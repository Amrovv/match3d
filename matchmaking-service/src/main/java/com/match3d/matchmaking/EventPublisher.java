package com.match3d.matchmaking;

/** Sends an event to intake. Faked in tests. */
public interface EventPublisher {

    void publish(Object event);
}
