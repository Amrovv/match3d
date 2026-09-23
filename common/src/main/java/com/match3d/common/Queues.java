package com.match3d.common;

/**
 * Queue names both services agree on. One queue per direction, so events for
 * the same entry arrive in the order they were sent. Each message names its
 * event in the type property.
 */
public final class Queues {

    /** EntryQueued and EntryLeft. */
    public static final String TO_MATCHMAKING = "match3d.to-matchmaking";

    /** EntryRejected and EntryMatched. */
    public static final String TO_INTAKE = "match3d.to-intake";

    private Queues() {
    }
}
