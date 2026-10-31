package com.moveinsync.roster.shared;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    public ApiException(HttpStatus status, String safeMessage) { super(safeMessage); this.status = status; }
    public HttpStatus status() { return status; }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "Resource not found"); }
    public static ApiException invalid(String safeMessage) { return new ApiException(HttpStatus.BAD_REQUEST, safeMessage); }
    public static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "Conflicting identity or revision; reload before retrying"); }
}
