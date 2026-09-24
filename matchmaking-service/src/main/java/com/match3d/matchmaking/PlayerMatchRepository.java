package com.match3d.matchmaking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
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

    /** Per band of 100 by rating before: band, total seconds from queued to formed, players. Matches formed since. */
    @Query(value = """
            select pm.rating_before / 100, sum(extract(epoch from (m.formed_at - pm.queued_at))), count(*)
            from matches m join player_matches pm on pm.match_id = m.id
            where m.formed_at >= :since
            group by pm.rating_before / 100
            order by 1""", nativeQuery = true)
    List<Object[]> waitBands(Instant since);
}
