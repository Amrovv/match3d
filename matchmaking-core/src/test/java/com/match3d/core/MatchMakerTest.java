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
     * A party of the given size at one rating, queued waitedFor seconds before
     * NOW, joined to both structures as the single entry it is.
     */
    private Party joinParty(int rating, int size, int waitedFor) {
        List<Player> friends = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            friends.add(new Player(UUID.randomUUID(), rating, NOW.minusSeconds(waitedFor)));
        }
        Party party = Party.of(friends, NOW.minusSeconds(waitedFor));
        index.insert(party);
        heap.insert(party);
        return party;
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
        assertEquals(5, index.entryCount(), "One attempt seats ten, it does not drain the queue");
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

        assertEquals(0, index.entryCount(), "A matched player is no longer queued by rating");
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

        assertEquals(9, index.entryCount(), "Nobody was matched, so nobody leaves the index");
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

    // resuming a selection

    @Test void testSelectionFillsALobbyPos() {
        List<Player> queued = joinCluster(1000, 12);

        MatchMaker.Selection selection = matcher.new Selection(queued.get(0), NOW);
        selection.fill();

        assertEquals(MatchMaker.LOBBY_SIZE, selection.members().size(), "A full lobby was seated");
        assertEquals(queued.get(0), selection.members().get(0), "The anchor is seated first");
    }

    @Test void testSelectionResumesWhereItStoppedAfterADrop() {
        // Twelve queued, ten seated, two dropped. The replacements have to come
        // from the two the cursor had not reached, which is only possible if the
        // walk resumes rather than starting the window again.
        List<Player> queued = joinCluster(1000, 12);

        MatchMaker.Selection selection = matcher.new Selection(queued.get(0), NOW);
        selection.fill();
        List<QueueEntry> dropped = List.copyOf(selection.members().subList(8, 10));
        selection.drop(dropped);
        selection.fill();

        assertEquals(MatchMaker.LOBBY_SIZE, selection.members().size(), "The seats were refilled");
        assertTrue(selection.members().containsAll(queued.subList(10, 12)),
                "The replacements are the two the cursor had not yet reached");
        dropped.forEach(gone -> assertFalse(selection.members().contains(gone),
                "A dropped member is not seated again"));
    }

    @Test void testSelectionMembersIsASnapshotNotALiveView() {
        // formLobby re-reads members() every round for this reason. The two
        // sides are the selection's state; members() is a reading of them, so
        // a caller holding one across a drop is looking at a lobby that has
        // since changed. Nothing throws when that happens, it just verifies
        // the wrong entries forever.
        List<Player> queued = joinCluster(1000, 12);

        MatchMaker.Selection selection = matcher.new Selection(queued.get(0), NOW);
        selection.fill();
        List<QueueEntry> captured = selection.members();
        selection.drop(List.copyOf(captured.subList(8, 10)));

        assertEquals(MatchMaker.LOBBY_SIZE, captured.size(),
                "The captured list is a snapshot, so it still names the dropped two");
        assertEquals(8, selection.members().size(),
                "The selection itself lost them, which is what a caller must re-read to see");
    }

    @Test void testSelectionCannotFillWithoutCandidatesNeg() {
        List<Player> queued = joinCluster(1000, 9);

        MatchMaker.Selection selection = matcher.new Selection(queued.get(0), NOW);
        selection.fill();

        assertEquals(9, selection.members().size(), "Nine willing players seat nine");
    }

    @Test void testSelectionCannotReconsiderARejectedCandidate() {
        // The outsider is rejected while the nine are seated, because they
        // reach nowhere near this cluster. Dropping members widens the reach,
        // but the cursor has already walked past them, so a resumed fill cannot
        // take them. This is the cost of resuming rather than re-seeding.
        Player anchor = join(1500, 3600);
        Player outsider = join(2300, 95);
        List<Player> spread = joinSpread();

        MatchMaker.Selection selection = matcher.new Selection(anchor, NOW);
        selection.fill();
        assertFalse(selection.members().contains(outsider), "The outsider never consented");

        selection.drop(List.copyOf(selection.members().subList(1, 10)));
        selection.fill();

        assertFalse(selection.members().contains(outsider),
                "A resumed walk cannot go back for a candidate it has already passed");
        assertTrue(spread.size() > 0, "The spread is what filled the lobby first time round");
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
        assertFalse(index.entriesInRange(0, 5000).anyMatch(b -> b.contains(failedAnchor)),
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
        assertEquals(0, matcher.strandedCount(), "Nobody was turned away for room");
    }

    @Test void testAPartyTurnedAwayForRoomIsCountedAsStranded() {
        // Anchor and a four stack fill team A, a four stack leaves team B one
        // short, and the last four stack consents but fits neither side.
        join(1000, 100);
        joinParty(1000, 4, 90);
        joinParty(1000, 4, 80);
        joinParty(1000, 4, 70);

        assertEquals(Optional.empty(), matcher.formLobby(NOW), "Nine seated, one seat nobody fits");
        assertEquals(1, matcher.strandedCount(), "A party that would have played was turned away");
        assertEquals(0, matcher.starvationCount(), "Stranded and starved are counted apart");
    }

    @Test void testAPartyTurnedAwayThatWouldNotConsentIsStarvation() {
        // The last four stack is inside the anchor's window, but its own
        // radius after one second does not reach 1000, so room was never
        // what kept it out.
        join(1000, 300);
        joinParty(1000, 4, 90);
        joinParty(1000, 4, 80);
        joinParty(1800, 4, 1);

        assertEquals(Optional.empty(), matcher.formLobby(NOW), "No lobby existed for this anchor");
        assertEquals(0, matcher.strandedCount(), "Only a candidate who would consent counts as stranded");
        assertEquals(1, matcher.starvationCount(), "The failure is an empty window, not a full side");
    }

    // teams

    @Test void testALobbyIsFiveASide() {
        joinCluster(1000, 10);

        Lobby lobby = matcher.formLobby(NOW).orElseThrow();

        assertEquals(MatchMaker.TEAM_SIZE, lobby.teamA().size(), "Team A holds five");
        assertEquals(MatchMaker.TEAM_SIZE, lobby.teamB().size(), "Team B holds five");
    }

    @Test void testTheAnchorLeadsTeamAPos() {
        List<Player> joined = joinCluster(1000, 10);

        assertEquals(joined.get(0), matcher.formLobby(NOW).orElseThrow().teamA().get(0),
                "The anchor is seated first, and the first side is filled first");
    }

    @Test void testAPartyLandsWhollyOnOneSidePos() {
        // The party waits longest, so it anchors and takes three of team A.
        Party party = joinParty(1000, 3, 100);
        joinCluster(1000, 7);

        Lobby lobby = matcher.formLobby(NOW).orElseThrow();
        long onA = party.members().stream().filter(lobby.teamA()::contains).count();

        assertEquals(3, onA, "Friends who queued together play together, never split across sides");
    }

    @Test void testAPartySeatsEveryoneInIt() {
        Party party = joinParty(1000, 4, 100);
        joinCluster(1000, 6);

        Lobby lobby = matcher.formLobby(NOW).orElseThrow();

        assertTrue(lobby.members().containsAll(party.members()),
                "A party is seated whole or not at all, so all four are in the lobby");
    }

    @Test void testThreeThreesAndASoloCannotFormALobbyNeg() {
        // Ten seats and every consent check passes, but no subset of these
        // adds up to a side, so counting to ten rather than to five and five
        // would form a lobby that cannot be played.
        joinParty(1000, 3, 100);
        joinParty(1000, 3, 90);
        joinParty(1000, 3, 80);
        join(1000, 70);

        assertEquals(Optional.empty(), matcher.formLobby(NOW),
                "A lobby that cannot be split into two sides must never form");
    }

    @Test void testAPartyTooLargeForEitherSideIsPassedOverNeg() {
        // The anchoring party takes three of team A, the next three of team B.
        // Both sides then have two free, so the third party of three fits
        // neither and the solos have to finish the lobby.
        joinParty(1000, 3, 100);
        joinParty(1000, 3, 90);
        Party passedOver = joinParty(1000, 3, 80);
        for (int i = 0; i < 4; i++) {
            join(1000, 70 - i);
        }

        Lobby lobby = matcher.formLobby(NOW).orElseThrow();

        assertEquals(MatchMaker.TEAM_SIZE, lobby.teamA().size(), "Team A is still five");
        assertEquals(MatchMaker.TEAM_SIZE, lobby.teamB().size(), "Team B is still five");
        assertTrue(passedOver.members().stream().noneMatch(lobby.members()::contains),
                "A party that fits neither side is passed over rather than split");
    }

    @Test void testAPartyCountsItsMembersAgainstTheLobbyPos() {
        // Two parties of five fill the lobby on their own.
        joinParty(1000, 5, 100);
        joinParty(1000, 5, 90);

        Lobby lobby = matcher.formLobby(NOW).orElseThrow();

        assertEquals(MatchMaker.LOBBY_SIZE, lobby.members().size(),
                "Two parties of five are a full lobby, one on each side");
    }

    @Test void testAPartyLeavesBothStructuresOnCommit() {
        Party party = joinParty(1000, 4, 100);
        joinCluster(1000, 6);

        matcher.formLobby(NOW).orElseThrow();

        assertFalse(index.contains(party.id()), "A matched party leaves the index as one entry");
        assertFalse(heap.contains(party.id()), "A matched party leaves the heap as one entry");
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
