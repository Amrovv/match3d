package com.match3d.intake;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface EntryRepository extends JpaRepository<EntryRow, UUID> {

    /** 1 if inserted, 0 if another join holds the id, as a solo's entry id is their own. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into entries (id, queued_at) values (:id, :queuedAt) on conflict (id) do nothing",
           nativeQuery = true)
    int insertIfAbsent(UUID id, Instant queuedAt);
}
