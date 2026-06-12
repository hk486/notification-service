package notification_service.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Persisted record of a notification that should be delivered at a future time.
 *
 * Lifecycle:
 *   PENDING     → created, waiting for scheduledAt to arrive
 *   DISPATCHED  → scheduler published it to Kafka; delivery handled by normal pipeline
 *   CANCELLED   → caller cancelled it before dispatch
 */
@Entity
@Table(name = "scheduled_notifications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationLog.Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Column(name = "template_name")
    private String templateName;

    /** JSON-serialised Map<String,String> — deserialized by the service before dispatch. */
    @Column(name = "template_data", columnDefinition = "TEXT")
    private String templateData;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    /** When the notification should be published to Kafka. */
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum Priority { HIGH, MEDIUM, LOW }

    public enum Status { PENDING, DISPATCHED, CANCELLED }
}
