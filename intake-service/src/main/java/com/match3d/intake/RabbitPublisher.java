package com.match3d.intake;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.match3d.common.EventJson;
import com.match3d.common.Queues;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;

/**
 * Publishes to the queue matchmaking reads. Durable queue and persistent
 * messages, so a broker restart does not lose what was accepted.
 *
 * A channel is not safe across threads and Javalin serves requests on
 * several, so publish is synchronised.
 */
public final class RabbitPublisher implements EventPublisher {

    private final Channel channel;

    public RabbitPublisher(Channel channel) throws IOException {
        this.channel = channel;
        channel.queueDeclare(Queues.TO_MATCHMAKING, true, false, false, null);
    }

    @Override
    public synchronized void publish(Object event) {
        AMQP.BasicProperties properties = MessageProperties.PERSISTENT_BASIC.builder()
                .type(event.getClass().getSimpleName())
                .build();
        try {
            channel.basicPublish("", Queues.TO_MATCHMAKING, properties, EventJson.toBytes(event));
        } catch (IOException e) {
            throw new UncheckedIOException("Broker did not take " + event, e);
        }
    }
}
