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

    /** Ten players, the first of them the one the matcher would have anchored on. */
    private static List<Player> tenPlayers() {
        List<Player> members = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            members.add(queuedAt(i));
        }
        return members;
    }

    // construction

    @Test void testNullMembersNeg() {
        assertThrows(NullPointerException.class, () -> new Lobby(null),
                "A lobby without members is not a lobby");
    }

    @Test void testMembersAreReadBackInOrder() {
        List<Player> members = tenPlayers();

        assertEquals(members, new Lobby(members).members(),
                "The order the matcher chose is the order the lobby keeps");
    }

    // immutability

    @Test void testMembersAreCopiedFromTheCaller() {
        List<Player> members = tenPlayers();
        Lobby lobby = new Lobby(members);
        members.clear();

        assertEquals(10, lobby.members().size(),
                "The lobby copies on construction, so the caller cannot empty it afterwards");
    }

    @Test void testMembersCannotBeModifiedNeg() {
        Lobby lobby = new Lobby(tenPlayers());

        assertThrows(UnsupportedOperationException.class, () -> lobby.members().add(queuedAt(99)),
                "A formed lobby is final, nobody joins it after the fact");
    }

    // anchor

    @Test void testAnchorIsTheFirstMember() {
        List<Player> members = tenPlayers();

        assertEquals(members.get(0), new Lobby(members).anchor(),
                "The anchor is held first, so the lobby knows whose wait set its window");
    }
}
