package com.moveinsync.roster.shared;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ProblemDetail domain(ApiException ex) { return problem(ex.status(), ex.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, MissingServletRequestParameterException.class})
    ProblemDetail invalid(Exception ignored) { return problem(HttpStatus.BAD_REQUEST, "Invalid request fields or values"); }
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class})
    ProblemDetail conflict(Exception ignored) { return problem(HttpStatus.CONFLICT, "Conflicting identity or revision; reload before retrying"); }
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(Exception ignored) { return problem(HttpStatus.FORBIDDEN, "Access denied"); }
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ProblemDetail missing(Exception ignored) { return problem(HttpStatus.NOT_FOUND, "Resource not found"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail method(Exception ignored) { return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not supported"); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail media(Exception ignored) { return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"); }
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(Exception ignored) { return problem(HttpStatus.SERVICE_UNAVAILABLE, "Database temporarily unavailable"); }
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ignored) {
        // Never log exception messages/stack traces: JDBC and binding errors may contain PII.
        LOG.error("Unhandled directory request failure");
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");
    }
    private ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail result=ProblemDetail.forStatusAndDetail(status,detail);
        result.setInstance(java.net.URI.create("urn:roster:error"));
        result.setProperty("errorCode",status.name());
        result.setProperty("message",detail);
        result.setProperty("correlationId",org.slf4j.MDC.get("correlationId"));
        result.setProperty("timestamp",java.time.Instant.now());
        return result;
    }
}
