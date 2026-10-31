package com.moveinsync.roster.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BatchMetricsTest {
    @Test void countsReceivedAppliedRejectedAndDuplicatesWithoutMetricIdentityLabels() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(BatchProcessingService.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();logger.addAppender(logs);
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var mapper=new ObjectMapper().findAndRegisterModules();var processor=mock(EventProcessor.class);var metrics=new SimpleMeterRegistry();
            var validator=new EventValidator(factory.getValidator(),mock(JdbcTemplate.class));
            var service=new BatchProcessingService(mapper,validator,processor,metrics);UUID tenant=UUID.randomUUID();
            var event=new HrEvent(UUID.randomUUID(),"E1",1L,HrEvent.Type.CREATE,Instant.now(),new HrEvent.Data("Fixture","f@example.invalid",null,null,"BLR",true));
            when(processor.process(eq(tenant),any(),anyInt())).thenAnswer(call->EventResult.of(call.getArgument(2),event.eventId(),
                    (int)call.getArgument(2)==0?EventResult.Status.APPLIED:EventResult.Status.DUPLICATE,"NONE"));
            var result=service.process(tenant,List.of(mapper.valueToTree(event),mapper.createObjectNode(),mapper.valueToTree(event)));
            assertThat(result).hasSize(3);
            assertThat(metrics.get("hr.events.received").counter().count()).isEqualTo(3);
            for(String status:List.of("applied","rejected","duplicate"))assertThat(metrics.get("hr.events."+status).counter().count()).isEqualTo(1);
            assertThat(metrics.getMeters()).allMatch(m->m.getId().getTags().isEmpty());
            assertThat(logs.list).hasSize(3);
            assertThat(logs.list).allMatch(log->log.getKeyValuePairs().stream().anyMatch(pair->pair.key.equals("correlationId")&&pair.value!=null));
            assertThat(logs.list).allMatch(log->!log.getKeyValuePairs().toString().contains("f@example.invalid"));
        } finally { logger.detachAppender(logs);logs.stop(); }
    }
}
