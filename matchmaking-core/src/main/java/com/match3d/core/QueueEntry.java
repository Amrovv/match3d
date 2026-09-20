package com.match3d.core;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One entity waiting in the queue, which is either a player alone or a party
 * that queued together. The structures hold these rather than players, so a
 * party is polled, offered and seated as a unit and can never be half matched.
 *
 * An entry carries one rating and one queue time however many people it is,
 * which is what lets the index, the heap and the consent check stay unchanged.
 *
 * Immutable. Identity is the id alone.
 */
public sealed interface QueueEntry permits Player, Party {

    UUID id();

    /** What the index is keyed by. A party's is derived from its members. */
    int rating();

    /** Shared across a party, so its members widen together. */
    Instant queuedAt();

    /** Seats this entry takes. Always 1 for a player. */
    int size();

    /** The people in it. A player is their own sole member. */
    List<Player> members();

    /** Longest waiting first, breaking ties on id so ordering is total. */
    Comparator<QueueEntry> BY_WAIT_TIME =
            Comparator.comparing(QueueEntry::queuedAt).thenComparing(QueueEntry::id);
}
