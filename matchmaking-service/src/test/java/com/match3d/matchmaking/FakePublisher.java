package com.match3d.matchmaking;

import java.util.ArrayList;
import java.util.List;

/** Records what would have been published. */
final class FakePublisher implements EventPublisher {

    final List<Object> published = new ArrayList<>();

    @Override
    public synchronized void publish(Object event) {
        published.add(event);
    }
}
