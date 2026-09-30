package com.example.gsb.race;

/**
 * A single registered synchronization event.
 *
 * @param sequence monotonically increasing event sequence number
 * @param type     event type
 * @param threadId thread performing the event
 * @param targetId lock id, volatile variable id, or the other thread id
 */
public record SyncEvent(
        long sequence,
        SyncEventType type,
        String threadId,
        String targetId) {
}
