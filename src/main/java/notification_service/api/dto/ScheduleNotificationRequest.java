package notification_service.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import notification_service.model.NotificationLog;
import notification_service.model.ScheduledNotification;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Request body for POST /api/v1/scheduled
 *
 * Identical to NotificationRequest but adds:
 *  - scheduledAt  — must be a future instant
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleNotificationRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotNull(message = "channel is required")
    private NotificationLog.Channel channel;

    @NotNull(message = "priority is required")
    private ScheduledNotification.Priority priority;

    private String templateName;
    private Map<String, String> templateData;
    private String message;

    private String idempotencyKey;

    /**
     * When to deliver the notification — must be in the future.
     * Format: "yyyy-MM-dd'T'HH:mm:ss"
     */
    @NotNull(message = "scheduledAt is required")
    @Future(message = "scheduledAt must be a future date/time")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime scheduledAt;
}
