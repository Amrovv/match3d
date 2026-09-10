package com.match3d.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Forms lobbies, one attempt per call. The heap names the anchor, their wait
 * sets a radius, the index yields candidates inside it, and consent must be
 * mutual across every pair.
 *
 * Every call shrinks the heap, so a caller loop terminates without tracking
 * what it tried. Owns the cooldown queue and the commit lock, and never reads
 * a clock: now is a parameter.
 */
public final class MatchMaker {

    /** Players per lobby, five a side. */
    static final int LOBBY_SIZE = 10;

    /** How long a failed anchor sits out before the heap sees them again. */
    static final Duration COOLDOWN = Duration.ofSeconds(10);

    private final ReentrantLock commitLock = new ReentrantLock();

    /** queuedAt is untouched, so a returning player keeps their full credit. */
    private record Pending(Player player, Instant readyAt) {
    }

    private static final Comparator<Pending> BY_READY_AT =
            Comparator.comparing(Pending::readyAt).thenComparing(p -> p.player().id());

    private final SkillIndex index;
    private final FairnessHeap heap;

    /** Failed anchors, soonest ready first. */
    private final PriorityQueue<Pending> cooling = new PriorityQueue<>(BY_READY_AT);

    public MatchMaker(SkillIndex index, FairnessHeap heap) {
        this.index = index;
        this.heap = heap;
    }

    /**
     * One attempt. Selection runs outside the lock and is verified under it,
     * so a lobby forms only if all ten are still queued at that moment.
     *
     * Empty means the anchor could not fill a lobby, lost a member to another
     * worker, or was itself matched elsewhere.
     */
    public Optional<Lobby> formLobby(Instant now) {
        Player anchor;
        commitLock.lock();
        try {
            anchor = drainAndPoll(now);
        } finally {
            commitLock.unlock();
        }
        if (anchor == null) return Optional.empty();

        List<Player> members = selectMembers(anchor, now);

        commitLock.lock();
        try {
            if (members.size() < LOBBY_SIZE) {
                cool(anchor, now, false);
                return Optional.empty();
            }
            if (!missing(members).isEmpty()) {
                cool(anchor, now, true);
                return Optional.empty();
            }
            commit(members);
        } finally {
            commitLock.unlock();
        }

        return Optional.of(new Lobby(members));
    }

    /** Under the lock. Drains cooled anchors, then polls one. Null if none. */
    private Player drainAndPoll(Instant now) {
        drainCooled(now);
        return heap.poll();
    }

    /** Under the lock. Members no longer queued, empty if all ten survive. */
    private List<Player> missing(List<Player> members) {
        List<Player> missing = new ArrayList<>();
        for (Player member : members) {
            if (!index.contains(member.id())) missing.add(member);
        }
        return missing;
    }

    /** Under the lock. Removes all ten from the heap, index and cooldown queue. */
    private void commit(List<Player> members) {
        for (Player member : members) {
            heap.remove(member.id());
            index.remove(member);
        }
        // A member may be cooling. Left in, drainCooled would hand them back.
        cooling.removeIf(pending -> members.contains(pending.player()));
    }

    /** Under the lock. Refuses a matched anchor, who would return to the heap. */
    private void cool(Player anchor, Instant now, boolean lostToContention) {
        if (!index.contains(anchor.id())) return;
        cooling.add(new Pending(anchor, now.plus(COOLDOWN)));
    }

    /**
     * The members the anchor can seat now, short if they cannot fill a lobby.
     * Consent is mutual across every pair, not merely with the anchor.
     */
    private List<Player> selectMembers(Player anchor, Instant now) {
        int anchorRadius = radiusOf(anchor, now);

        // The window is the anchor's own reach, so it excludes only candidates
        // Overlap would reject anyway. An optimisation, not a filter.
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
     * A queue time in the future counts as no wait, since intake and the
     * engine stamp it on different clocks.
     */
    private static int radiusOf(Player player, Instant now) {
        Duration waited = Duration.between(player.queuedAt(), now);
        return WideningFunction.ratingRadius(waited.isNegative() ? Duration.ZERO : waited);
    }

    /** Returns every player whose cooldown has expired to the heap. */
    private void drainCooled(Instant now) {
        while (!cooling.isEmpty() && !cooling.peek().readyAt().isAfter(now)) {
            heap.insert(cooling.poll().player());
        }
    }

    /** Players sitting out a cooldown. Exposed for tests. */
    int coolingCount() {
        return cooling.size();
    }
}
