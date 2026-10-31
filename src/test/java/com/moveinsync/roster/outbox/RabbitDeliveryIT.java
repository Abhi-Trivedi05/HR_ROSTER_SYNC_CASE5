package com.moveinsync.roster.outbox;

import com.moveinsync.roster.IntegrationSupport;
import com.moveinsync.roster.ingestion.HrEvent;
import com.moveinsync.roster.booking.BookingCancellationService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties="spring.rabbitmq.dynamic=true")
class RabbitDeliveryIT extends IntegrationSupport {
    @Container static final RabbitMQContainer RABBIT=new RabbitMQContainer("rabbitmq:4-management-alpine");
    @DynamicPropertySource static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.dynamic",()->true);
        registry.add("spring.rabbitmq.host",RABBIT::getHost);registry.add("spring.rabbitmq.port",RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username",RABBIT::getAdminUsername);registry.add("spring.rabbitmq.password",RABBIT::getAdminPassword);
    }
    @Autowired RabbitChangePublisher publisher;
    @Autowired OutboxRepository outbox;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired BookingCancellationService consumer;
    @Test void confirmedPersistentPublicationAndDuplicateDeliveryAreSafe() throws Exception {
        ((org.springframework.amqp.rabbit.core.RabbitAdmin)admin).initialize();submit(event("E",1,HrEvent.Type.CREATE));
        var claim=outbox.claim(tenant,1,60).getFirst();publisher.publish(claim.message());publisher.publish(claim.message());
        Message first=rabbit.receive("roster.booking",10000),second=rabbit.receive("roster.booking",10000);
        assertThat(first).isNotNull();assertThat(second).isNotNull();
        var a=mapper.readValue(first.getBody(),ChangeMessage.class);var b=mapper.readValue(second.getBody(),ChangeMessage.class);
        consumer.consume(a);consumer.consume(b);assertThat(count("message_inbox")).isEqualTo(1);
        assertThat(a.eventId()).isEqualTo(b.eventId());
    }
}
