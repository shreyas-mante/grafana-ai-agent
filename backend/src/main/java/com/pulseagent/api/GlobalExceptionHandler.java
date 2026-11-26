package com.pulseagent.api;

import com.pulseagent.llm.LlmException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/** Maps exceptions to short, safe messages. Stack traces and internals never reach the client. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField()).distinct().limit(5).collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid fields: " + fields));
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    ResponseEntity<?> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method() {
        return ResponseEntity.status(405).body(Map.of("error", "Method not allowed"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> mediaType() {
        return ResponseEntity.status(415).body(Map.of("error", "Use Content-Type: application/json"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "Not found"));
    }

    @ExceptionHandler(LlmException.class)
    ResponseEntity<?> llm(LlmException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> busy(IllegalStateException e) {
        return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> other(Exception e) {
        log.warn("Unhandled error: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(500).body(Map.of("error", "Something went wrong"));
    }
}
