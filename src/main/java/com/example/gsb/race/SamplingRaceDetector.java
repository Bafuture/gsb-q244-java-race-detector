package com.example.gsb.race;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper around {@link RaceDetector} that registers only one out of every
 * {@code interval} accesses per memory location. Synchronization events
 * are always forwarded: dropping them would break happens-before tracking.
 *
 * <p>Safe to use directly from concurrently executing workload threads.</p>
 */
public final class SamplingRaceDetector {

    private final RaceDetector delegate;
    private final int interval;
    private final Map<String, AtomicLong> perLocationCounters = new ConcurrentHashMap<>();
    private final AtomicLong sampledAccesses = new AtomicLong();
    private final AtomicLong skippedAccesses = new AtomicLong();

    public SamplingRaceDetector(int interval) {
        if (interval < 1) {
            throw new IllegalArgumentException("interval must be >= 1, was " + interval);
        }
        this.interval = interval;
        this.delegate = new RaceDetector();
    }

    public void recordAccess(String location, String threadId, AccessType type) {
        recordAccess(location, threadId, type, null);
    }

    public void recordAccess(String location, String threadId, AccessType type, String callSite) {
        long count = perLocationCounters
                .computeIfAbsent(location, key -> new AtomicLong())
                .incrementAndGet();
        if ((count - 1) % interval == 0) {
            if (callSite == null) {
                delegate.recordAccess(location, threadId, type);
            } else {
                delegate.recordAccess(location, threadId, type, callSite);
            }
            sampledAccesses.incrementAndGet();
        } else {
            skippedAccesses.incrementAndGet();
        }
    }

    public void recordSyncEvent(SyncEventType type, String threadId, String targetId) {
        delegate.recordSyncEvent(type, threadId, targetId);
    }

    public void onLockAcquire(String threadId, String lockId) {
        delegate.onLockAcquire(threadId, lockId);
    }

    public void onLockRelease(String threadId, String lockId) {
        delegate.onLockRelease(threadId, lockId);
    }

    public void onVolatileWrite(String threadId, String variableId) {
        delegate.onVolatileWrite(threadId, variableId);
    }

    public void onVolatileRead(String threadId, String variableId) {
        delegate.onVolatileRead(threadId, variableId);
    }

    public void onThreadStart(String parentThreadId, String childThreadId) {
        delegate.onThreadStart(parentThreadId, childThreadId);
    }

    public void onThreadJoin(String threadId, String joinedThreadId) {
        delegate.onThreadJoin(threadId, joinedThreadId);
    }

    /** The race set actually detected on the sampled accesses. */
    public List<Race> getRaces() {
        return delegate.getRaces();
    }

    public RaceDetectorStatistics statistics() {
        return delegate.statistics();
    }

    public long sampledAccesses() {
        return sampledAccesses.get();
    }

    public long skippedAccesses() {
        return skippedAccesses.get();
    }

    public int interval() {
        return interval;
    }
}
