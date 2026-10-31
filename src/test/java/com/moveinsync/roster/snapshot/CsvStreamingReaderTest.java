package com.moveinsync.roster.snapshot;

import jakarta.validation.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class CsvStreamingReaderTest {
    static ValidatorFactory factory;
    CsvStreamingReader reader;
    final String header="employeeId,version,name,email,phone,managerId,officeCode,active\r\n";
    @BeforeAll static void start() { factory=Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void stop() { factory.close(); }
    @BeforeEach void setup() { reader=new CsvStreamingReader(new SnapshotValidator(factory.getValidator()),1000); }
    InputStream stream(String csv) { return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)); }
    @Test void quotesUnicodeAndInvalidRecordsAreIndependent() throws Exception {
        List<SnapshotModels.Row> result=new ArrayList<>();
        reader.read(stream(header+"E1,1,\"Fixture, One\",f@example.invalid,,,BLR,true\r\nE2,1,Fixture,bad,,,BLR,true\r\n"),result::addAll);
        assertThat(result).hasSize(2);assertThat(result.get(0).valid()).isTrue();assertThat(result.get(0).data().name()).isEqualTo("Fixture, One");
        assertThat(result.get(1).errorCode()).isEqualTo("INVALID_EMAIL");
    }
    @Test void batchesRemainBoundedFor200KRows() throws Exception {
        // Generates rows lazily; this tests parsing/memory discipline, not database reconciliation performance.
        String row="E1,1,Fixture,f@example.invalid,,,BLR,true\n";
        InputStream generated=new InputStream() {
            long position;final byte[] prefix=header.getBytes(StandardCharsets.UTF_8),data=row.getBytes(StandardCharsets.UTF_8);
            @Override public int read() { if(position<prefix.length) return prefix[(int)position++]&255;long offset=position++-prefix.length;return offset>=200000L*data.length?-1:data[(int)(offset%data.length)]&255; }
        };
        int[] max={0},batches={0};
        assertThat(reader.read(generated,rows->{max[0]=Math.max(max[0],rows.size());batches[0]++;})).isEqualTo(200000);
        assertThat(max[0]).isEqualTo(1000);assertThat(batches[0]).isEqualTo(200);
    }
    @Test void oversizedLogicalRecordIsRejected() {
        assertThatThrownBy(()->reader.read(stream(header+"E,1,\""+"x".repeat(9000)),rows->{})).isInstanceOf(IOException.class);
    }
    @Test void malformedHeaderAndTruncatedQuotesAreRejected() {
        assertThatThrownBy(()->reader.read(stream("bad,header\n"),rows->{})).isInstanceOf(IOException.class);
        assertThatThrownBy(()->reader.read(stream(header+"E,1,\"truncated"),rows->{})).isInstanceOf(IOException.class);
    }
    @Test void exactThresholdAndZeroDenominatorAreSafe() {
        assertThat(SafetyBrakeService.decide(100,30,new BigDecimal("0.30")).blocked()).isTrue();
        assertThat(SafetyBrakeService.decide(100,29,new BigDecimal("0.30")).blocked()).isFalse();
        assertThat(SafetyBrakeService.decide(0,0,new BigDecimal("0.30")).fraction()).isEqualByComparingTo("0");
    }
}
