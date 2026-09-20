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
    private record Pending(QueueEntry entry, Instant readyAt) {
    }

    private static final Comparator<Pending> BY_READY_AT =
            Comparator.comparing(Pending::readyAt).thenComparing(p -> p.entry().id());

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
        QueueEntry anchor;
        commitLock.lock();
        try {
            anchor = drainAndPoll(now);
        } finally {
            commitLock.unlock();
        }
        if (anchor == null) return Optional.empty();

        Selection selection = new Selection(anchor, now);
        selection.fill();
        List<QueueEntry> members = selection.members();

        boolean settled = false;
        int budget = -1;
        try {
            while (true) {
                commitLock.lock();
                try {
                    if (seats(members) < LOBBY_SIZE) {
                        cool(anchor, now, false);
                        settled = true;
                        return Optional.empty();
                    }
                    List<QueueEntry> missing = missing(members);

                    if (missing.isEmpty()) {
                        commit(members);
                        settled = true;
                        return Optional.of(new Lobby(playersIn(members)));
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
                    selection.drop(missing);
                } finally {
                    commitLock.unlock();
                }
                selection.fill();
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
    private QueueEntry drainAndPoll(Instant now) {
        drainCooled(now);
        QueueEntry anchor = heap.poll();
        if (anchor != null) {
            index.remove(anchor);
        }
        return anchor;
    }

    /** Under the lock. Members no longer queued, empty if all ten survive. */
    private List<QueueEntry> missing(List<QueueEntry> members) {
        List<QueueEntry> missing = new ArrayList<>();
        for (int i = 1; i < members.size(); i++) {
            QueueEntry member = members.get(i);
            if (!index.contains(member.id())) missing.add(member);
        }
        return missing;
    }

    /** Under the lock. Removes all ten from the heap, index and cooldown queue. */
    private void commit(List<QueueEntry> members) {
        for (QueueEntry member : members) {
            heap.remove(member.id());
            index.remove(member);
        }
        // A member may be cooling. Left in, drainCooled would hand them back.
        cooling.removeIf(pending -> members.contains(pending.entry()));
    }

    /**
     * Under the lock. A matched anchor is counted as an abort rather than
     * cooled, since cooling would return them to the heap.
     */
    private void cool(QueueEntry anchor, Instant now, boolean lostToContention) {
        index.insert(anchor);
        if (lostToContention) contentionCooldowns++;
        else starvationCooldowns++;
        cooling.add(new Pending(anchor, now.plus(cooldown)));
    }

    /**
     * One anchor's walk through their window, resumable across attempts.
     *
     * The window is seeded once, in the constructor, and that seeding is the
     * expensive part of a pass: it touches every occupied rating in the window,
     * where seating a player costs a logarithm in the bucket count. Rebuilding
     * it for every retry would make a retry cost what a whole fresh pass costs,
     * so the cursor is kept and the walk resumes where it stopped.
     *
     * The cost is that a resumed walk cannot reconsider. Dropping a member
     * widens the reach, so a candidate rejected earlier might be acceptable
     * now, and the cursor has already passed them. A resumed pass can therefore
     * fail to fill where a fresh one would have succeeded.
     *
     * Held by one worker for one pass and never shared. The cursor draws from
     * live views, so it may offer a player who has already left and will not
     * see a rating that became occupied after seeding. The verify under the
     * lock is what makes both harmless.
     */
    final class Selection {

        private final QueueEntry anchor;
        private final Instant now;
        private final Iterator<QueueEntry> cursor;
        private final List<QueueEntry> members = new ArrayList<>(LOBBY_SIZE);

        private Overlap overlap;

        Selection(QueueEntry anchor, Instant now) {
            this.anchor = anchor;
            this.now = now;

            int anchorRadius = radiusOf(anchor, now);

            // The window is the anchor's own reach, so it excludes only
            // candidates Overlap would reject anyway. An optimisation, not a
            // filter.
            int windowLow = anchor.rating() - anchorRadius;
            int windowHigh = anchor.rating() + anchorRadius;

            this.cursor = WaitTimeMerge.byWaitTime(
                    index.entriesInRange(windowLow, windowHigh)).iterator();

            members.add(anchor);
            this.overlap = Overlap.of(anchor, anchorRadius);
        }

        /** Seats candidates until the lobby is full or the window is spent. */
        void fill() {
            while (seats(members) < LOBBY_SIZE && cursor.hasNext()) {
                QueueEntry candidate = cursor.next();
                if (members.contains(candidate)) continue;

                // A party is seated whole or not at all, so one that overruns
                // the seats left is passed over rather than split.
                if (seats(members) + candidate.size() > LOBBY_SIZE) continue;

                Overlap extended = overlap.extendedBy(candidate, radiusOf(candidate, now));
                if (extended.valid()) {
                    overlap = extended;
                    members.add(candidate);
                }
            }
        }

        /**
         * Removes members and rebuilds the consent state from those left.
         *
         * Overlap is a lossy fold, so it cannot be un-extended. Refolding is
         * nine constant time steps, against a re-seed of the whole window.
         */
        void drop(List<QueueEntry> gone) {
            members.removeAll(gone);
            overlap = overlapOf(members, now);
        }

        /** The seated members, the anchor first. Short until fill succeeds. */
        List<QueueEntry> members() {
            return members;
        }
    }

    /**
     * A queue time in the future counts as no wait, since intake and the
     * engine stamp it on different clocks.
     */
    private static int radiusOf(QueueEntry entry, Instant now) {
        Duration waited = Duration.between(entry.queuedAt(), now);
        return WideningFunction.ratingRadius(waited.isNegative() ? Duration.ZERO : waited);
    }

    /** The consent state of a seated group, folded from scratch. */
    private Overlap overlapOf(List<QueueEntry> seated, Instant now) {
        QueueEntry first = seated.get(0);
        Overlap overlap = Overlap.of(first, radiusOf(first, now));

        for (QueueEntry entry : seated.subList(1, seated.size())) {
            overlap = overlap.extendedBy(entry, radiusOf(entry, now));
        }
        return overlap;
    }

    /** Returns every player whose cooldown has expired to the heap. */
    private void drainCooled(Instant now) {
        while (!cooling.isEmpty() && !cooling.peek().readyAt().isAfter(now)) {
            heap.insert(cooling.poll().entry());
        }
    }

    /** Seats these entries take, since a party takes more than one. */
    private static int seats(List<QueueEntry> entries) {
        int seats = 0;
        for (QueueEntry entry : entries) {
            seats += entry.size();
        }
        return seats;
    }

    /** The ten players inside the seated entries, the anchor's first. */
    private static List<Player> playersIn(List<QueueEntry> entries) {
        List<Player> players = new ArrayList<>(LOBBY_SIZE);
        for (QueueEntry entry : entries) {
            players.addAll(entry.members());
        }
        return players;
    }

    /** Entries sitting out a cooldown. Exposed for tests. */
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
