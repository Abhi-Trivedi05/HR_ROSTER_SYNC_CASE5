package com.moveinsync.roster.ingestion;

import com.moveinsync.roster.shared.CanonicalHash;
import jakarta.validation.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventValidatorTest {
    static ValidatorFactory factory;
    EventValidator validator;
    @BeforeAll static void start() { factory=Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { factory.close(); }
    @BeforeEach void setup() { validator=new EventValidator(factory.getValidator(),mock(JdbcTemplate.class)); }
    HrEvent create(String id,Long version,String phone,String email) { return new HrEvent(UUID.randomUUID(),id,version,HrEvent.Type.CREATE,Instant.now(),new HrEvent.Data("Fixture",email,phone,null,"BLR",true)); }
    @Test void validCreateAndUpdate() { assertThat(validator.structure(create("E",1L,"+919876543210","f@example.invalid")).valid()).isTrue(); }
    @Test void validDeactivationHasNoData() { assertThat(validator.structure(new HrEvent(UUID.randomUUID(),"E",1L,HrEvent.Type.DEACTIVATE,Instant.now(),null)).valid()).isTrue(); }
    @Test void phoneEmailIdAndVersionCodes() {
        assertThat(validator.structure(create("E",1L,"bad","f@example.invalid")).errorCode()).isEqualTo("INVALID_PHONE");
        assertThat(validator.structure(create("E",1L,null,"private-invalid-email")).errorCode()).isEqualTo("INVALID_EMAIL");
        assertThat(validator.structure(create("",1L,null,"f@example.invalid")).errorCode()).isEqualTo("MISSING_EMPLOYEE_ID");
        assertThat(validator.structure(create("E",-1L,null,"f@example.invalid")).errorCode()).isEqualTo("INVALID_VERSION");
        assertThat(validator.structure(create("E",null,null,"f@example.invalid")).errorCode()).isEqualTo("INVALID_VERSION");
    }
    @Test void hashIsUnambiguousDeterministicAndExcludesTransport() {
        assertThat(CanonicalHash.of("ab","c")).isNotEqualTo(CanonicalHash.of("a","bc"));
        assertThat(CanonicalHash.of((Object)null)).isNotEqualTo(CanonicalHash.of(""));
        assertThat(create("E",1L,null,"f@example.invalid").hash()).isEqualTo(create("E",1L,null,"f@example.invalid").hash());
        assertThat(create("E",1L,null,"f@example.invalid").toString()).doesNotContain("f@example.invalid");
    }
}
