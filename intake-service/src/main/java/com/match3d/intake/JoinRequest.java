package com.match3d.intake;

import java.util.List;
import java.util.UUID;

/** Body of POST /queue/join. One id queues a solo, two to five a party. */
public record JoinRequest(List<UUID> memberIds) {
}
