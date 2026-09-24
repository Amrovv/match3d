package com.match3d.intake;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<PlayerRow, UUID> {

    List<PlayerRow> findByEntryId(UUID entryId);

    List<PlayerRow> findByMatchId(UUID matchId);

    List<PlayerRow> findByEntryIdIsNotNull();

    /** Queues a player under the entry unless already queued, clearing any match or refusal. 1 if claimed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into players (id, entry_id) values (:id, :entryId)
            on conflict (id) do update set entry_id = :entryId, match_id = null, side = null, rejection = null
            where players.entry_id is null""", nativeQuery = true)
    int claim(UUID id, UUID entryId);

    /** Frees an entry's members to queue again. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update players set entry_id = null where entry_id = :entryId", nativeQuery = true)
    int release(UUID entryId);

    /** Moves an entry's members from queued to matched, in one statement so no row is ever both. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update players set entry_id = null, match_id = :matchId, side = :side where entry_id = :entryId",
           nativeQuery = true)
    int match(UUID entryId, UUID matchId, String side);

    /** Moves an entry's members from queued to refused. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update players set entry_id = null, rejection = :reason where entry_id = :entryId",
           nativeQuery = true)
    int refuse(UUID entryId, String reason);

    /** Frees a match's players. A player queued again since is no longer on it, so is untouched. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update players set match_id = null, side = null where match_id = :matchId", nativeQuery = true)
    int endMatch(UUID matchId);
}
