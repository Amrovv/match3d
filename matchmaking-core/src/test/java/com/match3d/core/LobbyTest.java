package com.match3d.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LobbyTest {

    private static final Instant BASE = Instant.parse("2026-09-07T00:00:00Z");

    /** A player queued waitedFor seconds after the base instant. */
    private static Player queuedAt(int waitedFor) {
        return new Player(UUID.randomUUID(), 1000, BASE.plusSeconds(waitedFor));
    }

    /** Five players, numbered from the given offset so the sides differ. */
    private static List<Player> side(int from) {
        List<Player> members = new ArrayList<>();
        for (int i = from; i < from + 5; i++) {
            members.add(queuedAt(i));
        }
        return members;
    }

    /** A full lobby, the first side holding the player the matcher anchored on. */
    private static Lobby lobby() {
        return new Lobby(side(0), side(5));
    }

    // construction

    @Test void testNullTeamANeg() {
        assertThrows(NullPointerException.class, () -> new Lobby(null, side(5)),
                "A lobby missing a side is not a lobby");
    }

    @Test void testNullTeamBNeg() {
        assertThrows(NullPointerException.class, () -> new Lobby(side(0), null),
                "A lobby missing a side is not a lobby");
    }

    @Test void testSidesAreReadBackInOrder() {
        List<Player> teamA = side(0);
        List<Player> teamB = side(5);
        Lobby lobby = new Lobby(teamA, teamB);

        assertEquals(teamA, lobby.teamA(), "The order the matcher chose is the order team A keeps");
        assertEquals(teamB, lobby.teamB(), "The order the matcher chose is the order team B keeps");
    }

    // members

    @Test void testMembersAreBothSidesTeamAFirst() {
        List<Player> teamA = side(0);
        List<Player> teamB = side(5);
        List<Player> expected = new ArrayList<>(teamA);
        expected.addAll(teamB);

        assertEquals(expected, new Lobby(teamA, teamB).members(),
                "Members is both sides, team A first, so the anchor leads");
    }

    @Test void testMembersHoldsEveryone() {
        assertEquals(10, lobby().members().size(),
                "Five a side is ten players");
    }

    // immutability

    @Test void testSidesAreCopiedFromTheCaller() {
        List<Player> teamA = side(0);
        Lobby lobby = new Lobby(teamA, side(5));
        teamA.clear();

        assertEquals(5, lobby.teamA().size(),
                "The lobby copies on construction, so the caller cannot empty a side afterwards");
    }

    @Test void testASideCannotBeModifiedNeg() {
        Lobby lobby = lobby();

        assertThrows(UnsupportedOperationException.class, () -> lobby.teamA().add(queuedAt(99)),
                "A formed lobby is final, nobody joins it after the fact");
    }

    @Test void testMembersCannotBeModifiedNeg() {
        Lobby lobby = lobby();

        assertThrows(UnsupportedOperationException.class, () -> lobby.members().add(queuedAt(99)),
                "The combined view is as final as the sides it reads");
    }

    // anchor

    @Test void testAnchorIsTheFirstPlayerOnTeamA() {
        List<Player> teamA = side(0);

        assertEquals(teamA.get(0), new Lobby(teamA, side(5)).anchor(),
                "The anchor is placed first on team A, so the lobby knows whose wait set its window");
    }
}
