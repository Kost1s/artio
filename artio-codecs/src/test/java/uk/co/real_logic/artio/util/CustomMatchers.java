/*
 * Copyright 2015-2017 Real Logic Ltd.
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

package uk.co.real_logic.artio.util;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Function;

/**
 * Custom hamcrest matchers to support our own types in tests.
 */
public final class CustomMatchers
{

    private CustomMatchers()
    {
    }

    /**
     * Assert that a range of an ascii flyweight equals a String.
     */
    public static Matcher<AsciiBuffer> sequenceEqualsAscii(final String expectedValue, final int offset)
    {
        return sequenceEqualsAscii(expectedValue, offset, expectedValue.length());
    }

    public static Matcher<AsciiBuffer> sequenceEqualsAscii(
        final String expectedValue, final int offset, final int length)
    {
        Objects.requireNonNull(expectedValue);

        return new TypeSafeMatcher<AsciiBuffer>()
        {
            private String string;

            protected boolean matchesSafely(final AsciiBuffer item)
            {
                this.string = item.getAscii(offset, length);

                return expectedValue.equals(string);
            }

            public void describeTo(final Description description)
            {
                description.appendText("Expected a String like: ");
                description.appendValue(expectedValue);
                description.appendText("But actually was: ");
                description.appendValue(string);
            }
        };
    }

    /**
     * Doesn't use getters for properties, like hamcrest.
     */
    public static <T> Matcher<T> hasFluentProperty(final String name, final Matcher<?> valueMatcher)
    {
        return new TypeSafeMatcher<T>()
        {
            public String error = null;

            protected boolean matchesSafely(final T item)
            {
                try
                {
                    final Class<?> aClass = item.getClass();
                    Method method;
                    try
                    {
                        method = aClass.getDeclaredMethod(name);
                    }
                    catch (final NoSuchMethodException ignore)
                    {
                        method = aClass.getMethod(name);
                    }
                    method.setAccessible(true);
                    final Object value = method.invoke(item);
                    return valueMatcher.matches(value);
                }
                catch (final IllegalAccessException | InvocationTargetException | NoSuchMethodException ex)
                {
                    ex.printStackTrace();
                    error = ex.getMessage();
                    return false;
                }
            }

            public void describeTo(final Description description)
            {
                if (error != null)
                {
                    description.appendText(error);
                }
                else
                {
                    description.appendText("A method called " + name + " with ");
                    valueMatcher.describeTo(description);
                }
            }
        };
    }

    public static <T, V> Matcher<T> hasResult(
        final String name,
        final Function<T, V> getter,
        final Matcher<?> valueMatcher)
    {
        return new TypeSafeMatcher<T>()
        {
            protected boolean matchesSafely(final T item)
            {
                final Object value = getter.apply(item);
                return valueMatcher.matches(value);
            }

            protected void describeMismatchSafely(final T item, final Description mismatchDescription)
            {
                final V value = getter.apply(item);
                mismatchDescription
                    .appendText("was ")
                    .appendValue(value);
            }

            public void describeTo(final Description description)
            {
                description
                    .appendText("A method called ")
                    .appendValue(name)
                    .appendText(" with ");
                valueMatcher.describeTo(description);
            }
        };
    }

    public static <T> Matcher<T> hasFluentProperty(final String name, final Object value)
    {
        return hasFluentProperty(name, Matchers.equalTo(value));
    }

}
