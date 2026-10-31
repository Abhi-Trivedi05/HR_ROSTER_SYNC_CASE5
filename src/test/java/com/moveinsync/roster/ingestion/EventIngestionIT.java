package com.moveinsync.roster.ingestion;

import com.moveinsync.roster.IntegrationSupport;
import com.moveinsync.roster.employee.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventIngestionIT extends IntegrationSupport {
    @Autowired TransportService transport;
    @Test void createUpdateAndDeactivate() {
        assertThat(submit(event("E1",5,HrEvent.Type.CREATE)).status()).isEqualTo(EventResult.Status.APPLIED);
        assertThat(submit(event("E1",7,HrEvent.Type.UPDATE)).status()).isEqualTo(EventResult.Status.APPLIED);
        assertThat(submit(event("E1",8,HrEvent.Type.DEACTIVATE)).status()).isEqualTo(EventResult.Status.APPLIED);
        assertThat(jdbc.queryForObject("select active from employees where tenant_id=? and external_employee_id='E1'",Boolean.class,tenant)).isFalse();
        assertThat(count("outbox")).isEqualTo(3);
    }
    @Test void versionsFiveSevenSixAndExactReplayChangeNothing() {
        submit(event("E1",5,HrEvent.Type.CREATE));var seven=event("E1",7,HrEvent.Type.UPDATE);submit(seven);
        assertThat(submit(event("E1",6,HrEvent.Type.UPDATE)).status()).isEqualTo(EventResult.Status.STALE);
        var before=jdbc.queryForMap("select * from employees where tenant_id=? and external_employee_id='E1'",tenant);
        long receipts=count("event_receipts"),versions=count("processed_events"),outbox=count("outbox");
        assertThat(submit(seven).status()).isEqualTo(EventResult.Status.DUPLICATE);
        assertThat(jdbc.queryForMap("select * from employees where tenant_id=? and external_employee_id='E1'",tenant)).isEqualTo(before);
        assertThat(count("event_receipts")).isEqualTo(receipts);assertThat(count("processed_events")).isEqualTo(versions);assertThat(count("outbox")).isEqualTo(outbox);
        assertThat(before.get("source_version")).isEqualTo(7L);
    }
    @Test void sameVersionDetectsCanonicalPayloadAndEventIdConflicts() {
        var original=event("E1",1,HrEvent.Type.CREATE);submit(original);
        var equal=new HrEvent(UUID.randomUUID(),original.employeeId(),original.version(),original.eventType(),original.timestamp().plusSeconds(1),original.data());
        assertThat(submit(equal).status()).isEqualTo(EventResult.Status.DUPLICATE);
        var conflict=new HrEvent(UUID.randomUUID(),"E1",1L,HrEvent.Type.CREATE,original.timestamp(),new HrEvent.Data("Changed","x@example.invalid",null,null,"BLR",true));
        assertThat(submit(conflict).errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(submit(new HrEvent(original.eventId(),"E1",2L,HrEvent.Type.UPDATE,original.timestamp(),original.data())).errorCode()).isEqualTo("EVENT_ID_CONFLICT");
        assertThat(count("outbox")).isEqualTo(1);
    }
    @Test void mixedBatchValidatesEachRecordAndRejectsMassAssignment() throws Exception {
        var good=mapper.valueToTree(event("VALID",1,HrEvent.Type.CREATE));
        var phone=mapper.readTree("{\"eventId\":\""+UUID.randomUUID()+"\",\"employeeId\":\"BAD\",\"version\":1,\"eventType\":\"CREATE\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"data\":{\"name\":\"Fixture\",\"email\":\"f@example.invalid\",\"phone\":\"private-invalid-phone\",\"officeCode\":\"BLR\",\"active\":true}}");
        var office=mapper.valueToTree(new HrEvent(UUID.randomUUID(),"OFFICE",1L,HrEvent.Type.CREATE,java.time.Instant.now(),new HrEvent.Data("Fixture","f@example.invalid",null,null,"UNKNOWN",true)));
        var missing=mapper.readTree("{\"version\":-1}");
        var fractional=mapper.readTree(good.toString().replace("\"version\":1","\"version\":1.5"));
        var malicious=((com.fasterxml.jackson.databind.node.ObjectNode)good.deepCopy()).put("pickupPoint","Untrusted");
        var results=batch.process(tenant,List.of(good,phone,office,missing,fractional,malicious));
        assertThat(results).hasSize(6);
        assertThat(results.get(0).status()).isEqualTo(EventResult.Status.APPLIED);
        assertThat(results.get(1).errorCode()).isEqualTo("INVALID_PHONE");
        assertThat(results.get(2).errorCode()).isEqualTo("INVALID_OFFICE");
        assertThat(results.subList(3,6)).allMatch(r->r.status()==EventResult.Status.REJECTED);
        assertThat(mapper.writeValueAsString(results)).doesNotContain("private-invalid-phone","f@example.invalid");
        assertThat(count("employees")).isEqualTo(1);
    }
    @Test void twentyConcurrentReplaysProduceOneLogicalUpdate() throws Exception {
        var event=event("RACE",7,HrEvent.Type.CREATE);
        var results=race(20,n->processor.process(tenant,event,n));
        assertThat(results.stream().filter(r->r.status()==EventResult.Status.APPLIED).count()).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);assertThat(count("event_receipts")).isEqualTo(1);
        assertThat(count("employees")).isEqualTo(1);
    }
    @Test void concurrentVersionsEightAndNineFinishAtNine() throws Exception {
        submit(event("E1",7,HrEvent.Type.CREATE));
        race(2,n->processor.process(tenant,event("E1",8+n,HrEvent.Type.UPDATE),n));
        assertThat(jdbc.queryForObject("select source_version from employees where tenant_id=? and external_employee_id='E1'",Long.class,tenant)).isEqualTo(9);
    }
    @Test void transportAndHrWorkConcurrentlyWithoutLosingEitherOwner() throws Exception {
        submit(event("E1",1,HrEvent.Type.CREATE));UUID employee=employeeId("E1");
        race(2,n->{
            if (n==0) return processor.process(tenant,event("E1",2,HrEvent.Type.UPDATE),n);
            authenticate(tenant,"ADMIN",null);
            transport.update(tenant,employee,new EmployeeDtos.TransportUpdate("Gate",true,0L));
            return EventResult.of(n,null,EventResult.Status.APPLIED,"NONE");
        });
        var row=jdbc.queryForMap("select name,pickup_point,special_assistance,source_version,transport_revision from employees where tenant_id=? and id=?",tenant,employee);
        assertThat(row).containsEntry("name","Fixture 2").containsEntry("pickup_point","Gate").containsEntry("special_assistance",true)
                .containsEntry("source_version",2L).containsEntry("transport_revision",1L);
    }
    @Test void failureAfterEmployeeWriteRollsBackEmployeeReceiptAndOutbox() {
        doThrow(new IllegalStateException("injected")).when(failures).at("employee-written");
        assertThat(submit(event("ROLLBACK",1,HrEvent.Type.CREATE)).status()).isEqualTo(EventResult.Status.RETRYABLE);
        assertThat(count("employees")).isZero();assertThat(count("outbox")).isZero();assertThat(count("event_receipts")).isZero();
    }
    private <T> List<T> race(int threads,IntFunction<T> work) throws Exception {
        var start=new CyclicBarrier(threads);var results=new ArrayList<Future<T>>();
        try (var executor=Executors.newFixedThreadPool(threads)) {
            for(int n=0;n<threads;n++) { int index=n;results.add(executor.submit(()->{start.await(20,TimeUnit.SECONDS);return work.apply(index);})); }
            List<T> values=new ArrayList<>();for(var future:results) values.add(future.get(60,TimeUnit.SECONDS));return values;
        }
    }
}
