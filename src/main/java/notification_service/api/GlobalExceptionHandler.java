package notification_service.api;

import lombok.extern.slf4j.Slf4j;
import notification_service.exception.DuplicateRequestException;
import notification_service.exception.RateLimitExceededException;
import notification_service.exception.TemplateNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles @Valid validation failures — returns a map of field → error message.
     * Example response:
     * { "userId": "userId is required", "channel": "channel is required" }
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(err -> errors.put(err.getField(), err.getDefaultMessage()));
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An unexpected error occurred"));
    }

    /**
     * HTTP 404 Not Found — templateName was given but no matching row exists
     * in notification_templates for that name + channel combination.
     */
    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTemplateNotFound(TemplateNotFoundException ex) {
        log.warn("Template not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * HTTP 400 Bad Request — neither 'message' nor 'templateName' was provided.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * HTTP 409 Conflict — same idempotency key submitted more than once.
     * Tells the caller: "We already processed this request, don't resend."
     */
    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateRequestException ex) {
        log.warn("Duplicate request rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * HTTP 429 Too Many Requests — user has exceeded the per-channel rate limit.
     * Tells the caller: "Slow down, you've sent too many notifications recently."
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleRateLimit(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", ex.getMessage()));
    }
}
