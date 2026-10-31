package com.moveinsync.roster.ingestion;

import com.fasterxml.jackson.databind.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class BatchProcessingService {
    private static final Logger LOG=LoggerFactory.getLogger(BatchProcessingService.class);
    private final ObjectMapper mapper;
    private final EventValidator validator;
    private final EventProcessor processor;
    private final MeterRegistry metrics;
    public BatchProcessingService(ObjectMapper mapper,EventValidator validator,EventProcessor processor,MeterRegistry metrics) {
        this.mapper=mapper;this.validator=validator;this.processor=processor;this.metrics=metrics;
    }
    @PreAuthorize("hasRole('HR_INTEGRATION') and @tenantAccess.matches(#tenant)")
    public List<EventResult> process(UUID tenant,List<JsonNode> records) {
        if (records==null || records.isEmpty() || records.size()>1000) throw com.moveinsync.roster.shared.ApiException.invalid("Batch size must be between 1 and 1000");
        List<EventResult> results=new ArrayList<>(records.size());
        for (int index=0;index<records.size();index++) results.add(one(tenant,records.get(index),index));
        return List.copyOf(results);
    }
    private EventResult one(UUID tenant,JsonNode node,int index) {
        metrics.counter("hr.events.received").increment();
        HrEvent event=null; EventResult result;
        try {
            event=mapper.treeToValue(node,HrEvent.class);
            var validation=validator.structure(event);
            result=validation.valid()?processor.process(tenant,event,index):EventResult.of(index,event==null?null:event.eventId(),EventResult.Status.REJECTED,validation.errorCode());
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            String code="INVALID_FIELDS";
            if(ex instanceof com.fasterxml.jackson.databind.JsonMappingException mapping) {
                var fields=mapping.getPath().stream().map(com.fasterxml.jackson.databind.JsonMappingException.Reference::getFieldName).toList();
                if(fields.contains("version"))code="INVALID_VERSION";
                else if(fields.contains("eventType"))code="INVALID_EVENT_TYPE";
                else if(fields.contains("eventId"))code="INVALID_EVENT_ID";
                else if(fields.contains("timestamp"))code="INVALID_TIMESTAMP";
            }
            result=EventResult.of(index,null,EventResult.Status.REJECTED,code);
        } catch (EmployeeMutationRepository.EventRejected ex) {
            result=EventResult.of(index,event.eventId(),EventResult.Status.REJECTED,ex.code());
        } catch (DataAccessException | org.springframework.transaction.TransactionException ex) {
            result=EventResult.of(index,event==null?null:event.eventId(),EventResult.Status.RETRYABLE,"DATABASE_UNAVAILABLE");
        } catch (RuntimeException ex) {
            result=EventResult.of(index,event==null?null:event.eventId(),EventResult.Status.RETRYABLE,"PROCESSING_FAILURE");
        }
        metrics.counter("hr.events."+result.status().name().toLowerCase(Locale.ROOT)).increment();
        var log=LOG.atInfo().addKeyValue("tenantId",tenant).addKeyValue("employeeId",event==null?null:com.moveinsync.roster.shared.CanonicalHash.of(tenant,event.employeeId()))
                .addKeyValue("eventType",event==null?null:event.eventType()).addKeyValue("sourceVersion",event==null?null:event.version())
                .addKeyValue("processingStatus",result.status());
        if(MDC.get("correlationId")==null)log.addKeyValue("correlationId",event==null||event.eventId()==null?UUID.randomUUID():event.eventId());
        log.log("HR event processed");
        return result;
    }
}
