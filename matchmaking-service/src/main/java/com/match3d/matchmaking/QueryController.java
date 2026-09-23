package com.match3d.matchmaking;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Read only views over matches formed since this process started. */
@RestController
public class QueryController {

    private final MatchHistory history;

    public QueryController(MatchHistory history) {
        this.history = history;
    }

    /** 404 if no such match. */
    @GetMapping("/matches/{id}")
    public ResponseEntity<MatchRecord> match(@PathVariable UUID id) {
        return ResponseEntity.of(history.match(id));
    }

    /** Newest first, empty for a player never matched. */
    @GetMapping("/players/{id}/history")
    public List<MatchRecord> history(@PathVariable UUID id) {
        return history.historyOf(id);
    }
}
