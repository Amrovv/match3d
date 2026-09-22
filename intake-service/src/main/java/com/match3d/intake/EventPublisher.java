package com.match3d.intake;

/** Sends one event to matchmaking. */
public interface EventPublisher {

    /** Throws UncheckedIOException if the broker did not take the event. */
    void publish(Object event);
}
