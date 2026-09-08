package com.match3d.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Forms lobbies, one attempt per call.
 *
 * The heap names the anchor, the widening function turns their wait into a
 * radius, the index yields the candidates inside it, and every candidate must
 * accept the anchor as much as the anchor accepts them.
 *
 * A call either forms one lobby or puts one anchor on cooldown, so every call
 * shrinks the heap and a caller loop terminates without tracking what it tried.
 *
 * Owns the cooldown queue, so it is stateful. Never reads a clock, now is a
 * parameter, the same rule the widening function follows.
 */
public final class MatchMaker {

    /** Players per lobby, five a side. */
    static final int LOBBY_SIZE = 10;

    /** How long a failed anchor sits out before the heap sees them again. */
    static final Duration COOLDOWN = Duration.ofSeconds(10);

    /**
     * A failed anchor waiting out their cooldown.
     *
     * queuedAt is untouched, so a returning player is credited for the whole
     * time they sat out and lands back at the front of the heap.
     */
    private record Pending(Player player, Instant readyAt) {
    }

    private static final Comparator<Pending> BY_READY_AT =
            Comparator.comparing(Pending::readyAt).thenComparing(p -> p.player().id());

    private final SkillIndex index;
    private final FairnessHeap heap;

    /**
     * Failed anchors, soonest ready first.
     */
    private final PriorityQueue<Pending> cooling = new PriorityQueue<>(BY_READY_AT);

    public MatchMaker(SkillIndex index, FairnessHeap heap) {
        this.index = index;
        this.heap = heap;
    }

    /**
     * Makes one attempt to form a lobby.
     *
     * Returns the lobby if one formed, or empty if the anchor could not fill
     * one and was put on cooldown, or if nobody was available to anchor.
     */
    public Optional<Lobby> formLobby(Instant now) {
        // Drain any cooled anchors back to the heap.
        drainCooled(now);

        Player anchor = heap.poll();
        if (anchor == null) {
            return Optional.empty();
        }

        List<Player> members = selectMembers(anchor, now);

        if (members.size() < LOBBY_SIZE) {
            cooling.add(new Pending(anchor, now.plus(COOLDOWN)));
            return Optional.empty();
        }

        for (Player member : members) {
            heap.remove(member.id());
            index.remove(member);
        }

        // A member may be a failed anchor still cooling, since cooling bars a
        // player from anchoring rather than from being recruited. Left in,
        // drainCooled would hand a matched player back to the heap.
        cooling.removeIf(pending -> members.contains(pending.player()));

        return Optional.of(new Lobby(members));
    }

    /**
     * The lobby the anchor can form right now, or empty if they cannot fill one.
     *
     * Every member must accept every other member, not merely the anchor.
     */
    private List<Player> selectMembers(Player anchor, Instant now) {
        int anchorRadius = radiusOf(anchor, now);

        // Bounds who is worth looking at, while Overlap decides who is taken.
        // Kept apart from the overlap values, which start here and then move.
        int windowLow = anchor.rating() - anchorRadius;
        int windowHigh = anchor.rating() + anchorRadius;

        Iterator<Player> candidateCursor =
                WaitTimeMerge.byWaitTime(index.playersInRange(windowLow, windowHigh)).iterator();

        Overlap overlap = Overlap.of(anchor, anchorRadius);

        List<Player> members = new ArrayList<>(LOBBY_SIZE);
        members.add(anchor);

        while (members.size() < LOBBY_SIZE && candidateCursor.hasNext()) {
            Player candidate = candidateCursor.next();
            if (candidate.equals(anchor)) continue;

            Overlap extended = overlap.extendedBy(candidate, radiusOf(candidate, now));
            if (extended.valid()) {
                overlap = extended;
                members.add(candidate);
            }
        }
        return members;
    }

    /**
     * The rating radius a player accepts, given how long they have waited.
     */
    private static int radiusOf(Player player, Instant now) {
        return WideningFunction.ratingRadius(Duration.between(player.queuedAt(), now));
    }

    /**
     * Returns every player whose cooldown has expired to the heap.
     */
    private void drainCooled(Instant now) {
        while (!cooling.isEmpty() && !cooling.peek().readyAt().isAfter(now)) {
            heap.insert(cooling.poll().player());
        }
    }

    /**
     * The number of players sitting out a cooldown. Exposed so a test can prove
     * a failed anchor left the heap and came back.
     */
    int coolingCount() {
        return cooling.size();
    }
}
