package com.example.gsb.race;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the detector together with real concurrent execution: worker
 * threads mutate shared state while concurrently registering sampled
 * accesses, and the detector reports the race set actually observed.
 */
class ConcurrentRaceDetectionTest {

    private static final int THREADS = 4;
    private static final int ITERATIONS = 500;

    @Test
    void detectsRacesOnSampledAccessesFromRealThreads() throws InterruptedException {
        SamplingRaceDetector detector = new SamplingRaceDetector(1);
        int[] counter = {0};

        List<Thread> workers = startWorkers(THREADS, () -> {
            String threadId = Thread.currentThread().getName();
            for (int i = 0; i < ITERATIONS; i++) {
                detector.recordAccess("counter", threadId, AccessType.READ, "UnsynchronizedCounter.get");
                counter[0] = counter[0] + 1;
                detector.recordAccess("counter", threadId, AccessType.WRITE, "UnsynchronizedCounter.increment");
            }
        });
        for (Thread worker : workers) {
            worker.join();
        }

        List<Race> races = detector.getRaces();
        System.out.println("Detected race set (" + races.size() + "):");
        races.forEach(race -> System.out.println("  " + race));

        assertThat(races).isNotEmpty();
        assertThat(races).allSatisfy(race -> {
            assertThat(race.location()).isEqualTo("counter");
            assertThat(race.firstThread()).isNotEqualTo(race.secondThread());
            assertThat(race.missingSynchronization()).contains(SyncCategory.LOCK);
        });
        assertThat(races.stream().map(Race::firstThread).collect(Collectors.toSet()))
                .size()
                .isGreaterThanOrEqualTo(2);

        RaceDetectorStatistics stats = detector.statistics();
        assertThat(stats.registeredAccesses()).isEqualTo((long) THREADS * ITERATIONS * 2);
        assertThat(detector.skippedAccesses()).isZero();
        assertThat(stats.comparisons()).isGreaterThan(0);
        assertThat(detector.sampledAccesses()).isEqualTo(stats.registeredAccesses());
    }

    @Test
    void producesNoFalsePositivesWhenWorkersUseLocks() throws InterruptedException {
        SamplingRaceDetector detector = new SamplingRaceDetector(1);
        Lock guard = new ReentrantLock();
        int[] counter = {0};

        List<Thread> workers = startWorkers(THREADS, () -> {
            String threadId = Thread.currentThread().getName();
            for (int i = 0; i < ITERATIONS; i++) {
                guard.lock();
                detector.onLockAcquire(threadId, "guard");
                try {
                    counter[0] = counter[0] + 1;
                    detector.recordAccess("counter", threadId, AccessType.WRITE, "SynchronizedCounter.increment");
                } finally {
                    detector.onLockRelease(threadId, "guard");
                    guard.unlock();
                }
            }
        });
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(detector.getRaces()).isEmpty();
        assertThat(detector.statistics().prunedPairs()).isGreaterThan(0);
    }

    @Test
    void samplesOnlyOneAccessPerIntervalPerLocation() throws InterruptedException {
        int interval = 4;
        SamplingRaceDetector detector = new SamplingRaceDetector(interval);
        List<Thread> workers = startWorkers(THREADS, () -> {
            String threadId = Thread.currentThread().getName();
            for (int i = 0; i < ITERATIONS; i++) {
                detector.recordAccess("counter", threadId, AccessType.WRITE, "increment");
            }
        });
        for (Thread worker : workers) {
            worker.join();
        }

        long total = (long) THREADS * ITERATIONS;
        assertThat(detector.sampledAccesses() + detector.skippedAccesses()).isEqualTo(total);
        assertThat(detector.sampledAccesses()).isLessThan(total);
        assertThat(detector.skippedAccesses()).isLessThan(total);
        assertThat(detector.getRaces()).isNotEmpty();
    }

    private static List<Thread> startWorkers(int count, Runnable task) {
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread worker = new Thread(task, "worker-" + i);
            workers.add(worker);
            worker.start();
        }
        return workers;
    }
}
