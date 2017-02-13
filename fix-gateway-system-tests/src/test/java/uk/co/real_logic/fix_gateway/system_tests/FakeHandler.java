/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.system_tests;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.fix_gateway.Timing;
import uk.co.real_logic.fix_gateway.dictionary.IntDictionary;
import uk.co.real_logic.fix_gateway.library.*;
import uk.co.real_logic.fix_gateway.messages.DisconnectReason;
import uk.co.real_logic.fix_gateway.otf.OtfParser;
import uk.co.real_logic.fix_gateway.session.Session;

import java.util.*;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.junit.Assert.assertNotEquals;

public class FakeHandler
    implements SessionHandler, SessionAcquireHandler, SessionExistsHandler, SentPositionHandler
{
    private final OtfParser parser;
    private final FakeOtfAcceptor acceptor;

    private final List<Session> sessions = new ArrayList<>();
    private final Set<Session> slowSessions = new HashSet<>();
    private final Deque<CompleteSessionId> completeSessionIds = new ArrayDeque<>();

    private Session lastSession;
    private boolean hasDisconnected = false;
    private long sentPosition;

    FakeHandler(final FakeOtfAcceptor acceptor)
    {
        this.acceptor = acceptor;
        parser = new OtfParser(acceptor, new IntDictionary());
    }

    // ----------- EVENTS -----------

    public Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int libraryId,
        final Session session,
        final int sequenceIndex,
        final int messageType,
        final long timestampInNs,
        final long position)
    {
        parser.onMessage(buffer, offset, length);
        acceptor.lastMessage().sequenceIndex(sequenceIndex);
        acceptor.forSession(session);
        return CONTINUE;
    }

    public void onTimeout(final int libraryId, final Session session)
    {
    }

    public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow)
    {
        if (hasBecomeSlow)
        {
            slowSessions.add(session);
        }
        else
        {
            slowSessions.remove(session);
        }
    }

    public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason)
    {
        sessions.remove(session);
        hasDisconnected = true;
        return CONTINUE;
    }

    public SessionHandler onSessionAcquired(final Session session)
    {
        assertNotEquals(Session.UNKNOWN, session.id());
        sessions.add(session);
        this.lastSession = session;
        return this;
    }

    public Action onSendCompleted(final long position)
    {
        this.sentPosition = position;
        return CONTINUE;
    }

    public void onSessionExists(
        final FixLibrary library,
        final long sessionId,
        final String localCompId,
        final String localSubId,
        final String localLocationId,
        final String remoteCompId,
        final String remoteSubId,
        final String remoteLocationId,
        final String username,
        final String password)
    {
        completeSessionIds.add(new CompleteSessionId(localCompId, remoteCompId, sessionId));
    }

    // ----------- END EVENTS -----------

    public void resetSession()
    {
        lastSession = null;
    }

    public List<Session> sessions()
    {
        return sessions;
    }

    public boolean hasDisconnected()
    {
        return hasDisconnected;
    }

    public long awaitSessionId(final Runnable poller)
    {
        while (!hasSeenSession())
        {
            poller.run();
            Thread.yield();
        }

        return lastSessionId().sessionId();
    }

    public boolean hasSeenSession()
    {
        return !completeSessionIds.isEmpty();
    }

    public void clearSessions()
    {
        completeSessionIds.clear();
    }

    public long sentPosition()
    {
        return sentPosition;
    }

    long awaitSessionIdFor(
        final String initiatorId,
        final String acceptorId,
        final Runnable poller,
        final int timeoutInMs)
    {
        return Timing.withTimeout(
            "Unable to get session id for: " + initiatorId + " - " + acceptorId,
            () ->
            {
                poller.run();

                return completeSessionIds
                    .stream()
                    .filter((sid) ->
                        sid.initiatorCompId().equals(initiatorId) && sid.acceptorCompId().equals(acceptorId))
                    .findFirst();
            },
            timeoutInMs).sessionId();
    }

    public String lastAcceptorCompId()
    {
        return lastSessionId().acceptorCompId();
    }

    public String lastInitiatorCompId()
    {
        return lastSessionId().initiatorCompId();
    }

    public Session lastSession()
    {
        return lastSession;
    }

    private CompleteSessionId lastSessionId()
    {
        return completeSessionIds.peekFirst();
    }

    public static final class CompleteSessionId
    {
        private final String acceptorCompId;
        private final String initiatorCompId;
        private final long sessionId;

        private CompleteSessionId(final String acceptorCompId, final String initiatorCompId, final long sessionId)
        {
            this.acceptorCompId = acceptorCompId;
            this.initiatorCompId = initiatorCompId;
            this.sessionId = sessionId;
        }

        public String acceptorCompId()
        {
            return acceptorCompId;
        }

        public String initiatorCompId()
        {
            return initiatorCompId;
        }

        public long sessionId()
        {
            return sessionId;
        }
    }

    public boolean isSlow(final Session session)
    {
        return slowSessions.contains(session);
    }
}
