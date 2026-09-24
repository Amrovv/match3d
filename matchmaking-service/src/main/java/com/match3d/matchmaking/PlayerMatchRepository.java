package com.match3d.matchmaking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlayerMatchRepository extends JpaRepository<PlayerMatchRow, PlayerMatchRow.Key> {

    List<PlayerMatchRow> findByKeyMatchIdIn(Collection<UUID> matchIds);

    @Query("select pm.key.matchId from PlayerMatchRow pm where pm.key.playerId = :playerId")
    List<UUID> matchIdsOf(UUID playerId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "delete from player_matches where match_id = :matchId", nativeQuery = true)
    int deleteByMatchId(UUID matchId);
}
