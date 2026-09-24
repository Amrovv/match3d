package com.match3d.common;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Matchmaking to intake, every ten seconds. Its arrival is what intake counts
 * as alive; sentAt is for logs only, since the two clocks may disagree. Carries
 * the last hour's waits by rating band, empty bands left out.
 */
public record MatchmakingAlive(Instant sentAt, List<WaitBand> bands) {

    public MatchmakingAlive {
        Objects.requireNonNull(sentAt, "sentAt");
        bands = List.copyOf(bands);
    }
}
