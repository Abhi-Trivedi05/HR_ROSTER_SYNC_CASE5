package com.moveinsync.roster.ingestion;

import jakarta.validation.Validator;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventValidator {
    private final Validator validator;
    private final JdbcTemplate jdbc;
    public EventValidator(Validator validator, JdbcTemplate jdbc) { this.validator=validator; this.jdbc=jdbc; }
    public ValidationResult structure(HrEvent event) {
        if (event == null) return ValidationResult.error("MISSING_EVENT");
        var violations = validator.validate(event);
        if (!violations.isEmpty()) {
            // Deterministic priority; report codes only, never invalid values or validator messages.
            var paths = violations.stream().map(v -> v.getPropertyPath().toString()).sorted().toList();
            if (paths.contains("employeeId")) return ValidationResult.error("MISSING_EMPLOYEE_ID");
            if (paths.contains("version")) return ValidationResult.error("INVALID_VERSION");
            if (paths.contains("data.phone")) return ValidationResult.error("INVALID_PHONE");
            if (paths.contains("data.email")) return ValidationResult.error("INVALID_EMAIL");
            return ValidationResult.error("MISSING_OR_INVALID_FIELDS");
        }
        if (event.eventType()!=HrEvent.Type.DEACTIVATE && event.data()==null) return ValidationResult.error("MISSING_EMPLOYEE_DATA");
        if (event.eventType()==HrEvent.Type.DEACTIVATE && event.data()!=null) return ValidationResult.error("DEACTIVATION_DATA_NOT_ALLOWED");
        return ValidationResult.ok();
    }
    public ValidationResult references(UUID tenant, HrEvent event) {
        if (event.eventType()==HrEvent.Type.DEACTIVATE) return ValidationResult.ok();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from offices where tenant_id=? and code=? and active)",
                Boolean.class,tenant,event.data().officeCode()))) return ValidationResult.error("INVALID_OFFICE");
        return ValidationResult.ok();
    }
}
