package com.example.gsb.racedetector;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Combines the detector with real multi-threaded execution: worker threads
 * mutate shared state while registering (sampled) accesses on the same batch
 * of concurrent operations.
 */
class ConcurrentIntegrationTest {

    private static final int THREADS = 4;
    private static final int ITERATIONS = 200;

    @Test
    void findsRacesInUnsynchronizedConcurrentWorkload() throws InterruptedException {
        RaceDetector detector = RaceDetector.create();
        int[] counter = {0};
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);

        List<Thread> workers = launch(THREADS, ready, start, () -> {
            AccessRecorder recorder = detector.currentThread();
            for (int i = 0; i < ITERATIONS; i++) {
                recorder.recordRead("counter");
                counter[0] = counter[0] + 1;
                recorder.recordWrite("counter");
            }
        });
        start.countDown();
        joinAll(workers);

        RaceDetectionResult result = detector.detectRaces();

        assertThat(result.races()).isNotEmpty();
        assertThat(result.races())
                .allSatisfy(race -> {
                    assertThat(race.location()).isEqualTo("counter");
                    assertThat(race.first().threadId())
                            .isNotEqualTo(race.second().threadId());
                    assertThat(race.missingSynchronization()).contains(SyncCategory.LOCK);
                });

        long participatingThreads = result.races().stream()
                .flatMap(r -> java.util.stream.Stream.of(
                        r.first().threadId(), r.second().threadId()))
                .distinct().count();
        assertThat(participatingThreads).isEqualTo(THREADS);
    }

    @Test
    void findsRacesWhileSamplingTheSameBatch() throws InterruptedException {
        RaceDetector detector = RaceDetector.sampling(0.25, 42L);
        long[] counter = {0L};
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);

        List<Thread> workers = launch(THREADS, ready, start, () -> {
            AccessRecorder recorder = detector.currentThread();
            for (int i = 0; i < ITERATIONS; i++) {
                recorder.recordRead("hot-counter");
                counter[0]++;
                recorder.recordWrite("hot-counter");
            }
        });
        start.countDown();
        joinAll(workers);

        RaceDetectionResult result = detector.detectRaces();
        RaceStatistics stats = result.statistics();

        assertThat(stats.sampledOutCount()).isPositive();
        assertThat(stats.registeredAccessCount())
                .isLessThan((long) THREADS * ITERATIONS * 2);
        assertThat(stats.registeredAccessCount()).isPositive();
        assertThat(result.races()).isNotEmpty();
        assertThat(result.races())
                .extracting(RaceReport::location)
                .containsOnly("hot-counter");
    }

    @Test
    void lockProtectedConcurrentWorkloadReportsNoRaces() throws InterruptedException {
        RaceDetector detector = RaceDetector.create();
        ReentrantLock lock = new ReentrantLock();
        int[] counter = {0};
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);

        List<Thread> workers = launch(THREADS, ready, start, () -> {
            AccessRecorder recorder = detector.currentThread();
            for (int i = 0; i < ITERATIONS; i++) {
                lock.lock();
                try {
                    recorder.lockAcquire("counter-lock");
                    recorder.recordRead("counter");
                    counter[0]++;
                    recorder.recordWrite("counter");
                    recorder.lockRelease("counter-lock");
                } finally {
                    lock.unlock();
                }
            }
        });
        start.countDown();
        joinAll(workers);

        RaceDetectionResult result = detector.detectRaces();
        assertThat(counter[0]).isEqualTo(THREADS * ITERATIONS);
        assertThat(result.races()).isEmpty();
        assertThat(result.statistics().comparisonCount()).isGreaterThan(0);
    }

    @Test
    void volatileHandoffInRealThreadsReportsNoRaces() throws InterruptedException {
        RaceDetector detector = RaceDetector.create();
        Holder holder = new Holder();

        Thread producer = new Thread(() -> {
            AccessRecorder recorder = detector.currentThread();
            recorder.recordWrite("payload");
            holder.payload = 42;
            recorder.volatileWrite("ready-flag");
            holder.ready = true;
        }, "producer");
        Thread consumer = new Thread(() -> {
            AccessRecorder recorder = detector.currentThread();
            while (!holder.ready) {
                Thread.onSpinWait();
            }
            recorder.volatileRead("ready-flag");
            holder.seen = holder.payload;
            recorder.recordRead("payload");
        }, "consumer");

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        RaceDetectionResult result = detector.detectRaces();
        assertThat(holder.seen).isEqualTo(42);
        assertThat(result.races()).isEmpty();
    }

    private static class Holder {
        volatile boolean ready;
        int payload;
        int seen;
    }

    private static List<Thread> launch(int count, CountDownLatch ready,
                                       CountDownLatch start, Runnable task) {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                task.run();
            }, "worker-" + i);
            threads.add(thread);
            thread.start();
        }
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return threads;
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }
}
