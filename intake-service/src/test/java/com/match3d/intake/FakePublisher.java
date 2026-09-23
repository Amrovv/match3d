package com.match3d.intake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Records what would have been published. Set down to act as a broker that is unreachable. */
final class FakePublisher implements EventPublisher {

    final List<Object> published = new ArrayList<>();
    boolean down = false;

    @Override
    public synchronized void publish(Object event) {
        if (down) throw new UncheckedIOException(new IOException("broker down"));
        published.add(event);
    }
}
