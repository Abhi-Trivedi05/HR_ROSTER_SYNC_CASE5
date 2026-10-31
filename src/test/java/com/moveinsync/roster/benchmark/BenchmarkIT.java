package com.moveinsync.roster.benchmark;

import com.moveinsync.roster.IntegrationSupport;
import com.moveinsync.roster.ingestion.*;
import com.moveinsync.roster.snapshot.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("benchmark") @Import({QueryCountingConfig.class,BenchmarkProbeConfig.class}) @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.springframework.test.context.TestPropertySource(properties="logging.level.com.moveinsync.roster.ingestion.BatchProcessingService=OFF")
class BenchmarkIT extends IntegrationSupport {
    @Autowired ReconciliationService snapshots;
    @Autowired BenchmarkProbeConfig.Probe probe;
    @org.springframework.test.context.DynamicPropertySource static void batchSize(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("roster.snapshot.batch-size",()->Integer.getInteger("roster.benchmark.batchSize",5000));
    }
    final Path output=Path.of("benchmark-output");
    @Test @Order(1) void snapshot200K() throws Exception {
        Path file=Files.createTempFile("snapshot-200k-",".csv");
        try {
            try(var writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8)) {
                writer.write("employeeId,version,name,email,phone,managerId,officeCode,active\n");
                for(int n=0;n<200000;n++) writer.write("E"+n+",1,Fixture,fixture@example.invalid,,,BLR,true\n");
            }
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=new DigestInputStream(Files.newInputStream(file),digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            String checksum=HexFormat.of().formatHex(digest.digest());authenticate(tenant,"OPERATOR",null);
            List<Long> batchLatencies=new ArrayList<>(200);AtomicLong previous=new AtomicLong(System.nanoTime());
            probe.observer=point->{if(point.equals("staging-batch-committed")){long now=System.nanoTime();batchLatencies.add(now-previous.getAndSet(now));}};
            try(var measurement=new Measurement()) {
                var run=snapshots.create(tenant,new SnapshotModels.Create(checksum,1L,Instant.now()));
                long stageStart=System.nanoTime();
                try(var input=Files.newInputStream(file)) { run=snapshots.upload(tenant,run.runId(),input); }
                double stageSeconds=(System.nanoTime()-stageStart)/1e9;
                assertThat(run.status()).isEqualTo("READY");
                snapshots.approve(tenant,run.runId(),new SnapshotModels.Approval(checksum,run.revision(),false),"benchmark");
                long applyStart=System.nanoTime();assertThat(snapshots.apply(tenant,run.runId()).status()).isEqualTo("COMPLETED");
                double applySeconds=(System.nanoTime()-applyStart)/1e9;
                var result=measurement.finish("snapshot-200k",200000,batchLatencies,Integer.getInteger("roster.benchmark.batchSize",5000)+"-row parse/staging batches; first sample includes upload");
                result.put("stagingSeconds",stageSeconds);result.put("applySeconds",applySeconds);probe.observer=point->{};
                assertThat(count("employees")).isEqualTo(200000);write(result);
                assertThat(((Number)result.get("elapsedSeconds")).doubleValue()).isLessThan(60);
            }
        } finally { Files.deleteIfExists(file); }
    }
    @Test @Order(2) void ingestion200K() throws Exception {
        List<Long> latencies=new ArrayList<>(200000);
        try(var measurement=new Measurement()) {
            for(int n=0;n<200000;n++) {
                long started=System.nanoTime();
                assertThat(submit(event("E"+n,1,HrEvent.Type.CREATE)).status()).isEqualTo(EventResult.Status.APPLIED);
                latencies.add(System.nanoTime()-started);
                if((n+1)%10000==0) {
                    Files.createDirectories(output);
                    mapper.writeValue(output.resolve("progress.json").toFile(),Map.of("benchmark","ingestion-200k","completed",n+1,"target",200000,"elapsedSeconds",(System.nanoTime()-measurement.start)/1e9));
                }
            }
            var result=measurement.finish("ingestion-200k",200000,latencies,"single-event ingestion service calls; HTTP excluded");
            assertThat(count("employees")).isEqualTo(200000);write(result);
        }
    }
    @Test @Order(3) void concurrentEmployeeUpdates() throws Exception {
        for(int n=0;n<100;n++)submit(event("E"+n,1,HrEvent.Type.CREATE));
        AtomicLong[] versions=new AtomicLong[100];Arrays.setAll(versions,n->new AtomicLong(1));
        var latencies=new ConcurrentLinkedQueue<Long>();int operations=20000;
        try(var measurement=new Measurement();var executor=Executors.newFixedThreadPool(20)) {
            List<Future<?>> tasks=new ArrayList<>();var start=new CyclicBarrier(20);
            for(int thread=0;thread<20;thread++) {int offset=thread;tasks.add(executor.submit(()-> {
                start.await();
                for(int n=0;n<1000;n++) {
                    int employee=(n+offset)%100;long version=versions[employee].incrementAndGet();long began=System.nanoTime();
                    processor.process(tenant,event("E"+employee,version,HrEvent.Type.UPDATE),n);latencies.add(System.nanoTime()-began);
                }
                return null;
            })); }
            for(var task:tasks) task.get(20,TimeUnit.MINUTES);
            var result=measurement.finish("concurrent-updates-20-threads",operations,new ArrayList<>(latencies),"per-event core transaction latency; stale arrivals included");
            for(int n=0;n<100;n++) assertThat(jdbc.queryForObject("select source_version from employees where tenant_id=? and external_employee_id=?",Long.class,tenant,"E"+n)).isEqualTo(versions[n].get());
            write(result);
        }
    }
    void write(Map<String,Object> result) throws IOException {
        Files.createDirectories(output);mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(result.get("name")+".json").toFile(),result);
    }
    static final class Measurement implements AutoCloseable {
        final long start=System.nanoTime(),queries=QueryCountingConfig.OPERATIONS.get();
        final AtomicLong peak=new AtomicLong();final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();
        Measurement() { sampler.scheduleAtFixedRate(()->peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max),0,10,TimeUnit.MILLISECONDS); }
        Map<String,Object> finish(String name,int rows,List<Long> latencies,String unit) {
            double seconds=(System.nanoTime()-start)/1e9;latencies.sort(Long::compareTo);
            Map<String,Object> result=new LinkedHashMap<>();result.put("name",name);result.put("records",rows);result.put("elapsedSeconds",seconds);
            result.put("throughputPerSecond",rows/seconds);result.put("latencyUnit",unit);result.put("p50Ms",percentile(latencies,.50));
            result.put("p95Ms",percentile(latencies,.95));result.put("p99Ms",percentile(latencies,.99));result.put("peakSampledHeapBytes",peak.get());
            result.put("jdbcExecuteCalls",QueryCountingConfig.OPERATIONS.get()-queries);result.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
            result.put("java",System.getProperty("java.version"));result.put("os",System.getProperty("os.name"));result.put("processors",Runtime.getRuntime().availableProcessors());result.put("timestamp",Instant.now().toString());return result;
        }
        static double percentile(List<Long> values,double q) { return values.isEmpty()?0:values.get(Math.min(values.size()-1,(int)Math.ceil(q*values.size())-1))/1e6; }
        @Override public void close() { sampler.shutdownNow(); }
    }
}
