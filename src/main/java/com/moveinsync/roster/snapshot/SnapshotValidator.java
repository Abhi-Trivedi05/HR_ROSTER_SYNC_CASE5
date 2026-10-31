package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.ingestion.HrEvent;
import jakarta.validation.Validator;
import java.util.*;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class SnapshotValidator {
    private final Validator validator;
    public SnapshotValidator(Validator validator) { this.validator=validator; }
    public SnapshotModels.Row parse(CSVRecord record) {
        String employee=null;Long version=null;
        try {
            employee=record.size()>0?record.get(0).strip():null;
            if(employee!=null&&(employee.isBlank()||employee.length()>80))employee=null;
            if (record.size()!=8) return invalid(record.getRecordNumber(),employee,null,"INVALID_COLUMN_COUNT");
            employee=record.get("employeeId").strip();
            if (employee.isBlank()||employee.length()>80) return invalid(record.getRecordNumber(),"MISSING_EMPLOYEE_ID");
            try { String source=record.get("version").strip();if(!source.matches("[0-9]{1,19}"))return invalid(record.getRecordNumber(),employee,null,"INVALID_VERSION");version=Long.parseLong(source); }
            catch(NumberFormatException ex) { return invalid(record.getRecordNumber(),employee,null,"INVALID_VERSION"); }
            String active=record.get("active").strip();
            if (!Set.of("true","false").contains(active)) return invalid(record.getRecordNumber(),employee,version,"INVALID_ACTIVE");
            String manager=record.get("managerId").strip();
            var data=new HrEvent.Data(record.get("name"),record.get("email"),empty(record.get("phone")),
                    manager.isEmpty()?null:UUID.fromString(manager),record.get("officeCode"),Boolean.valueOf(active));
            var violations=validator.validate(data);
            if (!violations.isEmpty()) {
                var paths=violations.stream().map(v->v.getPropertyPath().toString()).toList();
                return invalid(record.getRecordNumber(),employee,version,paths.contains("phone")?"INVALID_PHONE":paths.contains("email")?"INVALID_EMAIL":"MISSING_OR_INVALID_FIELDS");
            }
            String hash=data.hash();
            return new SnapshotModels.Row(record.getRecordNumber(),employee,version,data,true,"NONE",hash,HashBucketProcessor.bucket(employee));
        } catch (IllegalArgumentException ex) { return invalid(record.getRecordNumber(),employee,version,"INVALID_FIELDS"); }
    }
    public static SnapshotModels.Row invalid(long row,String code) { return new SnapshotModels.Row(row,null,null,null,false,code,null,0); }
    private SnapshotModels.Row invalid(long row,String employee,Long version,String code) { return new SnapshotModels.Row(row,employee,version,null,false,code,null,0); }
    private String empty(String input) { return input.isBlank()?null:input.strip(); }
}
