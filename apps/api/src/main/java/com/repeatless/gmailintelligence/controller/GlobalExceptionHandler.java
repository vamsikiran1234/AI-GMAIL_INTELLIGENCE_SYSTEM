package com.repeatless.gmailintelligence.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(errorBody(ex.getReason(), ex.getStatusCode().value()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Unexpected error";
        // "No Gmail connection" → 409 so frontend shows meaningful message
        if (message.contains("No Gmail connection")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorBody("Gmail not connected. Please click 'Connect Gmail' first.", 409));
        }
        // "Thread summaries require synced mailbox" → 409
        if (message.contains("require synced")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorBody("Mailbox not synced. Please click 'Run Sync' first.", 409));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(message, 500));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(message, 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Internal server error";
        System.err.println("[GlobalExceptionHandler] Unhandled exception: " + ex.getClass().getSimpleName() + " — " + message);
        ex.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(message, 500));
    }

    private Map<String, Object> errorBody(String message, int status) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "status", status,
                "error", message != null ? message : "Unknown error"
        );
    }
}
