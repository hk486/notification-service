package notification_service.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalTime;

/**
 * Request body for PUT /api/v1/preferences/{userId}/{channel}
 *
 * All fields are optional — omit dndStart/dndEnd to clear the DND window.
 *
 * Example:
 * {
 *   "enabled": true,
 *   "dndStart": "22:00",
 *   "dndEnd":   "08:00"
 * }
 */
@Data
public class UserPreferenceRequest {

    private boolean enabled = true;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime dndStart;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime dndEnd;
}
