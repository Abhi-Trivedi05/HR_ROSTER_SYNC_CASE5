package com.moveinsync.roster.ingestion;

public record ValidationResult(boolean valid, String errorCode) {
    public static ValidationResult ok() { return new ValidationResult(true,"NONE"); }
    public static ValidationResult error(String code) { return new ValidationResult(false,code); }
}
