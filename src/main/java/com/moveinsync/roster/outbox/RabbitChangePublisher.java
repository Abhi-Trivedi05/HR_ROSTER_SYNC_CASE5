package com.moveinsync.roster.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RabbitChangePublisher implements ChangePublisher {
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final long timeout;
    public RabbitChangePublisher(RabbitTemplate rabbit,ObjectMapper mapper,@Value("${roster.outbox.confirm-timeout-ms:3000}") long timeout) {
        this.rabbit=rabbit;this.mapper=mapper;this.timeout=timeout;
    }
    @Override public void publish(ChangeMessage change) throws Exception {
        var properties=new MessageProperties();
        properties.setContentType("application/json"); properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(change.eventId().toString());
        CorrelationData correlation=new CorrelationData(change.eventId().toString());
        rabbit.send("roster.changes","employee.changed",new Message(mapper.writeValueAsBytes(change),properties),correlation);
        var confirmation=correlation.getFuture().get(timeout,TimeUnit.MILLISECONDS);
        if (!confirmation.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("BROKER_NOT_CONFIRMED");
    }
}
