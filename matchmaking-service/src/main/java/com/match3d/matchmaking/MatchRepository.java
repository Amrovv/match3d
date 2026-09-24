package com.match3d.matchmaking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MatchRepository extends JpaRepository<MatchRow, UUID> {

    List<MatchRow> findByIdInOrderByFormedAtDesc(Collection<UUID> ids);

    /** Sets the result only if there is none yet. 1 if set, 0 if unknown or already ended. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update matches set winner = :winner, ended_at = :endedAt where id = :id and winner is null",
           nativeQuery = true)
    int endIfOpen(UUID id, String winner, Instant endedAt);
}
