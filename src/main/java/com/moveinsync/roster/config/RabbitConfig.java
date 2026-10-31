package com.moveinsync.roster.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

@Configuration
public class RabbitConfig {
    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connection) {
        var factory=new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory,connection);
        factory.setPrefetchCount(10);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(5).backOffOptions(500,2,10000)
                .recoverer((message,cause)->{ throw new AmqpRejectAndDontRequeueException("BOOKING_RETRY_EXHAUSTED"); }).build());
        // Default handlers may render a Message (including its body); never delegate payload logging to them.
        factory.setErrorHandler(error->{ throw new AmqpRejectAndDontRequeueException("BOOKING_CONSUMPTION_FAILED"); });
        return factory;
    }
    @Bean Declarables topology() {
        var changes=new DirectExchange("roster.changes",true,false);
        var dead=new DirectExchange("roster.dead",true,false);
        var queue=QueueBuilder.durable("roster.booking").withArgument("x-dead-letter-exchange","roster.dead")
                .withArgument("x-dead-letter-routing-key","booking.failed").build();
        var deadQueue=QueueBuilder.durable("roster.booking.dead").build();
        return new Declarables(changes,dead,queue,deadQueue,
                BindingBuilder.bind(queue).to(changes).with("employee.changed"),
                BindingBuilder.bind(deadQueue).to(dead).with("booking.failed"));
    }
}
