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
 * Forms lobbies, one anchor per call. The heap names the anchor, their wait
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

    /**
     * How long a failed anchor sits out before the heap sees them again.
     *
     * A policy rather than a physical constant, so it is configurable and this
     * is only the default.
     */
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

    private int retries = 0;
    private int contentionCooldowns = 0;
    private int starvationCooldowns = 0;
    private int aborts = 0;

    private final Duration cooldown;

    public MatchMaker(SkillIndex index, FairnessHeap heap) {
        this(index, heap, COOLDOWN);
    }

    public MatchMaker(SkillIndex index, FairnessHeap heap, Duration cooldown) {
        this.index = index;
        this.heap = heap;
        this.cooldown = cooldown;
    }

    /**
     * One anchor, and as many attempts as their budget allows. Selection runs
     * outside the lock and is verified under it, so a lobby forms only if all
     * ten are still queued at that moment. A member taken by another worker in
     * between is replaced and the lobby verified again.
     *
     * The budget is the seats standing at the first failed verify, so a nearly
     * complete lobby is worth more persistence than a bare one. It is set once,
     * since recomputing it from a later, fuller selection would let it grow and
     * the loop would not terminate.
     *
     * Empty means the anchor could not fill a lobby, spent the budget losing
     * members, or was itself matched elsewhere.
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

        boolean settled = false;
        int budget = -1;
        try {
            while (true) {
                commitLock.lock();
                try {
                    if (members.size() < LOBBY_SIZE) {
                        cool(anchor, now, false);
                        settled = true;
                        return Optional.empty();
                    }
                    List<Player> missing = missing(members);

                    if (missing.isEmpty()) {
                        commit(members);
                        settled = true;
                        return Optional.of(new Lobby(members));
                    }

                    if (missing.contains(anchor)) {
                        aborts++;
                        return Optional.empty();
                    }

                    if (budget < 0) {
                        budget = members.size() - missing.size();
                    }
                    if (budget == 0) {
                        cool(anchor, now, true);
                        settled = true;
                        return Optional.empty();
                    }

                    budget--;
                    retries++;
                    members.removeAll(missing);
                } finally {
                    commitLock.unlock();
                }
                refill(members, now);
            }
        } finally {
            if (!settled) {
                commitLock.lock();
                try {
                    index.insert(anchor);
                    heap.insert(anchor);
                }
                finally {
                    commitLock.unlock();
                }
            }
        }
    }

    /** Under the lock. Drains cooled anchors, then polls one. Null if none. */
    private Player drainAndPoll(Instant now) {
        drainCooled(now);
        Player anchor = heap.poll();
        if (anchor != null) {
            index.remove(anchor);
        }
        return anchor;
    }

    /** Under the lock. Members no longer queued, empty if all ten survive. */
    private List<Player> missing(List<Player> members) {
        List<Player> missing = new ArrayList<>();
        for (int i = 1; i < members.size(); i++) {
            Player member = members.get(i);
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

    /**
     * Under the lock. A matched anchor is counted as an abort rather than
     * cooled, since cooling would return them to the heap.
     */
    private void cool(Player anchor, Instant now, boolean lostToContention) {
        index.insert(anchor);
        if (lostToContention) contentionCooldowns++;
        else starvationCooldowns++;
        cooling.add(new Pending(anchor, now.plus(cooldown)));
    }

    /**
     * The members the anchor can seat now, short if they cannot fill a lobby.
     * Consent is mutual across every pair, not merely with the anchor.
     */
    private List<Player> selectMembers(Player anchor, Instant now) {
        List<Player> members = new ArrayList<>(LOBBY_SIZE);
        members.add(anchor);
        return refill(members, now);
    }

    /**
     * A queue time in the future counts as no wait, since intake and the
     * engine stamp it on different clocks.
     */
    private static int radiusOf(Player player, Instant now) {
        Duration waited = Duration.between(player.queuedAt(), now);
        return WideningFunction.ratingRadius(waited.isNegative() ? Duration.ZERO : waited);
    }

    /**
     * Fills held up to a full lobby, admitting only candidates who consent with
     * everyone already seated. Modifies and returns held, short if nobody does.
     *
     * Package private so a test can exercise a part filled lobby directly.
     */
    List<Player> refill(List<Player> held, Instant now) {
        Player anchor = held.get(0);
        int anchorRadius = radiusOf(anchor, now);

        // The window is the anchor's own reach, so it excludes only candidates
        // Overlap would reject anyway. An optimisation, not a filter.
        int windowLow = anchor.rating() - anchorRadius;
        int windowHigh = anchor.rating() + anchorRadius;

        Iterator<Player> candidateCursor =
                WaitTimeMerge.byWaitTime(index.playersInRange(windowLow, windowHigh)).iterator();

        Overlap overlap = overlapOf(held, now);

        while (held.size() < LOBBY_SIZE && candidateCursor.hasNext()) {
            Player candidate = candidateCursor.next();
            if (held.contains(candidate)) continue;

            Overlap extended = overlap.extendedBy(candidate, radiusOf(candidate, now));
            if (extended.valid()) {
                overlap = extended;
                held.add(candidate);
            }
        }
        return held;
    }

    /** The consent state of a seated group, folded from scratch. */
    private Overlap overlapOf(List<Player> seated, Instant now) {
        Player first = seated.get(0);
        Overlap overlap = Overlap.of(first, radiusOf(first, now));

        for (Player player : seated.subList(1, seated.size())) {
            overlap = overlap.extendedBy(player, radiusOf(player, now));
        }
        return overlap;
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

    int retryCount() {
        return retries;
    }

    int contentionCount() {
        return contentionCooldowns;
    }

    int starvationCount() {
        return starvationCooldowns;
    }

    int abortCount() {
        return aborts;
    }
}
