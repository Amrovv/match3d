package com.match3d.matchmaking;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Player registration, standing in for an account system outside this project, and rating lookup. */
@RestController
public class PlayerController {

    private final RatingStore ratings;

    public PlayerController(RatingStore ratings) {
        this.ratings = ratings;
    }

    /** 201 at the starting rating, 409 if the id exists, 400 with no id. */
    @PostMapping("/players")
    public ResponseEntity<Void> create(@RequestBody CreatePlayerRequest request) {
        if (request.id() == null) return ResponseEntity.badRequest().build();
        HttpStatus status = ratings.create(request.id()) ? HttpStatus.CREATED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).build();
    }

    /** 404 if no such player. */
    @GetMapping("/players/{id}")
    public ResponseEntity<PlayerResponse> player(@PathVariable UUID id) {
        return ResponseEntity.of(ratings.ratingOf(id).map(rating -> new PlayerResponse(id, rating)));
    }
}
