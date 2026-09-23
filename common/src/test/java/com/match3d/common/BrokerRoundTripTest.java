package com.match3d.common;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One event through a real RabbitMQ and back. Needs the broker running on
 * localhost:5672, so it is excluded from the normal build.
 * Run it with ./gradlew :common:brokerTest
 */
@Tag("broker")
class BrokerRoundTripTest {

    @Test void testAnEventCrossesTheBrokerIntact() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // Server named, exclusive and auto deleted, so the test leaves nothing behind.
            String queue = channel.queueDeclare().getQueue();

            EntryQueued sent = new EntryQueued(UUID.randomUUID(), List.of(UUID.randomUUID()), Instant.now());
            channel.basicPublish("", queue, null, EventJson.toBytes(sent));

            GetResponse delivery = channel.basicGet(queue, true);

            assertNotNull(delivery, "The message published should be waiting in the queue");
            assertEquals(sent, EventJson.fromBytes(delivery.getBody(), EntryQueued.class),
                    "What matchmaking reads is exactly what intake wrote");
        }
    }
}
