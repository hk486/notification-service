package notification_service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.api.dto.NotificationRequest;
import notification_service.api.dto.NotificationResponse;
import notification_service.exception.DuplicateRequestException;
import notification_service.exception.RateLimitExceededException;
import notification_service.model.NotificationLog;
import notification_service.metrics.NotificationMetrics;
import notification_service.producer.NotificationProducer;
import notification_service.repository.NotificationLogRepository;
import notification_service.service.RedisIdempotencyService;
import notification_service.service.RedisRateLimiterService;
import notification_service.service.TemplateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Send EMAIL, SMS and PUSH notifications")
public class NotificationController {

    private final NotificationProducer producer;
    private final NotificationLogRepository notificationLogRepository;
    private final RedisIdempotencyService idempotencyService;
    private final RedisRateLimiterService rateLimiterService;
    private final TemplateService templateService;
    private final NotificationMetrics metrics;

    @PostMapping
    @Operation(
            summary = "Send a notification",
            description = "Publishes a notification to the correct Kafka topic based on priority. " +
                    "HIGH → OTP/Alerts, MEDIUM → Order updates, LOW → Promotions"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Notification accepted and queued"),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {

        // Generate idempotency key if not supplied by caller
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            request.setIdempotencyKey(UUID.randomUUID().toString());
        }

        String idempotencyKey = request.getIdempotencyKey();

        // ── GUARDRAIL 1: Idempotency ─────────────────────────────────────────
        // Reject if this exact key was already processed within the last 24 hours.
        // Prevents the caller from accidentally sending the same OTP twice.
        if (!idempotencyService.isNewRequest(idempotencyKey)) {
            metrics.incrementRejectedDuplicate(request.getChannel().name());
            throw new DuplicateRequestException(idempotencyKey);
        }

        // ── GUARDRAIL 2: Rate Limiting ───────────────────────────────────────
        // Reject if the user has already sent too many notifications on this channel
        // within the current sliding window (default: 5 per 60 seconds).
        if (!rateLimiterService.isAllowed(request.getUserId(), request.getChannel().name())) {
            metrics.incrementRejectedRateLimit(request.getChannel().name());
            throw new RateLimitExceededException(request.getUserId(), request.getChannel().name());
        }

        // ── GUARDRAIL 3: Template / Message validation ─────────────────────
        // Validate that either a raw message or a known template name is present.
        // This runs synchronously so the caller gets an immediate 400/404.
        templateService.validate(request.getTemplateName(), request.getChannel(), request.getMessage());

        String topic = producer.getTopicForPriority(request.getPriority());

        log.info("Notification request received | user=[{}] channel=[{}] priority=[{}] key=[{}]",
                request.getUserId(), request.getChannel(), request.getPriority(), idempotencyKey);

        // Persist initial log entry with PENDING status
        NotificationLog logEntry = NotificationLog.builder()
                .userId(request.getUserId())
                .channel(request.getChannel())
                .status(NotificationLog.Status.PENDING)
                .provider("PENDING")
                .message(request.getMessage())
                .idempotencyKey(idempotencyKey)
                .callbackUrl(request.getCallbackUrl())
                .build();
        notificationLogRepository.save(logEntry);

        // Publish to Kafka — consumer will process asynchronously
        producer.publish(request);
        metrics.incrementAccepted(request.getChannel().name(), request.getPriority().name());

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(NotificationResponse.builder()
                        .requestId(idempotencyKey)
                        .status("ACCEPTED")
                        .message("Notification queued successfully")
                        .topic(topic)
                        .build());
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get notification history for a user")
    public ResponseEntity<List<NotificationLog>> getHistory(@PathVariable String userId) {
        List<NotificationLog> logs = notificationLogRepository.findByUserId(userId);
        return ResponseEntity.ok(logs);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "Get all notifications by status (PENDING, SENT, FAILED)")
    public ResponseEntity<List<NotificationLog>> getByStatus(@PathVariable NotificationLog.Status status) {
        return ResponseEntity.ok(notificationLogRepository.findByStatus(status));
    }
}
