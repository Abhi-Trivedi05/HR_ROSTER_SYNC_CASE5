package com.moveinsync.roster.snapshot;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Consumer;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;

@Component
public class CsvStreamingReader {
    private static final List<String> HEADER=List.of("employeeId","version","name","email","phone","managerId","officeCode","active");
    private final SnapshotValidator validator;
    private final int batchSize;
    public CsvStreamingReader(SnapshotValidator validator,@org.springframework.beans.factory.annotation.Value("${roster.snapshot.batch-size:5000}") int batchSize) {
        if(batchSize<100||batchSize>5000)throw new IllegalArgumentException("Snapshot batch size must be between 100 and 5000");
        this.validator=validator;this.batchSize=batchSize;
    }
    public long read(InputStream input,Consumer<List<SnapshotModels.Row>> batches) throws IOException {
        var utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader=new RecordBoundedReader(new InputStreamReader(input,utf8));
                var parser=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false)
                        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get().parse(reader)) {
            if (!parser.getHeaderNames().equals(HEADER)) throw new IOException("INVALID_CSV_HEADER");
            List<SnapshotModels.Row> batch=new ArrayList<>(batchSize); long count=0;
            for (CSVRecord record:parser) {
                if (++count>200000) throw new IOException("ROW_LIMIT_EXCEEDED");
                batch.add(validator.parse(record));
                if (batch.size()==batchSize) { batches.accept(batch); batch=new ArrayList<>(batchSize); }
            }
            if (!batch.isEmpty()) batches.accept(batch);
            return count;
        } catch (UncheckedIOException ex) { throw new IOException("MALFORMED_CSV"); }
    }
    /** Counts a complete logical record, including quoted newlines; prevents parser buffer amplification. */
    static final class RecordBoundedReader extends FilterReader {
        private boolean quoted; private int count; private boolean first=true;
        RecordBoundedReader(Reader input) { super(input); }
        @Override public int read() throws IOException {
            int c=super.read();
            if (first) { first=false; if (c==0xfeff) c=super.read(); }
            if (c=='"') quoted=!quoted;
            if ((c=='\n'||c=='\r')&&!quoted) count=0;
            else if (c!=-1&&++count>8192) throw new IOException("CSV_RECORD_TOO_LARGE");
            return c;
        }
        @Override public int read(char[] target,int offset,int length) throws IOException {
            if (length==0) return 0;
            int n=0;
            while (n<length) { int c=read(); if (c==-1) return n==0?-1:n; target[offset+n++]=(char)c; }
            return n;
        }
    }
}
