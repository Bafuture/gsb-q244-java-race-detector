package com.example.gsb.racedetector;

/**
 * Access/synchronization handle bound to one thread. All methods are
 * thread-safe and may be invoked concurrently from different recorders.
 */
public final class AccessRecorder {

    private final RaceDetector detector;
    private final long threadId;
    private final String threadName;

    AccessRecorder(RaceDetector detector, long threadId, String threadName) {
        this.detector = detector;
        this.threadId = threadId;
        this.threadName = threadName;
    }

    public long threadId() {
        return threadId;
    }

    public String threadName() {
        return threadName;
    }

    public long recordRead(String location) {
        return detector.recordAccess(threadId, threadName, location, AccessType.READ, null);
    }

    public long recordRead(String location, String callSite) {
        return detector.recordAccess(threadId, threadName, location, AccessType.READ, callSite);
    }

    public long recordWrite(String location) {
        return detector.recordAccess(threadId, threadName, location, AccessType.WRITE, null);
    }

    public long recordWrite(String location, String callSite) {
        return detector.recordAccess(threadId, threadName, location, AccessType.WRITE, callSite);
    }

    public void lockAcquire(String lockId) {
        detector.lockAcquire(threadId, threadName, lockId);
    }

    public void lockRelease(String lockId) {
        detector.lockRelease(threadId, threadName, lockId);
    }

    public void volatileRead(String variableId) {
        detector.volatileRead(threadId, threadName, variableId);
    }

    public void volatileWrite(String variableId) {
        detector.volatileWrite(threadId, threadName, variableId);
    }

    /**
     * Records that this thread started {@code childThreadId}. Establishes an
     * edge from this thread's current clock to the child's clock; call it
     * before (or at the point of) the child being started.
     */
    public void threadStart(long childThreadId) {
        detector.threadStart(threadId, threadName, childThreadId);
    }

    /**
     * Records that this thread joined {@code joinedThreadId}. Merges the
     * joined thread's clock into this thread's clock.
     */
    public void threadJoin(long joinedThreadId) {
        detector.threadJoin(threadId, threadName, joinedThreadId);
    }
}
