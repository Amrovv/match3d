package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchMakerTest {

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private SkillIndex index;
    private FairnessHeap heap;
    private MatchMaker matcher;

    @BeforeEach
    void setUp() {
        index = new SkillIndex();
        heap = new FairnessHeap();
        matcher = new MatchMaker(index, heap);
    }

    /** A player queued waitedFor seconds before NOW, joined to both structures at once. */
    private Player join(int rating, int waitedFor) {
        Player player = new Player(UUID.randomUUID(), rating, NOW.minusSeconds(waitedFor));
        index.insert(player);
        heap.insert(player);
        return player;
    }

    /**
     * count players at one rating, the first of them the longest waiting.
     *
     * Waits are seconds apart so the anchor is never decided by an id tiebreak,
     * and short enough that every radius is close to the base one.
     */
    private List<Player> joinCluster(int rating, int count) {
        List<Player> joined = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            joined.add(join(rating, count - i));
        }
        return joined;
    }

    /**
     * Nine players spread across nine ratings around 1500, none of them the
     * anchor's own rating, all waiting less than the anchor.
     *
     * The spread is 1480 to 1525, and the shortest wait here is ten seconds,
     * which buys a radius of 84. Every one of them therefore reaches both ends
     * of that spread, so the lobby is valid without depending on the anchor's
     * much wider window.
     */
    private List<Player> joinSpread() {
        int[] ratings = {1480, 1485, 1490, 1495, 1505, 1510, 1515, 1520, 1525};
        List<Player> joined = new ArrayList<>();
        for (int i = 0; i < ratings.length; i++) {
            joined.add(join(ratings[i], 90 - i * 10));
        }
        return joined;
    }

    // nobody to match

    @Test void testEmptyQueueFormsNothing() {
        assertEquals(Optional.empty(), matcher.formLobby(NOW), "There is nobody to anchor on");
        assertEquals(0, matcher.coolingCount(), "Nothing failed, so nobody is sitting out");
    }

    // forming a lobby

    @Test void testFormsALobbyFromTenClusteredPlayersPos() {
        joinCluster(1000, 10);

        Optional<Lobby> formed = matcher.formLobby(NOW);

        assertTrue(formed.isPresent(), "Ten players at one rating all accept each other");
        assertEquals(MatchMaker.LOBBY_SIZE, formed.orElseThrow().members().size(),
                "A lobby is exactly ten players");
    }

    @Test void testLobbyMembersAreDistinct() {
        joinCluster(1000, 10);

        List<Player> members = matcher.formLobby(NOW).orElseThrow().members();
        Set<UUID> ids = new HashSet<>();
        members.forEach(member -> ids.add(member.id()));

        assertEquals(MatchMaker.LOBBY_SIZE, ids.size(),
                "The anchor is seated once, not once as anchor and again as a candidate");
    }

    @Test void testAnchorIsTheLongestWaiter() {
        List<Player> joined = joinCluster(1000, 10);

        assertEquals(joined.get(0), matcher.formLobby(NOW).orElseThrow().anchor(),
                "The heap names the anchor, so the lobby is built around whoever waited longest");
    }

    @Test void testTakesTenAndLeavesTheRest() {
        joinCluster(1000, 15);

        assertTrue(matcher.formLobby(NOW).isPresent(), "Fifteen candidates are more than enough");
        assertEquals(5, index.playerCount(), "One attempt seats ten, it does not drain the queue");
        assertEquals(5, heap.size(), "The heap keeps the five who were not seated");
    }

    @Test void testFormsALobbyAcrossSeveralRatingsPos() {
        Player anchor = join(1500, 300);
        joinSpread();

        Lobby formed = matcher.formLobby(NOW).orElseThrow();

        Set<Integer> ratings = new HashSet<>();
        formed.members().forEach(member -> ratings.add(member.rating()));

        assertEquals(MatchMaker.LOBBY_SIZE, formed.members().size(), "A lobby is exactly ten players");
        assertEquals(anchor, formed.anchor(), "The longest waiter anchors");
        assertEquals(MatchMaker.LOBBY_SIZE, ratings.size(),
                "Ten distinct ratings, so the lobby was merged out of ten separate buckets"
                        + " rather than lifted from one");
    }

    @Test void testEveryMemberOfASpreadLobbyReachesEveryOtherPos() {
        join(1500, 300);
        joinSpread();

        List<Player> members = matcher.formLobby(NOW).orElseThrow().members();

        int lowest = members.stream().mapToInt(Player::rating).min().orElseThrow();
        int highest = members.stream().mapToInt(Player::rating).max().orElseThrow();

        for (Player member : members) {
            int radius = WideningFunction.ratingRadius(java.time.Duration.between(member.queuedAt(), NOW));
            assertTrue(member.rating() - radius <= lowest && member.rating() + radius >= highest,
                    "Player rated " + member.rating() + " accepts a radius of " + radius
                            + ", which must cover the whole lobby span " + lowest + " to " + highest);
        }
    }

    @Test void testACandidateWhoCannotReachBackIsSkippedButTheLobbyStillFormsNeg() {
        // The outsider sits inside the anchor's window, so the query returns
        // them and a one sided check would seat them. Their own radius reaches
        // nowhere near 1500. They have also waited longer than the nine, so the
        // merge offers them first and the rejection has to happen mid walk
        // rather than at the end.
        join(1500, 300);
        Player outsider = join(2300, 95);
        joinSpread();

        Lobby formed = matcher.formLobby(NOW).orElseThrow();

        assertEquals(MatchMaker.LOBBY_SIZE, formed.members().size(),
                "Rejecting a candidate does not cost a seat, the walk carries on");
        assertFalse(formed.members().contains(outsider),
                "The outsider accepts nobody in this lobby, so they cannot be seated in it");
        assertTrue(heap.contains(outsider.id()), "A skipped candidate is still queued");
    }

    // committing a lobby

    @Test void testMembersLeaveBothStructures() {
        joinCluster(1000, 10);

        matcher.formLobby(NOW);

        assertEquals(0, index.playerCount(), "A matched player is no longer queued by rating");
        assertEquals(0, index.ratingCount(), "The emptied bucket goes with them");
        assertEquals(0, heap.size(), "A matched player is no longer waiting");
    }

    @Test void testASecondCallCannotRematchTheSamePlayers() {
        joinCluster(1000, 10);
        matcher.formLobby(NOW);

        assertEquals(Optional.empty(), matcher.formLobby(NOW),
                "The ten were removed, so there is nobody left to form a second lobby");
    }

    // failing to fill

    @Test void testTooFewCandidatesFormsNothingNeg() {
        joinCluster(1000, 9);

        assertEquals(Optional.empty(), matcher.formLobby(NOW),
                "Nine willing players are still one short of a lobby");
    }

    @Test void testAFailedAttemptLeavesTheQueueIntact() {
        joinCluster(1000, 9);

        matcher.formLobby(NOW);

        assertEquals(9, index.playerCount(), "Nobody was matched, so nobody leaves the index");
        assertEquals(8, heap.size(), "Only the anchor left the heap, and they are on cooldown");
        assertEquals(1, matcher.coolingCount(), "The failed anchor is sitting out");
    }

    @Test void testConsentIsMutualNeg() {
        // The anchor has waited an hour, so their window spans most of the
        // domain and every candidate falls inside it. The candidates have just
        // queued, so their own windows are the base radius and reach nowhere
        // near the anchor. A one sided check would seat all ten.
        join(1000, 3600);
        joinCluster(1500, 14);

        assertEquals(Optional.empty(), matcher.formLobby(NOW),
                "A long waiter cannot conscript players who would not accept them back");
        assertEquals(1, matcher.coolingCount(), "The anchor failed, so the anchor cools");
    }

    // cooldown

    @Test void testAFailedAnchorDoesNotAnchorAgainImmediately() {
        List<Player> joined = joinCluster(1000, 9);

        matcher.formLobby(NOW);
        matcher.formLobby(NOW.plusSeconds(1));

        assertEquals(2, matcher.coolingCount(),
                "The second attempt anchored on somebody else, so two players are now cooling");
        assertEquals(7, heap.size(), "Both failed anchors are out of the heap");
        assertFalse(heap.contains(joined.get(0).id()), "The first anchor is still sitting out");
        assertFalse(heap.contains(joined.get(1).id()), "So is the second");
    }

    @Test void testACooledAnchorComesBackAndAnchorsAgain() {
        joinCluster(1000, 9);

        matcher.formLobby(NOW);
        matcher.formLobby(NOW.plus(MatchMaker.COOLDOWN));

        assertEquals(1, matcher.coolingCount(),
                "The original anchor was drained back, anchored again, failed again, and cools alone."
                        + " A second player cooling would mean the first never returned");
        assertEquals(8, heap.size(), "Exactly one player is out of the heap, the same one as before");
    }

    @Test void testACoolingPlayerCanStillBeRecruited() {
        // Cooldown bars a player from anchoring, not from being matched. The
        // tenth player arrives after the first attempt has failed, so the
        // second attempt anchors on somebody else and seats the cooling player.
        List<Player> joined = joinCluster(1000, 9);
        Player failedAnchor = joined.get(0);

        matcher.formLobby(NOW);
        assertEquals(1, matcher.coolingCount(), "Fixture check: the anchor is cooling");

        join(1000, 0);
        Lobby formed = matcher.formLobby(NOW.plusSeconds(1)).orElseThrow();

        assertTrue(formed.members().contains(failedAnchor),
                "A player on cooldown is still a candidate for somebody else's lobby");
        assertNotEquals(failedAnchor, formed.anchor(), "They are barred from anchoring, though");
        assertEquals(0, matcher.coolingCount(),
                "A recruited player must leave the cooldown queue, or they would be handed"
                        + " back to the heap after they had already been matched");
    }

    @Test void testAMatchedPlayerNeverReturnsToTheHeap() {
        List<Player> joined = joinCluster(1000, 9);
        Player failedAnchor = joined.get(0);

        matcher.formLobby(NOW);
        join(1000, 0);
        matcher.formLobby(NOW.plusSeconds(1));

        matcher.formLobby(NOW.plus(MatchMaker.COOLDOWN));

        assertFalse(heap.contains(failedAnchor.id()),
                "The cooldown has expired, but the player is in a lobby and must not be drained back");
        assertFalse(index.playersInRange(0, 5000).anyMatch(b -> b.contains(failedAnchor)),
                "Nor are they still queued by rating");
    }

    // counting what a pass did

    @Test void testASuccessfulLobbyCountsNothing() {
        joinCluster(1000, 10);

        matcher.formLobby(NOW);

        assertEquals(0, matcher.retryCount(), "Nothing was taken, so nothing was retried");
        assertEquals(0, matcher.starvationCount(), "A lobby formed, so no anchor was cooled");
        assertEquals(0, matcher.contentionCount(), "No budget was spent");
        assertEquals(0, matcher.abortCount(), "The anchor was never at risk");
    }

    @Test void testAnAnchorWhoCannotFillALobbyIsCountedAsStarvation() {
        joinCluster(1000, 9);

        matcher.formLobby(NOW);

        assertEquals(1, matcher.starvationCount(), "No lobby existed for this anchor");
        assertEquals(0, matcher.contentionCount(),
                "Nobody took a member, so the failure is not contention");
        assertEquals(0, matcher.retryCount(), "There was nothing to retry");
    }

    // clock skew

    @Test void testAQueueTimeInTheFutureIsTreatedAsNoWait() {
        // intake stamps the queue time on one machine, the engine reads it on
        // another. Skew puts a player who has just joined slightly ahead of now.
        joinCluster(1000, 9);
        join(1000, -30);

        assertTrue(matcher.formLobby(NOW).isPresent(),
                "A player from a slightly fast clock is matchable, not a crash");
    }

    @Test void testAnAnchorWithAQueueTimeInTheFutureStillAnchors() {
        for (int i = 0; i < 10; i++) {
            join(1000, -30 - i);
        }

        assertTrue(matcher.formLobby(NOW).isPresent(),
                "Every queue time is ahead of now, so the anchor is too, and it still matches");
    }

    // fairness

    @Test void testLongestWaitersAreSeatedFirst() {
        List<Player> joined = joinCluster(1000, 20);

        List<Player> members = matcher.formLobby(NOW).orElseThrow().members();

        assertEquals(joined.subList(0, 10), members,
                "The heap sets the anchor and the merge fills the seats, both by wait time,"
                        + " so a lobby is the ten who have waited longest");
    }

    @Test void testEveryCallShrinksTheHeap() {
        // The termination argument for a caller loop: a call either seats ten
        // or cools one, so the heap is strictly smaller either way and a caller
        // never has to track what it has already tried.
        joinCluster(1000, 25);

        int previous = heap.size();
        for (int attempt = 0; attempt < 5; attempt++) {
            matcher.formLobby(NOW);

            assertTrue(heap.size() < previous,
                    "Attempt " + attempt + " left the heap at " + heap.size() + ", was " + previous);
            previous = heap.size();
        }
    }
}
