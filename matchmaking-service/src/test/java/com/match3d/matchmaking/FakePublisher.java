package com.match3d.matchmaking;

import java.util.ArrayList;
import java.util.List;

/**
 * Records what would have been published. Set down to act as a broker that is
 * unreachable, or failNext to fail only that many publishes.
 */
final class FakePublisher implements EventPublisher {

    final List<Object> published = new ArrayList<>();
    boolean down = false;
    int failNext = 0;

    @Override
    public synchronized void publish(Object event) {
        if (down) throw new IllegalStateException("broker down");
        if (failNext > 0) {
            failNext--;
            throw new IllegalStateException("broker blipped");
        }
        published.add(event);
    }
}
