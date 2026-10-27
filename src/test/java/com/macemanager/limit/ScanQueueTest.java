package com.macemanager.limit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ScanQueueTest {
    @Test
    void completionWaitsForDiscoveredWorkAndOtherJobsGetATurn() {
        ScanQueue queue = new ScanQueue(() -> 0);
        List<String> events = new ArrayList<>();
        ScanQueue.Job first = queue.submit(() -> events.add("first done"));
        first.add(() -> first.add(() -> events.add("discovered")));
        queue.submit(() -> events.add("second done")).add(() -> events.add("second"));

        queue.tick(1, 100);
        assertTrue(events.isEmpty());
        queue.tick(1, 100);
        assertEquals(List.of("second", "second done"), events);
        queue.tick(1, 100);
        assertEquals(List.of("second", "second done", "discovered", "first done"), events);
    }

    @Test
    void anExpensiveUnitStopsThisTickWithoutLosingPendingWork() {
        AtomicLong clock = new AtomicLong();
        ScanQueue queue = new ScanQueue(clock::get);
        List<String> events = new ArrayList<>();
        ScanQueue.Job job = queue.submit(() -> events.add("done"));
        job.add(() -> clock.addAndGet(11));
        job.add(() -> events.add("next"));

        queue.tick(100, 10);
        assertTrue(events.isEmpty());
        assertEquals(11, queue.maximumUnitNanos());
        assertEquals(11, queue.maximumTickNanos());
        queue.tick(100, 10);
        assertEquals(List.of("next", "done"), events);
    }

    @Test
    void failedWorkDoesNotCancelOtherJobsOrReportSuccess() {
        ScanQueue queue = new ScanQueue(() -> 0);
        List<String> events = new ArrayList<>();
        queue.submit(() -> fail("Must not report success"), exception -> events.add("failed"))
                .add(() -> { throw new IllegalStateException("broken inventory"); });
        queue.submit(() -> events.add("other done")).add(() -> events.add("other"));
        queue.tick(10, 100);
        assertEquals(List.of("failed", "other", "other done"), events);
    }

    @Test
    void shutdownDiscardsPendingWorkWithoutClaimingCompletion() {
        ScanQueue queue = new ScanQueue(() -> 0);
        queue.submit(() -> fail("Incomplete sweep")).add(() -> fail("Cancelled work"));
        queue.clear();
        queue.tick(10, 100);
    }
}
