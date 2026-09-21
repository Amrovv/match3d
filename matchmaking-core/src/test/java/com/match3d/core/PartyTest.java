package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PartyTest {

    private static final Instant BASE = Instant.parse("2026-09-07T00:00:00Z");

    /** A player at that rating, queued at the base instant. */
    private static Player rated(int rating) {
        return new Player(UUID.randomUUID(), rating, BASE);
    }

    /** Members at the given ratings, in the order listed. */
    private static List<Player> members(int... ratings) {
        List<Player> members = new ArrayList<>();
        for (int rating : ratings) {
            members.add(rated(rating));
        }
        return members;
    }

    /** A party over those ratings, built the sanctioned way. */
    private static Party party(int... ratings) {
        return Party.of(members(ratings), BASE);
    }

    // construction

    @Test void testNullIdNeg() {
        List<Player> members = members(1500, 1500);

        assertThrows(NullPointerException.class,
                () -> new Party(null, members, BASE, 1500),
                "A party without an id has no identity at all");
    }

    @Test void testNullQueuedAtNeg() {
        List<Player> members = members(1500, 1500);

        assertThrows(NullPointerException.class,
                () -> new Party(UUID.randomUUID(), members, null, 1500),
                "Wait time drives fairness, so a missing queue time must fail loudly");
    }

    @Test void testNullMembersNeg() {
        assertThrows(NullPointerException.class,
                () -> new Party(UUID.randomUUID(), null, BASE, 1500),
                "A party without members is not a party");
    }

    @Test void testComponentsAreReadBack() {
        Party party = party(1450, 1550);

        assertEquals(BASE, party.queuedAt(), "The queue time is stored as given");
        assertEquals(2, party.members().size(), "Both members are kept");
    }

    // size

    @Test void testOneMemberNeg() {
        List<Player> members = members(1500);

        assertThrows(IllegalArgumentException.class,
                () -> new Party(UUID.randomUUID(), members, BASE, 1500),
                "One player queues as themselves, so a party of one is not a thing");
    }

    @Test void testRatingAnEmptyPartyNeg() {
        List<Player> members = members();

        assertThrows(IllegalArgumentException.class,
                () -> Party.of(members, BASE),
                "of rates before it builds, so an empty list must fail the same way there");
    }

    @Test void testEmptyMembersNeg() {
        List<Player> members = members();

        assertThrows(IllegalArgumentException.class,
                () -> new Party(UUID.randomUUID(), members, BASE, 1500),
                "A party with nobody in it cannot be rated or seated");
    }

    @Test void testSixMembersNeg() {
        List<Player> members = members(1500, 1500, 1500, 1500, 1500, 1500);

        assertThrows(IllegalArgumentException.class,
                () -> new Party(UUID.randomUUID(), members, BASE, 1500),
                "Teams are five a side, so six players could never sit together");
    }

    @Test void testFiveMembersPos() {
        assertEquals(5, party(1500, 1500, 1500, 1500, 1500).size(),
                "Five fills one team exactly, which is the largest party that can");
    }

    @Test void testSizeIsTheMemberCount() {
        assertEquals(3, party(1400, 1500, 1600).size(),
                "Size is the seats the party takes, which is how many people it holds");
    }

    // spread

    @Test void testSpreadExactlyAtTheCapPos() {
        assertEquals(2, party(1000, 3500).size(),
                "The cap is inclusive, so a pair exactly MAX_SPREAD apart may party");
    }

    @Test void testSpreadOneOverTheCapNeg() {
        List<Player> members = members(1000, 3501);

        assertThrows(IllegalArgumentException.class,
                () -> Party.of(members, BASE),
                "Beyond the cap the two could never accept each other in a lobby");
    }

    @Test void testSpreadIsMeasuredAcrossTheExtremesNeg() {
        // Every adjacent pair is inside the cap. Only the outermost two are not,
        // so a check comparing neighbours rather than extremes would pass this.
        List<Player> members = members(1000, 2200, 3600);

        assertThrows(IllegalArgumentException.class,
                () -> Party.of(members, BASE),
                "The widest pair decides, not the widest gap between neighbours");
    }

    // the derived rating

    @Test void testRatingOfATightParty() {
        assertEquals(1525, party(1450, 1500, 1550).rating(),
                "Friends of similar rating are barely shifted at all");
    }

    @Test void testRatingOfAWidePair() {
        assertEquals(2100, party(1200, 2400).rating(),
                "The mean is pulled halfway to the stronger of the two");
    }

    @Test void testRatingOfAClusterCarryingOneHighPlayer() {
        assertEquals(2008, party(1200, 1250, 2400).rating(),
                "Two low players and one high one still land well above their own mean");
    }

    @Test void testRatingOfFourLowPlayersAndOneHigh() {
        assertEquals(2500, party(1000, 1000, 1000, 1000, 3500).rating(),
                "A strong player dragging four friends does not buy them an easy lobby");
    }

    @Test void testRatingOfIdenticalMembers() {
        assertEquals(1500, party(1500, 1500, 1500).rating(),
                "With nobody stronger there is nothing to shift toward");
    }

    @Test void testRatingIsNeverBelowTheMean() {
        assertTrue(party(1000, 1000, 3000).rating() >= 1666,
                "The shift only ever pulls upward, so the rating cannot sit under the mean");
    }

    @Test void testRatingIsNeverAboveTheStrongest() {
        assertTrue(party(1000, 1000, 3000).rating() <= 3000,
                "A shift of one would reach the strongest member, and it is a half");
    }

    @Test void testRatingIsFlooredRatherThanRounded() {
        // The usual cases floor and round alike. Here the mean is 1401.5 and the
        // shifted value 1401.75, so rounding would give 1402 and flooring 1401.
        assertEquals(1401, party(1401, 1402).rating(),
                "Flooring matches the widening curve, so one convention covers both");
    }

    // the stored rating cannot contradict the members

    @Test void testRatingDisagreeingWithTheMembersNeg() {
        List<Player> members = members(1200, 2400);

        assertThrows(IllegalArgumentException.class,
                () -> new Party(UUID.randomUUID(), members, BASE, 1500),
                "A stored rating contradicting the members would fail silently everywhere else");
    }

    @Test void testTheCanonicalConstructorAcceptsTheDerivedRatingPos() {
        List<Player> members = members(1200, 2400);

        assertDoesNotThrow(() -> new Party(UUID.randomUUID(), members, BASE, 2100),
                "The check guards against drift, it is not a bar on building one directly");
    }

    // immutability

    @Test void testMembersAreCopiedFromTheCaller() {
        List<Player> members = members(1400, 1500, 1600);
        Party party = Party.of(members, BASE);
        members.clear();

        assertEquals(3, party.size(),
                "The party copies on construction, so the caller cannot empty it afterwards");
    }

    @Test void testMembersCannotBeModifiedNeg() {
        Party party = party(1400, 1600);

        assertThrows(UnsupportedOperationException.class,
                () -> party.members().add(rated(1500)),
                "A queued party is fixed, nobody joins it after the fact");
    }

    // identity

    @Test void testEqualsIgnoresEverythingButTheIdPos() {
        UUID id = UUID.randomUUID();
        Party queued = new Party(id, members(1200, 2400), BASE, 2100);
        Party rebuilt = new Party(id, members(1450, 1550), BASE.plusSeconds(600), 1525);

        assertEquals(queued, rebuilt,
                "Identity is the id alone, so a party rebuilt from a message equals the one queued");
    }

    @Test void testEqualsDifferentIdNeg() {
        List<Player> same = members(1400, 1600);

        assertNotEquals(Party.of(same, BASE), Party.of(same, BASE),
                "Two parties over the same people are still two parties");
    }

    @Test void testEqualsOtherTypeNeg() {
        Party party = party(1400, 1600);

        assertNotEquals(party, new Player(party.id(), 1500, BASE),
                "A party sharing an id with a player is still not that player");
    }

    @Test void testHashCodeAgreesWithEquals() {
        UUID id = UUID.randomUUID();
        Party queued = new Party(id, members(1200, 2400), BASE, 2100);
        Party rebuilt = new Party(id, members(1450, 1550), BASE.plusSeconds(600), 1525);

        assertEquals(queued.hashCode(), rebuilt.hashCode(),
                "Equal parties must hash alike, or a hash based collection will not find them");
    }

    @Test void testRebuildingWithTheSameMembersGivesANewId() {
        List<Player> members = members(1400, 1600);
        Party queued = Party.of(members, BASE);
        Party rebuilt = Party.of(members, BASE);

        assertNotEquals(queued.id(), rebuilt.id(),
                "A party rebuilt after a membership change is not the party that was queued");
    }

    // the QueueEntry contract

    @Test void testAPartyOrdersByItsSharedQueueTime() {
        Party earlier = Party.of(members(1500, 1500), BASE);
        Party later = Party.of(members(1500, 1500), BASE.plusSeconds(60));

        assertTrue(QueueEntry.BY_WAIT_TIME.compare(earlier, later) < 0,
                "The heap must offer the party that has waited longer first");
    }

    @Test void testAPartyAndAPlayerOrderAgainstEachOther() {
        QueueEntry party = Party.of(members(1500, 1500), BASE);
        QueueEntry player = new Player(UUID.randomUUID(), 1500, BASE.plusSeconds(60));

        assertTrue(QueueEntry.BY_WAIT_TIME.compare(party, player) < 0,
                "One queue holds both kinds, so they must order against each other");
    }
}
