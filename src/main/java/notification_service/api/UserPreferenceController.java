package notification_service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import notification_service.api.dto.UserPreferenceRequest;
import notification_service.model.NotificationLog;
import notification_service.model.UserPreference;
import notification_service.service.UserPreferenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/preferences")
@RequiredArgsConstructor
@Tag(name = "User Preferences", description = "Manage per-user, per-channel notification preferences and DND windows")
public class UserPreferenceController {

    private final UserPreferenceService userPreferenceService;

    @PutMapping("/{userId}/{channel}")
    @Operation(
            summary = "Set preference for a user + channel",
            description = "Creates or updates the notification preference. " +
                    "Set enabled=false to opt out. " +
                    "Set dndStart/dndEnd (HH:mm) to suppress delivery in that time window."
    )
    public ResponseEntity<UserPreference> upsert(
            @PathVariable String userId,
            @PathVariable NotificationLog.Channel channel,
            @RequestBody UserPreferenceRequest request) {

        UserPreference saved = userPreferenceService.upsert(
                userId,
                channel,
                request.isEnabled(),
                request.getDndStart(),
                request.getDndEnd()
        );
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get all preferences for a user")
    public ResponseEntity<List<UserPreference>> getAll(@PathVariable String userId) {
        return ResponseEntity.ok(userPreferenceService.getAll(userId));
    }
}
