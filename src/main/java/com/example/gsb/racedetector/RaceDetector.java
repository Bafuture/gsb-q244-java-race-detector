package com.example.gsb.racedetector;

import java.lang.StackWalker.StackFrame;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A data race detector based on per-thread vector clocks and explicit
 * happens-before synchronization events.
 *
 * <p>All registered accesses are kept (or dropped by a {@link SamplingPolicy})
 * together with a snapshot of the accessing thread's vector clock. Race
 * detection then compares every same-location access pair: a pair races when
 * at least one access is a write and neither vector clock happens-before the
 * other.
 *
 * <p>The detector is safe to use from real concurrent threads: every public
 * method is synchronized.
 */
public final class RaceDetector {

    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final SamplingPolicy samplingPolicy;

    private final Map<Long, VectorClock> threadClocks = new HashMap<>();
    private final Map<String, VectorClock> lockClocks = new HashMap<>();
    private final Map<String, VectorClock> volatileClocks = new HashMap<>();

    private final List<MemoryAccess> accesses = new ArrayList<>();
    private final List<SyncEvent> syncEvents = new ArrayList<>();

    /** lock id -> threads that acquired or released it */
    private final Map<String, Set<Long>> lockThreads = new HashMap<>();
    /** volatile variable id -> threads that read or wrote it */
    private final Map<String, Set<Long>> volatileThreads = new HashMap<>();
    /** unordered thread pairs linked by start/join */
    private final Set<Long> lifecycleEdges = new HashSet<>();

    private final Map<Long, AccessRecorder> recorders = new HashMap<>();

    private long nextAccessSequence = 1L;
    private long nextSyncSequence = 1L;
    private long sampledOutCount = 0L;

    private RaceStatistics lastStatistics =
            new RaceStatistics(0, 0, 0, 0, 0);

    private RaceDetector(SamplingPolicy samplingPolicy) {
        this.samplingPolicy = samplingPolicy;
    }

    /** Creates a detector that keeps every access. */
    public static RaceDetector create() {
        return new RaceDetector(SamplingPolicy.all());
    }

    /** Creates a detector that independently samples accesses. */
    public static RaceDetector sampling(double rate, long seed) {
        return new RaceDetector(SamplingPolicy.uniform(rate, seed));
    }

    /** Returns (creating if needed) a recorder for an explicit thread. */
    public synchronized AccessRecorder forThread(long threadId, String threadName) {
        return recorders.computeIfAbsent(threadId,
                id -> new AccessRecorder(this, id,
                        threadName != null ? threadName : ("thread-" + id)));
    }

    /** Returns a recorder bound to the calling JVM thread. */
    public AccessRecorder currentThread() {
        Thread thread = Thread.currentThread();
        return forThread(thread.getId(), thread.getName());
    }

    // ------------------------------------------------------------------
    // Memory access registration
    // ------------------------------------------------------------------

    public long recordRead(String location) {
        Thread thread = Thread.currentThread();
        return recordAccess(thread.getId(), thread.getName(),
                location, AccessType.READ, null);
    }

    public long recordWrite(String location) {
        Thread thread = Thread.currentThread();
        return recordAccess(thread.getId(), thread.getName(),
                location, AccessType.WRITE, null);
    }

    public long recordAccess(String location, AccessType type) {
        Thread thread = Thread.currentThread();
        return recordAccess(thread.getId(), thread.getName(), location, type, null);
    }

    public long recordAccess(String location, AccessType type, String callSite) {
        Thread thread = Thread.currentThread();
        return recordAccess(thread.getId(), thread.getName(), location, type, callSite);
    }

    synchronized long recordAccess(long threadId, String threadName,
                                   String location, AccessType type, String callSite) {
        long sequence = nextAccessSequence++;
        VectorClock clock = clockOf(threadId);
        clock.increment(threadId);
        if (!samplingPolicy.sample()) {
            sampledOutCount++;
            return sequence;
        }
        String site = callSite != null ? callSite : captureCallSite();
        accesses.add(new MemoryAccess(sequence, threadId,
                threadName != null ? threadName : ("thread-" + threadId),
                location, type, site, clock.snapshot()));
        return sequence;
    }

    // ------------------------------------------------------------------
    // Synchronization events
    // ------------------------------------------------------------------

    synchronized void lockAcquire(long threadId, String threadName, String lockId) {
        tick(threadId);
        clockOf(threadId).mergeFrom(lockClocks.computeIfAbsent(lockId, k -> new VectorClock()));
        lockThreads.computeIfAbsent(lockId, k -> new HashSet<>()).add(threadId);
        syncEvents.add(new SyncEvent(nextSyncSequence++, SyncEventType.LOCK_ACQUIRE,
                threadId, name(threadId, threadName), lockId));
    }

    synchronized void lockRelease(long threadId, String threadName, String lockId) {
        tick(threadId);
        lockClocks.computeIfAbsent(lockId, k -> new VectorClock())
                .mergeFrom(clockOf(threadId));
        lockThreads.computeIfAbsent(lockId, k -> new HashSet<>()).add(threadId);
        syncEvents.add(new SyncEvent(nextSyncSequence++, SyncEventType.LOCK_RELEASE,
                threadId, name(threadId, threadName), lockId));
    }

    synchronized void volatileRead(long threadId, String threadName, String variableId) {
        tick(threadId);
        clockOf(threadId).mergeFrom(
                volatileClocks.computeIfAbsent(variableId, k -> new VectorClock()));
        volatileThreads.computeIfAbsent(variableId, k -> new HashSet<>()).add(threadId);
        syncEvents.add(new SyncEvent(nextSyncSequence++, SyncEventType.VOLATILE_READ,
                threadId, name(threadId, threadName), variableId));
    }

    synchronized void volatileWrite(long threadId, String threadName, String variableId) {
        tick(threadId);
        volatileClocks.computeIfAbsent(variableId, k -> new VectorClock())
                .mergeFrom(clockOf(threadId));
        volatileThreads.computeIfAbsent(variableId, k -> new HashSet<>()).add(threadId);
        syncEvents.add(new SyncEvent(nextSyncSequence++, SyncEventType.VOLATILE_WRITE,
                threadId, name(threadId, threadName), variableId));
    }

    synchronized void threadStart(long parentId, String parentName, long childId) {
        tick(parentId);
        clockOf(childId).mergeFrom(clockOf(parentId));
        lifecycleEdges.add(edgeKey(parentId, childId));
        syncEvents.add(new SyncEvent(nextSyncSequence++, SyncEventType.THREAD_START,
                parentId, name(parentId, parentName), String.valueOf(childId)));
    }

    synchronized void threadJoin(long joinerId, String joinerName, long joinedId) {
        tick(joinerId);
        clockOf(joinerId).mergeFrom(clockOf(joinedId));
        lifecycleEdges.add(edgeKey(joinerId, joinedId));
        syncEvents.add(new SyncEvent(nextSyncSequence++, SyncEventType.THREAD_JOIN,
                joinerId, name(joinerId, joinerName), String.valueOf(joinedId)));
    }

    // ------------------------------------------------------------------
    // Race detection
    // ------------------------------------------------------------------

    /**
     * Compares all registered access pairs and reports data races.
     *
     * <p>A pair is pruned (excluded from race candidacy) when the two
     * accesses target different locations, come from the same thread, are
     * both reads, or are ordered by happens-before. Only same-location,
     * cross-thread pairs containing a write reach the vector-clock
     * comparison (and are counted as comparisons).
     */
    public synchronized RaceDetectionResult detectRaces() {
        Map<String, List<MemoryAccess>> byLocation = new LinkedHashMap<>();
        for (MemoryAccess access : accesses) {
            byLocation.computeIfAbsent(access.location(), k -> new ArrayList<>()).add(access);
        }

        long totalPairs = accesses.size() * (accesses.size() - 1L) / 2L;
        long sameLocationPairs = 0L;
        for (List<MemoryAccess> group : byLocation.values()) {
            sameLocationPairs += group.size() * (group.size() - 1L) / 2L;
        }
        long pruned = totalPairs - sameLocationPairs;
        long comparisons = 0L;

        List<RaceReport> races = new ArrayList<>();
        for (Map.Entry<String, List<MemoryAccess>> entry : byLocation.entrySet()) {
            List<MemoryAccess> group = entry.getValue();
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    MemoryAccess a = group.get(i);
                    MemoryAccess b = group.get(j);

                    if (a.threadId() == b.threadId()
                            || (!a.isWrite() && !b.isWrite())) {
                        pruned++;
                        continue;
                    }

                    comparisons++;
                    VectorClock clockA = VectorClock.fromSnapshot(a.clockSnapshot());
                    VectorClock clockB = VectorClock.fromSnapshot(b.clockSnapshot());
                    if (clockA.happensBefore(clockB) || clockB.happensBefore(clockA)) {
                        pruned++;
                        continue;
                    }

                    races.add(new RaceReport(entry.getKey(), a, b,
                            missingSynchronization(a.threadId(), b.threadId())));
                }
            }
        }

        lastStatistics = new RaceStatistics(accesses.size(), syncEvents.size(),
                comparisons, pruned, sampledOutCount);
        return new RaceDetectionResult(races, lastStatistics);
    }

    public synchronized RaceStatistics getStatistics() {
        return lastStatistics;
    }

    public synchronized int accessCount() {
        return accesses.size();
    }

    public synchronized int syncEventCount() {
        return syncEvents.size();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Synchronization categories for which no shared synchronization object
     * or lifecycle edge exists between the two threads.
     */
    private Set<SyncCategory> missingSynchronization(long threadId1, long threadId2) {
        EnumSet<SyncCategory> missing = EnumSet.allOf(SyncCategory.class);
        if (sharesSyncObject(lockThreads, threadId1, threadId2)) {
            missing.remove(SyncCategory.LOCK);
        }
        if (sharesSyncObject(volatileThreads, threadId1, threadId2)) {
            missing.remove(SyncCategory.VOLATILE);
        }
        if (lifecycleEdges.contains(edgeKey(threadId1, threadId2))) {
            missing.remove(SyncCategory.THREAD_LIFECYCLE);
        }
        return missing;
    }

    private boolean sharesSyncObject(Map<String, Set<Long>> syncToThreads,
                                     long threadId1, long threadId2) {
        for (Set<Long> threads : syncToThreads.values()) {
            if (threads.contains(threadId1) && threads.contains(threadId2)) {
                return true;
            }
        }
        return false;
    }

    private static long edgeKey(long a, long b) {
        return a < b ? a * 31L + b : b * 31L + a;
    }

    private VectorClock clockOf(long threadId) {
        return threadClocks.computeIfAbsent(threadId, k -> new VectorClock());
    }

    private void tick(long threadId) {
        clockOf(threadId).increment(threadId);
    }

    private static String name(long threadId, String name) {
        return name != null ? name : "thread-" + threadId;
    }

    private String captureCallSite() {
        return STACK_WALKER.walk(frames -> frames
                .filter(frame -> frame.getDeclaringClass() != RaceDetector.class
                        && frame.getDeclaringClass() != AccessRecorder.class)
                .findFirst()
                .map(RaceDetector::formatFrame)
                .orElse("unknown"));
    }

    private static String formatFrame(StackFrame frame) {
        String fileName = frame.getFileName() != null ? frame.getFileName() : "?";
        return frame.getClassName() + "." + frame.getMethodName()
                + "(" + fileName + ":" + frame.getLineNumber() + ")";
    }
}
