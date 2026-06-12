package notification_service.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import notification_service.model.NotificationLog;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotNull(message = "channel is required")
    private NotificationLog.Channel channel;

    @NotNull(message = "priority is required")
    private Priority priority;

    // Either provide a templateName + data map...
    private String templateName;
    private Map<String, String> templateData;

    // ...or provide a direct message body
    private String message;

    // Unique key to prevent duplicate sends
    private String idempotencyKey;

    // Optional: if set, the service POSTs delivery result to this URL after SENT/FAILED
    @Pattern(regexp = "https?://.+", message = "callbackUrl must be a valid HTTP or HTTPS URL")
    private String callbackUrl;

    public enum Priority {
        HIGH,   // → notification.high   (OTP, Alerts, Transactions)
        MEDIUM, // → notification.medium (Order updates, Reminders)
        LOW     // → notification.low    (Marketing, Promotions)
    }
}
