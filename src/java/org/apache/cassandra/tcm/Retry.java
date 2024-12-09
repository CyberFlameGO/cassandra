/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.tcm;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import com.codahale.metrics.Meter;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.utils.Clock;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;

public abstract class Retry
{
    protected static final int MAX_TRIES = DatabaseDescriptor.getCmsDefaultRetryMaxTries();
    private static final int DEFAULT_BACKOFF_MS = DatabaseDescriptor.getDefaultRetryBackoff().toMilliseconds();
    private static final int DEFAULT_MAX_BACKOFF_MS = DatabaseDescriptor.getDefaultMaxRetryBackoff().toMilliseconds();

    protected final int maxTries;
    protected int tries;
    protected Meter retryMeter;

    public Retry(Meter retryMeter)
    {
        this(MAX_TRIES, retryMeter);
    }

    public Retry(int maxTries, Meter retryMeter)
    {
        this.maxTries = maxTries;
        this.retryMeter = retryMeter;
    }

    public int currentTries()
    {
        return tries;
    }

    public boolean reachedMax()
    {
        return tries >= maxTries;
    }

    public void maybeSleep()
    {
        sleepUninterruptibly(computeSleepFor(), TimeUnit.MILLISECONDS);
    }

    public long computeSleepFor()
    {
        tries++;
        retryMeter.mark();
        return sleepFor();
    }

    protected abstract long sleepFor();

    protected abstract long maxWait();

    public static class Jitter extends Retry
    {
        private final Random random;
        private final int maxJitterMs;

        public Jitter(Meter retryMeter)
        {
            this(MAX_TRIES, DEFAULT_BACKOFF_MS, new Random(), retryMeter);
        }

        private Jitter(int maxTries, int maxJitterMs, Random random, Meter retryMeter)
        {
            super(maxTries, retryMeter);
            this.random = random;
            this.maxJitterMs = maxJitterMs;
        }

        public long sleepFor()
        {
            int actualBackoff = ThreadLocalRandom.current().nextInt(maxJitterMs / 2, maxJitterMs);
            return random.nextInt(actualBackoff);
        }

        @Override
        protected long maxWait()
        {
            return maxJitterMs;
        }

        @Override
        public String toString()
        {
            return "Jitter{" +
                   ", maxTries=" + maxTries +
                   ", tries=" + tries +
                   ", maxJitterMs=" + maxJitterMs +
                   '}';
        }
    }

    public static class Backoff extends Retry
    {
        protected final int backoffMs;

        public Backoff(Meter retryMeter)
        {
            this(MAX_TRIES, DEFAULT_BACKOFF_MS, retryMeter);
        }

        public Backoff(int maxTries, int backoffMs, Meter retryMeter)
        {
            super(maxTries, retryMeter);
            this.backoffMs = backoffMs;
        }

        public long sleepFor()
        {
            return (long) tries * backoffMs;
        }

        @Override
        protected long maxWait()
        {
            return backoffMs;
        }

        @Override
        public String toString()
        {
            return "Backoff{" +
                   "backoffMs=" + backoffMs +
                   ", maxTries=" + maxTries +
                   ", tries=" + tries +
                   '}';
        }
    }

    public static class ExponentialBackoff extends Retry
    {
        private final long baseSleepTimeMillis;
        private final long maxSleepMillis;
        private final DoubleSupplier randomSource;

        public ExponentialBackoff(int maxAttempts, long baseSleepTimeMillis, long maxSleepMillis, DoubleSupplier randomSource, Meter retryMeter)
        {
            super(maxAttempts, retryMeter);
            this.baseSleepTimeMillis = baseSleepTimeMillis;
            this.maxSleepMillis = maxSleepMillis;
            this.randomSource = randomSource;
        }

        public ExponentialBackoff(Meter retryMeter)
        {
            this(MAX_TRIES, DEFAULT_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS, ThreadLocalRandom.current()::nextDouble, retryMeter);
        }

        @Override
        protected long sleepFor()
        {
            return org.apache.cassandra.utils.Backoff.ExponentialBackoff.computeWaitTime(tries, baseSleepTimeMillis, maxSleepMillis, randomSource);
        }

        @Override
        protected long maxWait()
        {
            return maxSleepMillis;
        }
    }

    public static class Deadline extends Retry
    {
        public final long deadlineNanos;
        protected final Retry delegate;

        public Deadline(long deadlineNanos, Retry delegate)
        {
            super(delegate.maxTries, delegate.retryMeter);
            assert deadlineNanos > 0 : String.format("Deadline should be strictly positive but was %d.", deadlineNanos);
            this.deadlineNanos = deadlineNanos;
            this.delegate = delegate;
        }

        public static Deadline at(long deadlineNanos, Retry delegate)
        {
            return new Deadline(deadlineNanos, delegate);
        }

        public static Deadline after(long timeoutNanos, Retry delegate)
        {
            return new Deadline(Clock.Global.nanoTime() + timeoutNanos, delegate);
        }

        public static Deadline wrap(Retry delegate)
        {
            long deadlineMillis = delegate.maxTries * delegate.maxWait();
            return new Deadline(Clock.Global.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis), delegate);
        }

        @Override
        public boolean reachedMax()
        {
            return delegate.reachedMax() || Clock.Global.nanoTime() > deadlineNanos;
        }

        public long remainingNanos()
        {
            return Math.max(0, deadlineNanos - Clock.Global.nanoTime());
        }

        @Override
        public int currentTries()
        {
            return delegate.currentTries();
        }

        @Override
        public long sleepFor()
        {
            return delegate.sleepFor();
        }

        @Override
        protected long maxWait()
        {
            return deadlineNanos;
        }

        public String toString()
        {
            return String.format("Deadline{remainingMs=%d, tries=%d/%d}", TimeUnit.NANOSECONDS.toMillis(remainingNanos()), currentTries(), delegate.maxTries);
        }
    }

}
