package com.example.gsb.racedetector;

/**
 * A registered synchronization event.
 *
 * @param sequence  detector-wide synchronization event sequence number
 * @param type      lock / volatile / thread-lifecycle event type
 * @param threadId  thread that performed the event
 * @param threadName name of that thread
 * @param targetId  lock id, volatile variable id, or the other thread involved
 */
public record SyncEvent(
        long sequence,
        SyncEventType type,
        long threadId,
        String threadName,
        String targetId
) {
    public SyncEvent {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
    }
}
