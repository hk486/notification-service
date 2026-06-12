package notification_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.model.NotificationLog;
import notification_service.model.UserPreference;
import notification_service.repository.UserPreferenceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

/**
 * Manages per-user, per-channel notification preferences.
 *
 * Two delivery guards:
 *   1. enabled=false → channel permanently opted out
 *   2. DND window    → delivery silently suppressed between dndStart and dndEnd
 *
 * DND handles overnight windows: if dndStart > dndEnd (e.g. 22:00 → 08:00)
 * the check wraps midnight correctly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private final UserPreferenceRepository userPreferenceRepository;

    /**
     * Returns true when it is safe to deliver — channel is enabled AND
     * the current time is outside the user's DND window.
     */
    public boolean isDeliveryAllowed(String userId, NotificationLog.Channel channel) {
        return userPreferenceRepository
                .findByUserIdAndChannel(userId, channel)
                .map(pref -> {
                    if (!pref.isEnabled()) {
                        log.info("[PREF] Delivery blocked — channel opted out | user=[{}] channel=[{}]",
                                userId, channel);
                        return false;
                    }
                    if (isInDndWindow(pref)) {
                        log.info("[PREF] Delivery blocked — DND window active | user=[{}] channel=[{}] dnd=[{} – {}]",
                                userId, channel, pref.getDndStart(), pref.getDndEnd());
                        return false;
                    }
                    return true;
                })
                .orElse(true); // no preference row = deliver by default
    }

    /**
     * Creates or updates the preference row for the given user + channel.
     */
    public UserPreference upsert(String userId,
                                  NotificationLog.Channel channel,
                                  boolean enabled,
                                  LocalTime dndStart,
                                  LocalTime dndEnd) {
        UserPreference pref = userPreferenceRepository
                .findByUserIdAndChannel(userId, channel)
                .orElseGet(() -> UserPreference.builder()
                        .userId(userId)
                        .channel(channel)
                        .build());

        pref.setEnabled(enabled);
        pref.setDndStart(dndStart);
        pref.setDndEnd(dndEnd);

        UserPreference saved = userPreferenceRepository.save(pref);
        log.info("[PREF] Saved | user=[{}] channel=[{}] enabled=[{}] dnd=[{} – {}]",
                userId, channel, enabled, dndStart, dndEnd);
        return saved;
    }

    /**
     * Returns all stored preferences for a user (one row per channel that was ever set).
     */
    public List<UserPreference> getAll(String userId) {
        return userPreferenceRepository.findByUserId(userId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isInDndWindow(UserPreference pref) {
        if (pref.getDndStart() == null || pref.getDndEnd() == null) {
            return false;
        }
        LocalTime now = LocalTime.now();
        LocalTime start = pref.getDndStart();
        LocalTime end   = pref.getDndEnd();

        // Same-day window (e.g. 09:00 → 17:00)
        if (!start.isAfter(end)) {
            return !now.isBefore(start) && !now.isAfter(end);
        }
        // Overnight window (e.g. 22:00 → 08:00)
        return !now.isBefore(start) || !now.isAfter(end);
    }
}
