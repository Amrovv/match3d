package com.match3d.intake;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.match3d.common.EventJson;
import com.match3d.common.Queues;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes to the queue matchmaking reads, as persistent messages naming
 * their event in the type property. RabbitTemplate is safe across threads.
 */
@Component
public final class RabbitPublisher implements EventPublisher {

    private final RabbitTemplate rabbit;

    public RabbitPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publish(Object event) {
        MessageProperties properties = new MessageProperties();
        properties.setType(event.getClass().getSimpleName());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        try {
            rabbit.send("", Queues.TO_MATCHMAKING, new Message(EventJson.toBytes(event), properties));
        } catch (AmqpException e) {
            throw new UncheckedIOException(new IOException("Broker did not take " + event, e));
        }
    }
}
