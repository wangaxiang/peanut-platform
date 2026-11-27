package com.peanut.common;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResult<Void>> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .body(ApiResult.fail(response.getStatusCode().value(), response.getBody().getDetail()));
        }
        LOG.error("Unhandled API error", exception);
        return ResponseEntity.internalServerError().body(ApiResult.fail(500, "Internal server error"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResult<Void>> handleStatus(ResponseStatusException exception) {
        int code = exception.getStatusCode().value();
        return ResponseEntity.status(code).body(ApiResult.fail(code, exception.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ApiResult.fail(400, message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResult<Void>> handleInvalidInput() {
        return ResponseEntity.badRequest().body(ApiResult.fail(400, "Invalid request format"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResult<Void>> handleIntegrityViolation() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResult.fail(409, "The operation conflicts with related records or data constraints"));
    }
}
