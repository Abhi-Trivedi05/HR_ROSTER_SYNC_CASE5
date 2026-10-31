package com.moveinsync.roster.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.moveinsync.roster.outbox.ChangeMessage;

@Component
public class BookingConsumer {
    private final ObjectMapper mapper;
    private final BookingCancellationService service;
    public BookingConsumer(ObjectMapper mapper,BookingCancellationService service) { this.mapper=mapper;this.service=service; }
    @RabbitListener(queues="roster.booking",autoStartup="${roster.booking.consumer-enabled:true}")
    public void receive(Message message) {
        try { service.consume(mapper.readValue(message.getBody(),ChangeMessage.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            throw new AmqpRejectAndDontRequeueException("INVALID_CHANGE_MESSAGE");
        } catch (java.io.IOException ex) { throw new AmqpRejectAndDontRequeueException("INVALID_CHANGE_MESSAGE"); }
    }
}
