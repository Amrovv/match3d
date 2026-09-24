package com.match3d.matchmaking;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.match3d.common.MatchEnded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Match results, standing in for a game server reporting who won. */
@RestController
public class ResultController {

    private static final Logger log = LoggerFactory.getLogger(ResultController.class);

    private final MatchResults results;
    private final EventPublisher publisher;

    public ResultController(MatchResults results, EventPublisher publisher) {
        this.results = results;
        this.publisher = publisher;
    }

    /** 200 with the winner, 400 for a winner other than A or B, 404 unknown match, 409 already ended. */
    @PostMapping("/matches/{id}/result")
    public ResponseEntity<?> result(@PathVariable UUID id, @RequestBody(required = false) ResultRequest request) {
        String given = request == null ? null : request.winner();
        if (given != null && !given.equals("A") && !given.equals("B")) {
            return ResponseEntity.badRequest().body("winner must be A or B");
        }
        char winner = given != null ? given.charAt(0) : (ThreadLocalRandom.current().nextBoolean() ? 'A' : 'B');

        return switch (results.end(id, winner)) {
            case ENDED -> {
                announce(id);
                yield ResponseEntity.ok(new ResultResponse(id, String.valueOf(winner)));
            }
            case ALREADY_ENDED -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case UNKNOWN_MATCH -> ResponseEntity.notFound().build();
        };
    }

    /** After the commit. A failure leaves ratings moved and intake showing the match until the players queue again. */
    private void announce(UUID matchId) {
        try {
            publisher.publish(new MatchEnded(matchId));
        } catch (RuntimeException e) {
            log.warn("Match {} ended but intake was not told", matchId, e);
        }
    }
}
