package com.match3d.intake;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

public interface EntryRepository extends JpaRepository<EntryRow, UUID> {

    /** 1 if inserted, 0 if another join holds the id, as a solo's entry id is their own. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into entries (id, queued_at, sent_at) values (:id, :queuedAt, :queuedAt)
            on conflict (id) do nothing""", nativeQuery = true)
    int insertIfAbsent(UUID id, Instant queuedAt);

    /** Matchmaking holds the entry. 0 if intake no longer does, such as after a leave. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update entries set accepted_at = :at, rating = :rating where id = :id", nativeQuery = true)
    int accept(UUID id, int rating, Instant at);

    /** Sent again, so unconfirmed until matchmaking accepts it anew. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update entries set sent_at = :at, accepted_at = null where id in (:ids)", nativeQuery = true)
    int markSent(Collection<UUID> ids, Instant at);
}
