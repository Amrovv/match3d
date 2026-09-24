package com.match3d.intake;

import com.match3d.common.EntryMatched;
import com.match3d.common.EntryRejected;
import com.match3d.common.EventJson;
import com.match3d.common.MatchEnded;
import com.match3d.common.Queues;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


/**
 * Consumes what matchmaking sends back. Each message names its event in the
 * type property, which decides the record its bytes are read as.
 *
 * Runs on a listener thread, alongside request threads using the same registry.
 */
@Component
public class ResultListener {

    private final QueueRegistry registry;
    private final MatchBoard board;
    private final RejectionBoard rejections;

    public ResultListener(QueueRegistry registry, MatchBoard board, RejectionBoard rejections) {
        this.registry = registry;
        this.board = board;
        this.rejections = rejections;
    }

    @RabbitListener(queues = Queues.TO_INTAKE)
    public void onMessage(Message message) {
        String type = message.getMessageProperties().getType();
        byte[] body = message.getBody();
        switch (type) {
            case "EntryMatched" -> onMatched(EventJson.fromBytes(body, EntryMatched.class));
            case "EntryRejected" -> onRejected(EventJson.fromBytes(body, EntryRejected.class));
            case "MatchEnded" -> board.end(EventJson.fromBytes(body, MatchEnded.class).matchId());
            default -> throw new IllegalArgumentException("Unknown event type " + type);
        }
    }

    /** Records the lobby by player, then frees its entries to queue again. */
    void onMatched(EntryMatched matched) {
        List<UUID> teamAPlayers = collectPlayers(matched.teamA());
        List<UUID> teamBPlayers = collectPlayers(matched.teamB());

        board.record(new MatchView(matched.matchId(), teamAPlayers, teamBPlayers));

        for (UUID entryId : matched.teamA()) {
            registry.remove(entryId);
        }

        for (UUID entryId : matched.teamB()) {
            registry.remove(entryId);
        }
    }

    /** Keeps the reason against the members, then forgets an entry that was never queued. */
    void onRejected(EntryRejected rejected) {
        // An expression, so a new reason fails to compile until it is handled here.
        boolean refused = switch (rejected.reason()) {
            case SPREAD_TOO_WIDE, UNKNOWN_PLAYER -> true;
            case DUPLICATE -> false; // redelivery of an entry already queued
        };
        if (!refused) return;

        List<UUID> players = collectPlayers(List.of(rejected.entryId()));
        rejections.record(players, rejected.reason());
        registry.remove(rejected.entryId());
    }

    /** The players inside these entries. Entries intake no longer holds are skipped. */
    private List<UUID> collectPlayers(List<UUID> entryIds) {
        List<UUID> players = new ArrayList<>();
        for (UUID entryId : entryIds) {
            List<UUID> members = registry.membersOf(entryId);
            if (members != null) players.addAll(members);
        }
        return players;
    }
}
