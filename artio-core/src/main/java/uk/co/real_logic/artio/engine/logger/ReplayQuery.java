/*
 * Copyright 2015-2018 Real Logic Ltd, Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.logger;

import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Long2ObjectCache;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.storage.messages.ReplayIndexRecordDecoder;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static org.agrona.UnsafeAccess.UNSAFE;
import static uk.co.real_logic.artio.engine.logger.ReplayIndexDescriptor.*;
import static uk.co.real_logic.artio.engine.logger.Replayer.MOST_RECENT_MESSAGE;

/**
 * Queries an index of a composite key of session id and sequence number.
 *
 * This object isn't thread-safe, but the underlying replay index is a single-writer, multiple-reader threadsafe index.
 */
public class ReplayQuery implements AutoCloseable
{
    private final MessageHeaderDecoder messageFrameHeader = new MessageHeaderDecoder();
    private final ReplayIndexRecordDecoder indexRecord = new ReplayIndexRecordDecoder();

    private final LongFunction<SessionQuery> newSessionQuery = SessionQuery::new;
    private final Long2ObjectCache<SessionQuery> fixSessionToIndex;
    private final String logFileDir;
    private final ExistingBufferFactory indexBufferFactory;
    private final int requiredStreamId;
    private final IdleStrategy idleStrategy;
    private final AeronArchive aeronArchive;
    private final ErrorHandler errorHandler;
    private final int archiveReplayStream;

    private Subscription replaySubscription;

    public ReplayQuery(
        final String logFileDir,
        final int cacheNumSets,
        final int cacheSetSize,
        final ExistingBufferFactory indexBufferFactory,
        final int requiredStreamId,
        final IdleStrategy idleStrategy,
        final AeronArchive aeronArchive,
        final ErrorHandler errorHandler,
        final int archiveReplayStream)
    {
        this.logFileDir = logFileDir;
        this.indexBufferFactory = indexBufferFactory;
        this.requiredStreamId = requiredStreamId;
        this.idleStrategy = idleStrategy;
        this.aeronArchive = aeronArchive;
        this.errorHandler = errorHandler;
        this.archiveReplayStream = archiveReplayStream;

        fixSessionToIndex = new Long2ObjectCache<>(cacheNumSets, cacheSetSize, SessionQuery::close);
    }

    /**
     *
     * @param handler the handler to pass the messages to
     * @param sessionId the FIX session id of the stream to replay.
     * @param beginSequenceNumber sequence number to begin replay at (inclusive).
     * @param beginSequenceIndex the sequence index to begin replay at (inclusive).
     * @param endSequenceNumber sequence number to end replay at (inclusive).
     * @param endSequenceIndex the sequence index to end replay at (inclusive).
     * @param logTag the operation to tag log entries with
     * @return number of messages replayed
     */
    public ReplayOperation query(
        final ControlledFragmentHandler handler,
        final long sessionId,
        final int beginSequenceNumber,
        final int beginSequenceIndex,
        final int endSequenceNumber,
        final int endSequenceIndex,
        final LogTag logTag)
    {
        return fixSessionToIndex
            .computeIfAbsent(sessionId, newSessionQuery)
            .query(handler, beginSequenceNumber, beginSequenceIndex, endSequenceNumber, endSequenceIndex, logTag);
    }

    public void close()
    {
        fixSessionToIndex.clear();
    }

    private final class SessionQuery implements AutoCloseable
    {
        private final ByteBuffer wrappedBuffer;
        private final long sessionId;
        private final UnsafeBuffer buffer;
        private final int capacity;

        SessionQuery(final long sessionId)
        {
            wrappedBuffer = indexBufferFactory.map(replayIndexFile(logFileDir, sessionId, requiredStreamId));
            buffer = new UnsafeBuffer(wrappedBuffer);
            capacity = recordCapacity(buffer.capacity());
            this.sessionId = sessionId;
        }

        ReplayOperation query(
            final ControlledFragmentHandler handler,
            final int beginSequenceNumber,
            final int beginSequenceIndex,
            final int endSequenceNumber,
            final int endSequenceIndex,
            final LogTag logTag)
        {
            messageFrameHeader.wrap(buffer, 0);

            final int actingBlockLength = messageFrameHeader.blockLength();
            final int actingVersion = messageFrameHeader.version();
            final boolean upToMostRecentMessage = endSequenceNumber == MOST_RECENT_MESSAGE;

            // LOOKUP THE RANGE FROM THE INDEX
            // NB: this is a List as we are looking up recordings in the correct order to replay them.
            final List<RecordingRange> ranges = new ArrayList<>();
            RecordingRange currentRange = null;

            long iteratorPosition = getIteratorPosition();
            long stopIteratingPosition = iteratorPosition + capacity;

            int lastSequenceNumber = -1;
            while (iteratorPosition != stopIteratingPosition)
            {
                final long changePosition = endChangeVolatile(buffer);

                // Lapped by writer
                if (changePosition > iteratorPosition && (iteratorPosition + capacity) <= beginChangeVolatile(buffer))
                {
                    iteratorPosition = changePosition;
                    stopIteratingPosition = iteratorPosition + capacity;
                }

                final int offset = offset(iteratorPosition, capacity);
                indexRecord.wrap(buffer, offset, actingBlockLength, actingVersion);
                final long beginPosition = indexRecord.position();
                final int sequenceIndex = indexRecord.sequenceIndex();
                final int sequenceNumber = indexRecord.sequenceNumber();
                final long recordingId = indexRecord.recordingId();
                final int readLength = indexRecord.length();

                UNSAFE.loadFence(); // LoadLoad required so previous loads don't move past version check below.

                // if the block was read atomically with no updates
                if (changePosition == beginChangeVolatile(buffer))
                {
                    idleStrategy.reset();

                    final boolean afterEnd = !upToMostRecentMessage && (sequenceIndex > endSequenceIndex ||
                        (sequenceIndex == endSequenceIndex && sequenceNumber > endSequenceNumber));
                    if (beginPosition == 0 || afterEnd)
                    {
                        break;
                    }

                    final boolean withinQueryRange = sequenceIndex > beginSequenceIndex ||
                        (sequenceIndex == beginSequenceIndex && sequenceNumber >= beginSequenceNumber);
                    if (withinQueryRange)
                    {
                        currentRange = addRange(
                            ranges,
                            currentRange,
                            lastSequenceNumber,
                            beginPosition,
                            sequenceNumber,
                            recordingId,
                            readLength);
                        lastSequenceNumber = sequenceNumber;
                        iteratorPosition += RECORD_LENGTH;
                    }
                    else // before start of query
                    {
                        iteratorPosition = skipToStart(beginSequenceNumber, iteratorPosition, sequenceNumber);
                    }
                }
                else
                {
                    idleStrategy.idle();
                }
            }

            if (currentRange != null)
            {
                ranges.add(currentRange);
            }

            return newReplayOperation(handler, ranges, logTag);
        }

        private long skipToStart(final int beginSequenceNumber, final long iteratorPosition, final int sequenceNumber)
        {
            if (sequenceNumber < beginSequenceNumber)
            {
                // Pre: sequenceIndex <= beginSequenceIndex
                return jumpPosition(beginSequenceNumber, sequenceNumber, iteratorPosition);
            }
            else
            {
                // Pre: sequenceIndex < beginSequenceIndex
                // Don't have a good way to estimate the jump, so just scan forward.
                return iteratorPosition + RECORD_LENGTH;
            }
        }

        private long jumpPosition(final int beginSequenceNumber, final int sequenceNumber, final long iteratorPosition)
        {
            final int sequenceNumberJump = beginSequenceNumber - sequenceNumber;
            return iteratorPosition + sequenceNumberJump * RECORD_LENGTH;
        }

        private ReplayOperation newReplayOperation(
            final ControlledFragmentHandler handler, final List<RecordingRange> ranges, final LogTag logTag)
        {
            if (replaySubscription == null)
            {
                replaySubscription = aeronArchive.context().aeron().addSubscription(
                    IPC_CHANNEL, archiveReplayStream);
            }

            DebugLogger.log(logTag,
                "ReplayQuery : Built new replay operation with Recording Ranges: %s%n",
                ranges);

            return new ReplayOperation(
                handler,
                ranges,
                aeronArchive,
                errorHandler,
                replaySubscription,
                archiveReplayStream,
                logTag);
        }

        private RecordingRange addRange(
            final List<RecordingRange> ranges,
            final RecordingRange currentRange,
            final int lastSequenceNumber,
            final long beginPosition,
            final int sequenceNumber,
            final long recordingId,
            final int readLength)
        {
            RecordingRange range = currentRange;
            if (range == null)
            {
                range = new RecordingRange(recordingId, sessionId);
            }
            else if (range.recordingId != recordingId)
            {
                ranges.add(range);
                range = new RecordingRange(recordingId, sessionId);
            }

            range.add(
                beginPosition - FRAME_ALIGNMENT,
                readLength + FRAME_ALIGNMENT);

            // FIX messages can be fragmented, so number of range adds != count
            if (lastSequenceNumber != sequenceNumber)
            {
                range.count++;
            }
            return range;
        }

        private long getIteratorPosition()
        {
            // positions on a monotonically increasing scale
            long iteratorPosition = beginChangeVolatile(buffer);
            // First iteration around you need to start at 0
            if (iteratorPosition < capacity)
            {
                iteratorPosition = 0;
            }
            return iteratorPosition;
        }

        public void close()
        {
            if (wrappedBuffer instanceof MappedByteBuffer)
            {
                IoUtil.unmap((MappedByteBuffer)wrappedBuffer);
            }

            CloseHelper.close(replaySubscription);
        }
    }

}
