package com.moveinsync.roster.shared;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class CanonicalHash {
    private CanonicalHash() { }
    public static String of(Object... fields) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            for (Object field : fields) {
                if (field == null) out.writeInt(-1);
                else {
                    byte[] value = field.toString().getBytes(StandardCharsets.UTF_8);
                    out.writeInt(value.length); out.write(value);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException ex) { throw new IllegalStateException("Hash unavailable"); }
    }
}
