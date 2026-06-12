package notification_service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import notification_service.api.dto.ScheduleNotificationRequest;
import notification_service.model.ScheduledNotification;
import notification_service.service.ScheduledNotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduled")
@RequiredArgsConstructor
@Tag(name = "Scheduled Notifications", description = "Schedule notifications for future delivery")
public class ScheduledNotificationController {

    private final ScheduledNotificationService service;

    /**
     * Schedule a notification for future delivery.
     *
     * POST /api/v1/scheduled
     * Body: ScheduleNotificationRequest (same as a normal send, plus scheduledAt)
     *
     * Returns 202 with the persisted entity (including assigned id).
     */
    @PostMapping
    @Operation(summary = "Schedule a notification for a future time")
    public ResponseEntity<ScheduledNotification> schedule(
            @Valid @RequestBody ScheduleNotificationRequest request) {
        ScheduledNotification saved = service.schedule(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(saved);
    }

    /**
     * Cancel a pending scheduled notification before it fires.
     *
     * DELETE /api/v1/scheduled/{id}
     *
     * Returns 204 on success, 404 if not found, 409 if already dispatched/cancelled.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a pending scheduled notification")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * List all scheduled notifications (any status) for a user.
     *
     * GET /api/v1/scheduled/{userId}
     */
    @GetMapping("/{userId}")
    @Operation(summary = "List scheduled notifications for a user")
    public ResponseEntity<List<ScheduledNotification>> getByUser(@PathVariable String userId) {
        return ResponseEntity.ok(service.getByUser(userId));
    }

    // ── Exception handlers ────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", ex.getMessage()));
    }
}
