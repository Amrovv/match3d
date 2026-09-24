package com.match3d.intake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EntryRepository extends JpaRepository<EntryRow, UUID> {
}
