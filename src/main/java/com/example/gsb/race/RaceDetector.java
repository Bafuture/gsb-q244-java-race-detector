package com.example.gsb.race;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vector-clock based data race detector.
 *
 * <p>Clients register memory accesses via {@link #recordAccess} and
 * synchronization events via the {@code on*} methods (or the generic
 * {@link #recordSyncEvent}). Two accesses to the same location, at least
 * one of them a write, are reported as a {@link Race} when neither
 * happens-before the other.</p>
 *
 * <p>Per location the detector keeps, for each thread, only the most
 * recent read and write together with the vector clock at which they
 * happened. A new access is compared against those summaries instead of
 * the full history; pairs already ordered by happens-before are pruned.
 * This keeps the detector sound (no false positives) while bounding the
 * number of comparisons.</p>
 *
 * <p>All public methods are synchronized, so registration may happen from
 * the very threads executing the monitored workload.</p>
 */
public final class RaceDetector {

    private final Map<String, VectorClock> threadClocks = new HashMap<>();
    private final Map<String, VectorClock> lockReleaseClocks = new HashMap<>();
    private final Map<String, VectorClock> volatileWriteClocks = new HashMap<>();
    private final Map<String, String> volatileLastWriter = new HashMap<>();
    private final Set<String> volatileEdges = new HashSet<>();
    private final Map<String, Deque<String>> heldLocks = new HashMap<>();
    private final Map<String, Set<String>> lifecycleEdges = new HashMap<>();
    private final Map<String, LocationState> locations = new HashMap<>();
    private final List<Race> races = new ArrayList<>();
    private final Set<String> raceKeys = new HashSet<>();

    private long accessSequence;
    private long syncSequence;
    private long comparisons;
    private long prunedPairs;

    // ------------------------------------------------------------------
    // Access registration
    // ------------------------------------------------------------------

    /**
     * Registers an access, capturing the call site from the current stack.
     */
    public void recordAccess(String location, String threadId, AccessType type) {
        recordAccess(location, threadId, type, captureCallSite());
    }

    /**
     * Registers an access with an explicit call site description.
     */
    public synchronized void recordAccess(String location, String threadId, AccessType type, String callSite) {
        VectorClock clock = threadClock(threadId);
        clock.increment(threadId);
        accessSequence++;

        MemoryAccess access = new MemoryAccess(
                accessSequence, location, threadId, type, callSite, heldLockSnapshot(threadId));
        LocationState state = locations.computeIfAbsent(location, key -> new LocationState());
        VectorClock current = clock.copy();

        if (type == AccessType.WRITE) {
            checkPairs(state.lastWrites, access, current);
            checkPairs(state.lastReads, access, current);
            state.lastWrites.put(threadId, new ClockedAccess(access, current));
        } else {
            checkPairs(state.lastWrites, access, current);
            state.lastReads.put(threadId, new ClockedAccess(access, current));
        }
    }

    private void checkPairs(Map<String, ClockedAccess> candidates, MemoryAccess access, VectorClock current) {
        for (Map.Entry<String, ClockedAccess> entry : candidates.entrySet()) {
            if (entry.getKey().equals(access.threadId())) {
                continue;
            }
            comparisons++;
            ClockedAccess prior = entry.getValue();
            if (prior.clock.isLessOrEqual(current)) {
                prunedPairs++;
                continue;
            }
            reportRace(prior, new ClockedAccess(access, current));
        }
    }

    private void reportRace(ClockedAccess prior, ClockedAccess current) {
        MemoryAccess first = prior.access;
        MemoryAccess second = current.access;
        String left = first.threadId() + '|' + first.type();
        String right = second.threadId() + '|' + second.type();
        String key = left.compareTo(right) <= 0
                ? first.location() + '|' + left + '|' + right
                : first.location() + '|' + right + '|' + left;
        if (!raceKeys.add(key)) {
            return;
        }
        races.add(new Race(first.location(), first, second, missingSynchronization(first, second)));
    }

    private Set<SyncCategory> missingSynchronization(MemoryAccess first, MemoryAccess second) {
        Set<SyncCategory> missing = EnumSet.noneOf(SyncCategory.class);
        Set<String> sharedLocks = new HashSet<>(first.heldLocks());
        sharedLocks.retainAll(second.heldLocks());
        if (sharedLocks.isEmpty()) {
            missing.add(SyncCategory.LOCK);
        }
        if (!volatileEdgeBetween(first.threadId(), second.threadId())) {
            missing.add(SyncCategory.VOLATILE);
        }
        if (!lifecycleRelated(first.threadId(), second.threadId())) {
            missing.add(SyncCategory.THREAD_LIFECYCLE);
        }
        return missing;
    }

    // ------------------------------------------------------------------
    // Synchronization events
    // ------------------------------------------------------------------

    /**
     * Generic dispatcher for explicit synchronization event registration.
     */
    public synchronized void recordSyncEvent(SyncEventType type, String threadId, String targetId) {
        switch (type) {
            case LOCK_ACQUIRE -> onLockAcquire(threadId, targetId);
            case LOCK_RELEASE -> onLockRelease(threadId, targetId);
            case VOLATILE_READ -> onVolatileRead(threadId, targetId);
            case VOLATILE_WRITE -> onVolatileWrite(threadId, targetId);
            case THREAD_START -> onThreadStart(threadId, targetId);
            case THREAD_JOIN -> onThreadJoin(threadId, targetId);
        }
    }

    public synchronized void onLockAcquire(String threadId, String lockId) {
        noteSyncEvent();
        VectorClock clock = threadClock(threadId);
        clock.increment(threadId);
        VectorClock released = lockReleaseClocks.get(lockId);
        if (released != null) {
            clock.merge(released);
        }
        heldLocks.computeIfAbsent(threadId, key -> new ArrayDeque<>()).addLast(lockId);
    }

    public synchronized void onLockRelease(String threadId, String lockId) {
        noteSyncEvent();
        VectorClock clock = threadClock(threadId);
        clock.increment(threadId);
        lockReleaseClocks.put(lockId, clock.copy());
        Deque<String> held = heldLocks.get(threadId);
        if (held != null) {
            held.removeLastOccurrence(lockId);
        }
    }

    public synchronized void onVolatileWrite(String threadId, String variableId) {
        noteSyncEvent();
        VectorClock clock = threadClock(threadId);
        clock.increment(threadId);
        volatileWriteClocks.put(variableId, clock.copy());
        volatileLastWriter.put(variableId, threadId);
    }

    public synchronized void onVolatileRead(String threadId, String variableId) {
        noteSyncEvent();
        VectorClock clock = threadClock(threadId);
        clock.increment(threadId);
        VectorClock written = volatileWriteClocks.get(variableId);
        if (written != null) {
            clock.merge(written);
        }
        String writer = volatileLastWriter.get(variableId);
        if (writer != null && !writer.equals(threadId)) {
            volatileEdges.add(writer + "->" + threadId);
        }
    }

    public synchronized void onThreadStart(String parentThreadId, String childThreadId) {
        noteSyncEvent();
        VectorClock parentClock = threadClock(parentThreadId);
        parentClock.increment(parentThreadId);
        threadClock(childThreadId).merge(parentClock);
        lifecycleEdges.computeIfAbsent(parentThreadId, key -> new HashSet<>()).add(childThreadId);
    }

    public synchronized void onThreadJoin(String threadId, String joinedThreadId) {
        noteSyncEvent();
        VectorClock clock = threadClock(threadId);
        clock.increment(threadId);
        clock.merge(threadClock(joinedThreadId));
        lifecycleEdges.computeIfAbsent(joinedThreadId, key -> new HashSet<>()).add(threadId);
    }

    private void noteSyncEvent() {
        syncSequence++;
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    /**
     * Returns the de-duplicated set of detected races, in detection order.
     */
    public synchronized List<Race> getRaces() {
        return List.copyOf(races);
    }

    public synchronized RaceDetectorStatistics statistics() {
        return new RaceDetectorStatistics(accessSequence, syncSequence, comparisons, prunedPairs);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private VectorClock threadClock(String threadId) {
        return threadClocks.computeIfAbsent(threadId, key -> new VectorClock());
    }

    private Set<String> heldLockSnapshot(String threadId) {
        Deque<String> held = heldLocks.get(threadId);
        return held == null ? Set.of() : Set.copyOf(held);
    }

    private boolean volatileEdgeBetween(String firstThread, String secondThread) {
        return volatileEdges.contains(firstThread + "->" + secondThread)
                || volatileEdges.contains(secondThread + "->" + firstThread);
    }

    private boolean lifecycleRelated(String firstThread, String secondThread) {
        return reachable(firstThread, secondThread) || reachable(secondThread, firstThread);
    }

    private boolean reachable(String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(from);
        visited.add(from);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : lifecycleEdges.getOrDefault(current, Set.of())) {
                if (next.equals(to)) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return false;
    }

    private static String captureCallSite() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            if (className.equals(Thread.class.getName())
                    || className.equals(RaceDetector.class.getName())
                    || className.equals(SamplingRaceDetector.class.getName())) {
                continue;
            }
            return className + "." + element.getMethodName()
                    + "(" + element.getFileName() + ":" + element.getLineNumber() + ")";
        }
        return "unknown";
    }

    private static final class LocationState {
        private final Map<String, ClockedAccess> lastReads = new HashMap<>();
        private final Map<String, ClockedAccess> lastWrites = new HashMap<>();
    }

    private static final class ClockedAccess {
        private final MemoryAccess access;
        private final VectorClock clock;

        private ClockedAccess(MemoryAccess access, VectorClock clock) {
            this.access = access;
            this.clock = clock;
        }
    }
}
