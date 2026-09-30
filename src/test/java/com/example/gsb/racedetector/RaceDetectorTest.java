package com.example.gsb.racedetector;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests driven by explicit per-thread recorders (no real concurrency). */
class RaceDetectorTest {

    private AccessRecorder writer(RaceDetector detector, long id) {
        return detector.forThread(id, "writer-" + id);
    }

    // ------------------------------------------------------------------
    // Race identification
    // ------------------------------------------------------------------

    @Test
    void writeWriteFromDifferentThreadsIsARace() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.recordWrite("x", "A.run(A.java:10)");
        t2.recordWrite("x", "B.run(B.java:20)");

        RaceDetectionResult result = detector.detectRaces();

        assertThat(result.raceCount()).isEqualTo(1);
        RaceReport race = result.races().get(0);
        assertThat(race.location()).isEqualTo("x");
        assertThat(race.first().type()).isEqualTo(AccessType.WRITE);
        assertThat(race.second().type()).isEqualTo(AccessType.WRITE);
    }

    @Test
    void writeReadFromDifferentThreadsIsARace() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.recordWrite("x");
        t2.recordRead("x");

        assertThat(detector.detectRaces().raceCount()).isEqualTo(1);
    }

    @Test
    void readReadIsNeverARace() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.recordRead("x");
        t2.recordRead("x");

        assertThat(detector.detectRaces().hasRaces()).isFalse();
    }

    @Test
    void accessesFromSameThreadAreOrderedAndNotRaces() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);

        t1.recordWrite("x");
        t1.recordRead("x");
        t1.recordWrite("x");

        assertThat(detector.detectRaces().hasRaces()).isFalse();
    }

    @Test
    void racesOnDifferentLocationsAreReportedSeparately() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.recordWrite("a");
        t2.recordWrite("a");
        t1.recordWrite("b");
        t2.recordWrite("b");

        assertThat(detector.detectRaces().races())
                .extracting(RaceReport::location)
                .containsExactlyInAnyOrder("a", "b");
    }

    // ------------------------------------------------------------------
    // Lock protection: no false positives
    // ------------------------------------------------------------------

    @Test
    void lockProtectedAccessesProduceNoFalsePositive() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.lockAcquire("lock");
        t1.recordWrite("x");
        t1.lockRelease("lock");

        t2.lockAcquire("lock");
        t2.recordRead("x");
        t2.lockRelease("lock");

        assertThat(detector.detectRaces().hasRaces()).isFalse();
    }

    @Test
    void differentLocksDoNotOrderAccesses() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.lockAcquire("lock-1");
        t1.recordWrite("x");
        t1.lockRelease("lock-1");

        t2.lockAcquire("lock-2");
        t2.recordRead("x");
        t2.lockRelease("lock-2");

        assertThat(detector.detectRaces().raceCount()).isEqualTo(1);
    }

    @Test
    void accessOutsideCriticalSectionStillRaces() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        // t1 writes only after releasing the lock, so the write is not
        // ordered before t2's critical section.
        t1.lockAcquire("lock");
        t1.lockRelease("lock");
        t1.recordWrite("x");

        t2.lockAcquire("lock");
        t2.recordRead("x");
        t2.lockRelease("lock");

        assertThat(detector.detectRaces().raceCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Volatile protection: no false positives
    // ------------------------------------------------------------------

    @Test
    void volatileWriteReadOrdersLaterMemoryAccesses() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.recordWrite("data");
        t1.volatileWrite("flag");

        t2.volatileRead("flag");
        t2.recordRead("data");

        assertThat(detector.detectRaces().hasRaces()).isFalse();
    }

    @Test
    void volatileReadBeforeWriteDoesNotOrderAccesses() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t2.volatileRead("flag");
        t2.recordRead("data");

        t1.recordWrite("data");
        t1.volatileWrite("flag");

        assertThat(detector.detectRaces().raceCount()).isEqualTo(1);
    }

    @Test
    void unrelatedVolatileVariablesDoNotOrderAccesses() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.recordWrite("data");
        t1.volatileWrite("flag-a");

        t2.volatileRead("flag-b");
        t2.recordRead("data");

        assertThat(detector.detectRaces().raceCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Thread start/join ordering
    // ------------------------------------------------------------------

    @Test
    void threadStartOrdersParentBeforeChild() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder parent = writer(detector, 1);
        AccessRecorder child = writer(detector, 2);

        parent.recordWrite("x");
        parent.threadStart(2);
        child.recordRead("x");

        assertThat(detector.detectRaces().hasRaces()).isFalse();
    }

    @Test
    void threadJoinOrdersChildBeforeJoiner() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder child = writer(detector, 2);
        AccessRecorder parent = writer(detector, 1);

        child.recordWrite("x");
        parent.threadJoin(2);
        parent.recordRead("x");

        assertThat(detector.detectRaces().hasRaces()).isFalse();
    }

    // ------------------------------------------------------------------
    // Report content
    // ------------------------------------------------------------------

    @Test
    void reportContainsLocationThreadsCallSitesAndMissingSyncTypes() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.recordWrite("shared-balance", "Bank.deposit(Bank.java:33)");
        t2.recordRead("shared-balance", "Report.print(Report.java:58)");

        RaceReport report = detector.detectRaces().races().get(0);

        assertThat(report.location()).isEqualTo("shared-balance");

        assertThat(report.first().threadId()).isEqualTo(1L);
        assertThat(report.first().threadName()).isEqualTo("writer-1");
        assertThat(report.first().callSite()).isEqualTo("Bank.deposit(Bank.java:33)");

        assertThat(report.second().threadId()).isEqualTo(2L);
        assertThat(report.second().threadName()).isEqualTo("writer-2");
        assertThat(report.second().callSite()).isEqualTo("Report.print(Report.java:58)");

        assertThat(report.missingSynchronization()).containsExactlyInAnyOrder(
                SyncCategory.LOCK,
                SyncCategory.VOLATILE,
                SyncCategory.THREAD_LIFECYCLE);
    }

    @Test
    void reportDropsSyncCategoriesThatExistBetweenThreads() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.lockAcquire("shared-lock");
        t1.lockRelease("shared-lock");
        t2.lockAcquire("shared-lock");
        t2.lockRelease("shared-lock");

        t1.recordWrite("y");
        t2.recordWrite("y");

        RaceReport report = detector.detectRaces().races().get(0);
        assertThat(report.missingSynchronization())
                .doesNotContain(SyncCategory.LOCK)
                .contains(SyncCategory.VOLATILE, SyncCategory.THREAD_LIFECYCLE);
    }

    @Test
    void callSiteIsCapturedAutomatically() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t9 = detector.forThread(9L, "manual");
        AccessRecorder t10 = detector.forThread(10L, "other");

        detector.recordWrite("z");
        t9.recordWrite("z");
        t10.recordWrite("z");

        RaceDetectionResult result = detector.detectRaces();
        assertThat(result.races().get(0).first().callSite())
                .contains("RaceDetectorTest")
                .contains(".java:");
        assertThat(result.races().get(0).second().callSite())
                .contains("RaceDetectorTest")
                .contains(".java:");
    }

    @Test
    void blankLocationIsRejected() {
        RaceDetector detector = RaceDetector.create();
        assertThatThrownBy(() -> detector.recordRead(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------

    @Test
    void statisticsCountAccessesEventsComparisonsAndPrunedPairs() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        // 5 accesses -> 10 pairs. Same location A: (a1,a2) compared,
        // (a1,a5) same thread, (a2,a5) read/read. Location B: (a3,a4)
        // read/read. The other 6 pairs span different locations.
        t1.recordWrite("A");  // a1
        t2.recordRead("A");   // a2
        t1.recordRead("B");   // a3
        t2.recordRead("B");   // a4
        t1.recordRead("A");   // a5

        t1.lockAcquire("m");
        t1.lockRelease("m");

        RaceDetectionResult result = detector.detectRaces();
        RaceStatistics stats = result.statistics();

        assertThat(stats.registeredAccessCount()).isEqualTo(5);
        assertThat(stats.registeredSyncEventCount()).isEqualTo(2);
        assertThat(stats.comparisonCount()).isEqualTo(1);
        assertThat(stats.prunedPairCount()).isEqualTo(9);
        assertThat(result.raceCount()).isEqualTo(1);
        // invariant: every pair is either racy or pruned
        assertThat(stats.prunedPairCount() + result.raceCount()).isEqualTo(10);
    }

    @Test
    void lockOrderedPairsAreCountedAsComparisonsAndPruned() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        t1.lockAcquire("m");
        t1.recordWrite("x");
        t1.lockRelease("m");
        t2.lockAcquire("m");
        t2.recordWrite("x");
        t2.lockRelease("m");

        RaceDetectionResult result = detector.detectRaces();
        assertThat(result.hasRaces()).isFalse();
        assertThat(result.statistics().comparisonCount()).isEqualTo(1);
        assertThat(result.statistics().prunedPairCount()).isEqualTo(1);
    }

    @Test
    void detectionIsIdempotent() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);
        t1.recordWrite("x");
        t2.recordWrite("x");

        assertThat(detector.detectRaces().races())
                .isEqualTo(detector.detectRaces().races());
    }

    // ------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------

    @Test
    void samplingDropsSomeAccessesButStillFindsRaces() {
        RaceDetector detector = RaceDetector.sampling(0.5, 7L);
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);

        for (int i = 0; i < 100; i++) {
            t1.recordWrite("hot");
            t2.recordWrite("hot");
        }

        RaceDetectionResult result = detector.detectRaces();
        RaceStatistics stats = result.statistics();

        assertThat(stats.registeredAccessCount() + stats.sampledOutCount())
                .isEqualTo(200);
        assertThat(stats.sampledOutCount()).isPositive();
        assertThat(stats.registeredAccessCount()).isLessThan(200);
        assertThat(result.hasRaces()).isTrue();
    }

    @Test
    void raceReportsFormADistinctSet() {
        RaceDetector detector = RaceDetector.create();
        AccessRecorder t1 = writer(detector, 1);
        AccessRecorder t2 = writer(detector, 2);
        AccessRecorder t3 = writer(detector, 3);
        t1.recordWrite("x");
        t2.recordWrite("x");
        t3.recordWrite("x");

        List<RaceReport> races = detector.detectRaces().races();
        assertThat(races).hasSize(3);
        assertThat(new HashSet<>(races)).hasSize(3);
        assertThat(EnumSet.allOf(SyncCategory.class)).isNotEmpty();
        Set<String> threads = new HashSet<>();
        races.forEach(r -> {
            threads.add(r.first().threadName());
            threads.add(r.second().threadName());
        });
        assertThat(threads).containsExactlyInAnyOrder("writer-1", "writer-2", "writer-3");
    }
}
