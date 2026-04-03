package com.example.messaging.messaging;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {
    public static final String EVENTS_EXCHANGE = "events.x";
    public static final String EVENTS_ROUTING_KEY = "events.msg";
    public static final String EVENTS_QUEUE = "events.q";

    public static final String DLX_EXCHANGE = "events.dlx";
    public static final String DLQ_ROUTING_KEY = "events.msg.dlq";
    public static final String DLQ_QUEUE = "events.dlq";

    @Bean
    public Declarables rabbitDeclarables() {
        DirectExchange eventsExchange = new DirectExchange(EVENTS_EXCHANGE, true, false);
        DirectExchange dlxExchange = new DirectExchange(DLX_EXCHANGE, true, false);

        Queue mainQueue = new Queue(
                EVENTS_QUEUE,
                true,
                false,
                false,
                Map.of(
                        "x-dead-letter-exchange", DLX_EXCHANGE,
                        "x-dead-letter-routing-key", DLQ_ROUTING_KEY
                )
        );

        Queue dlqQueue = new Queue(DLQ_QUEUE, true);

        Binding mainBinding = BindingBuilder.bind(mainQueue).to(eventsExchange).with(EVENTS_ROUTING_KEY);
        Binding dlqBinding = BindingBuilder.bind(dlqQueue).to(dlxExchange).with(DLQ_ROUTING_KEY);

        return new Declarables(eventsExchange, dlxExchange, mainQueue, dlqQueue, mainBinding, dlqBinding);
    }
}

