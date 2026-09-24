package com.match3d.matchmaking;

/** Body of POST /matches/{id}/result. Winner "A" or "B", or absent for a coin toss. */
public record ResultRequest(String winner) {
}
