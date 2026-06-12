package notification_service.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Envelope published to notification.dlq when a notification delivery
 * fails after all Resilience4j retries are exhausted.
 *
 * Preserved in Kafka so the failure can be investigated, replayed,
 * or trigger an ops alert (PagerDuty, Slack, etc.).
 *
 * Fields:
 *   idempotencyKey — links back to the NotificationLog row in MySQL
 *   userId         — who the notification was for
 *   channel        — EMAIL / SMS / PUSH
 *   failureReason  — the exception message from the last failed attempt
 *   failedAt       — when the final failure occurred
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DlqMessage {

    private String idempotencyKey;
    private String userId;
    private String channel;
    private String failureReason;
    private LocalDateTime failedAt;
}
