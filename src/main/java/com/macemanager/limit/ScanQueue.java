package com.macemanager.limit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Cooperative server-thread work. A unit (one inventory/entity/query) is never interrupted. */
public final class ScanQueue {
    private final Deque<Job> jobs = new ArrayDeque<>();
    private final LongSupplier clock;
    private long maximumTickNanos;
    private long maximumUnitNanos;

    public ScanQueue() {
        this(System::nanoTime);
    }

    ScanQueue(LongSupplier clock) {
        this.clock = clock;
    }

    public Job submit(Runnable complete) {
        return submit(complete, exception -> complete.run());
    }

    public Job submit(Runnable complete, Consumer<RuntimeException> failed) {
        Job job = new Job(complete, failed);
        jobs.addLast(job);
        return job;
    }

    public void tick(int maximumUnits, long budgetNanos) {
        long start = clock.getAsLong();
        int units = 0;
        while (!jobs.isEmpty() && units < maximumUnits) {
            Job job = jobs.removeFirst();
            Runnable work = job.work.pollFirst();
            if (work != null) {
                long unitStart = clock.getAsLong();
                try {
                    work.run();
                } catch (RuntimeException exception) {
                    job.failure = exception;
                    Logger.getLogger("MaceManager").warning("Scan unit failed: " + exception);
                }
                maximumUnitNanos = Math.max(maximumUnitNanos, clock.getAsLong() - unitStart);
                units++;
            }
            if (job.work.isEmpty()) {
                if (job.failure == null) {
                    job.complete.run();
                } else {
                    job.failed.accept(job.failure);
                }
            } else {
                jobs.addLast(job);
            }
            if (clock.getAsLong() - start >= budgetNanos) {
                break;
            }
        }
        maximumTickNanos = Math.max(maximumTickNanos, clock.getAsLong() - start);
    }

    public long maximumTickNanos() {
        return maximumTickNanos;
    }

    public long maximumUnitNanos() {
        return maximumUnitNanos;
    }

    public void clear() {
        jobs.clear();
    }

    public static final class Job {
        private final Deque<Runnable> work = new ArrayDeque<>();
        private final Runnable complete;
        private final Consumer<RuntimeException> failed;
        private RuntimeException failure;

        private Job(Runnable complete, Consumer<RuntimeException> failed) {
            this.complete = complete;
            this.failed = failed;
        }

        public void add(Runnable task) {
            work.addLast(task);
        }
    }
}
