package com.example.gsb.race;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RaceDetectorTest {

    @Test
    void detectsUnsynchronizedWriteWritePair() {
        RaceDetector detector = new RaceDetector();

        detector.recordAccess("counter", "t1", AccessType.WRITE, "Counter.bump(Counter.java:10)");
        detector.recordAccess("counter", "t2", AccessType.WRITE, "Counter.bump(Counter.java:10)");

        List<Race> races = detector.getRaces();
        assertThat(races).hasSize(1);

        Race race = races.get(0);
        assertThat(race.location()).isEqualTo("counter");
        assertThat(race.first().threadId()).isEqualTo("t1");
        assertThat(race.second().threadId()).isEqualTo("t2");
        assertThat(race.first().type()).isEqualTo(AccessType.WRITE);
        assertThat(race.second().type()).isEqualTo(AccessType.WRITE);
        assertThat(race.first().callSite()).isEqualTo("Counter.bump(Counter.java:10)");
        assertThat(race.second().callSite()).isEqualTo("Counter.bump(Counter.java:10)");
        assertThat(race.missingSynchronization()).containsExactlyInAnyOrder(SyncCategory.values());
        assertThat(race.toString()).contains("counter").contains("t1").contains("t2");
    }

    @Test
    void detectsWriteReadRaceInBothOrders() {
        RaceDetector writeThenRead = new RaceDetector();
        writeThenRead.recordAccess("map", "t1", AccessType.WRITE, "put");
        writeThenRead.recordAccess("map", "t2", AccessType.READ, "get");
        assertThat(writeThenRead.getRaces()).singleElement().satisfies(race -> {
            assertThat(race.first().type()).isEqualTo(AccessType.WRITE);
            assertThat(race.second().type()).isEqualTo(AccessType.READ);
        });

        RaceDetector readThenWrite = new RaceDetector();
        readThenWrite.recordAccess("map", "t1", AccessType.READ, "get");
        readThenWrite.recordAccess("map", "t2", AccessType.WRITE, "put");
        assertThat(readThenWrite.getRaces()).hasSize(1);
    }

    @Test
    void doesNotReportReadReadPairs() {
        RaceDetector detector = new RaceDetector();
        detector.recordAccess("config", "t1", AccessType.READ, "lookup");
        detector.recordAccess("config", "t2", AccessType.READ, "lookup");
        assertThat(detector.getRaces()).isEmpty();
    }

    @Test
    void doesNotReportAccessesFromTheSameThread() {
        RaceDetector detector = new RaceDetector();
        detector.recordAccess("counter", "t1", AccessType.WRITE, "a");
        detector.recordAccess("counter", "t1", AccessType.READ, "b");
        detector.recordAccess("counter", "t1", AccessType.WRITE, "c");
        assertThat(detector.getRaces()).isEmpty();
    }

    @Test
    void lockProtectedAccessesAreNotRaces() {
        RaceDetector detector = new RaceDetector();

        detector.onLockAcquire("t1", "lock-x");
        detector.recordAccess("counter", "t1", AccessType.WRITE, "Counter.bump");
        detector.onLockRelease("t1", "lock-x");

        detector.onLockAcquire("t2", "lock-x");
        detector.recordAccess("counter", "t2", AccessType.WRITE, "Counter.bump");
        detector.onLockRelease("t2", "lock-x");

        assertThat(detector.getRaces()).isEmpty();
    }

    @Test
    void differentLocksDoNotOrderAccesses() {
        RaceDetector detector = new RaceDetector();

        detector.onLockAcquire("t1", "lock-a");
        detector.recordAccess("counter", "t1", AccessType.WRITE, "a");
        detector.onLockRelease("t1", "lock-a");

        detector.onLockAcquire("t2", "lock-b");
        detector.recordAccess("counter", "t2", AccessType.WRITE, "b");
        detector.onLockRelease("t2", "lock-b");

        assertThat(detector.getRaces()).hasSize(1);
        assertThat(detector.getRaces().get(0).missingSynchronization()).contains(SyncCategory.LOCK);
    }

    @Test
    void volatileWriteReadOrdersSubsequentAccesses() {
        RaceDetector detector = new RaceDetector();

        detector.recordAccess("data", "producer", AccessType.WRITE, "Producer.publish");
        detector.onVolatileWrite("producer", "ready");
        detector.onVolatileRead("consumer", "ready");
        detector.recordAccess("data", "consumer", AccessType.READ, "Consumer.consume");

        assertThat(detector.getRaces()).isEmpty();
    }

    @Test
    void withoutVolatileEdgeTheSameAccessesRace() {
        RaceDetector detector = new RaceDetector();

        detector.recordAccess("data", "producer", AccessType.WRITE, "Producer.publish");
        detector.recordAccess("data", "consumer", AccessType.READ, "Consumer.consume");

        assertThat(detector.getRaces()).hasSize(1);
        assertThat(detector.getRaces().get(0).missingSynchronization()).contains(SyncCategory.VOLATILE);
    }

    @Test
    void unrelatedVolatileVariablesDoNotOrderAccesses() {
        RaceDetector detector = new RaceDetector();

        detector.onVolatileWrite("producer", "flag-a");
        detector.recordAccess("data", "producer", AccessType.WRITE, "publish");
        detector.onVolatileRead("consumer", "flag-b");
        detector.recordAccess("data", "consumer", AccessType.READ, "consume");

        assertThat(detector.getRaces()).hasSize(1);
    }

    @Test
    void threadStartOrdersParentBeforeChild() {
        RaceDetector detector = new RaceDetector();

        detector.recordAccess("shared", "parent", AccessType.WRITE, "init");
        detector.onThreadStart("parent", "child");
        detector.recordAccess("shared", "child", AccessType.READ, "use");

        assertThat(detector.getRaces()).isEmpty();
    }

    @Test
    void threadJoinOrdersChildBeforeParent() {
        RaceDetector detector = new RaceDetector();

        detector.recordAccess("shared", "child", AccessType.WRITE, "compute");
        detector.onThreadJoin("parent", "child");
        detector.recordAccess("shared", "parent", AccessType.READ, "use");

        assertThat(detector.getRaces()).isEmpty();
    }

    @Test
    void racesAreReportedPerLocationAndDeduplicatedPerPair() {
        RaceDetector detector = new RaceDetector();
        for (int i = 0; i < 5; i++) {
            detector.recordAccess("a", "t1", AccessType.WRITE, "a1");
            detector.recordAccess("a", "t2", AccessType.WRITE, "a2");
            detector.recordAccess("b", "t1", AccessType.WRITE, "b1");
            detector.recordAccess("b", "t2", AccessType.WRITE, "b2");
        }
        assertThat(detector.getRaces()).extracting(Race::location)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void capturesCallSiteFromTheStack() {
        RaceDetector detector = new RaceDetector();
        emit(detector);
        detector.recordAccess("x", "t2", AccessType.WRITE, "other");

        assertThat(detector.getRaces()).singleElement().satisfies(race ->
                assertThat(race.first().callSite()).contains("RaceDetectorTest.emit"));
    }

    private void emit(RaceDetector detector) {
        detector.recordAccess("x", "t1", AccessType.WRITE);
    }

    @Test
    void tracksStatistics() {
        RaceDetector detector = new RaceDetector();

        detector.onLockAcquire("t1", "lock-x");
        detector.recordAccess("counter", "t1", AccessType.WRITE, "a");
        detector.onLockRelease("t1", "lock-x");

        detector.onLockAcquire("t2", "lock-x");
        detector.recordAccess("counter", "t2", AccessType.READ, "b");
        detector.onLockRelease("t2", "lock-x");

        RaceDetectorStatistics stats = detector.statistics();
        assertThat(stats.registeredAccesses()).isEqualTo(2);
        assertThat(stats.registeredSyncEvents()).isEqualTo(4);
        assertThat(stats.comparisons()).isEqualTo(1);
        assertThat(stats.prunedPairs()).isEqualTo(1);
    }

    @Test
    void statisticsReflectRaceComparisonsAndGenericEventDispatcher() {
        RaceDetector detector = new RaceDetector();

        detector.recordSyncEvent(SyncEventType.LOCK_ACQUIRE, "t1", "lock-a");
        detector.recordSyncEvent(SyncEventType.LOCK_RELEASE, "t1", "lock-a");
        detector.recordAccess("counter", "t1", AccessType.WRITE, "a");
        detector.recordAccess("counter", "t2", AccessType.WRITE, "b");

        RaceDetectorStatistics stats = detector.statistics();
        assertThat(stats.registeredAccesses()).isEqualTo(2);
        assertThat(stats.registeredSyncEvents()).isEqualTo(2);
        assertThat(stats.comparisons()).isEqualTo(1);
        assertThat(stats.prunedPairs()).isZero();

        MemoryAccess first = detector.getRaces().get(0).first();
        assertThat(first.sequence()).isEqualTo(1L);
        assertThat(first.heldLocks()).isEqualTo(Set.of());
    }

    @Test
    void accessRecordedWhileHoldingLockReportsHeldLock() {
        RaceDetector detector = new RaceDetector();
        detector.onLockAcquire("t1", "outer");
        detector.onLockAcquire("t1", "inner");
        detector.recordAccess("x", "t1", AccessType.WRITE, "under-locks");
        detector.recordAccess("x", "t2", AccessType.WRITE, "bare");

        Race race = detector.getRaces().get(0);
        assertThat(race.first().heldLocks()).containsExactlyInAnyOrder("outer", "inner");
        assertThat(race.second().heldLocks()).isEmpty();
    }
}
