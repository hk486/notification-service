package notification_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.api.dto.NotificationRequest;
import notification_service.api.dto.ScheduleNotificationRequest;
import notification_service.model.NotificationLog;
import notification_service.model.ScheduledNotification;
import notification_service.producer.NotificationProducer;
import notification_service.repository.NotificationLogRepository;
import notification_service.repository.ScheduledNotificationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the lifecycle of scheduled (future-time) notifications.
 *
 * Two responsibilities:
 *  1. schedule() — persist the intent to send at a future time
 *  2. dispatchDue() — runs every 60 s; publishes overdue PENDING rows to Kafka
 *     and marks them DISPATCHED so they are never sent twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledNotificationService {

    private final ScheduledNotificationRepository repository;
    private final NotificationLogRepository notificationLogRepository;
    private final NotificationProducer producer;
    private final TemplateService templateService;
    private final ObjectMapper objectMapper;

    // ── Schedule ──────────────────────────────────────────────────────────────

    /**
     * Persists the scheduling request.
     * Returns the saved entity (including the generated id) for the HTTP response.
     */
    @Transactional
    public ScheduledNotification schedule(ScheduleNotificationRequest req) {
        // Pre-validate template/message so the caller gets an immediate 400/404
        templateService.validate(req.getTemplateName(), req.getChannel(), req.getMessage());

        String key = (req.getIdempotencyKey() == null || req.getIdempotencyKey().isBlank())
                ? UUID.randomUUID().toString()
                : req.getIdempotencyKey();

        String templateDataJson = serializeTemplateData(req.getTemplateData());

        ScheduledNotification entity = ScheduledNotification.builder()
                .userId(req.getUserId())
                .channel(req.getChannel())
                .priority(req.getPriority())
                .templateName(req.getTemplateName())
                .templateData(templateDataJson)
                .message(req.getMessage())
                .idempotencyKey(key)
                .scheduledAt(req.getScheduledAt())
                .build();

        ScheduledNotification saved = repository.save(entity);
        log.info("[SCHEDULER] Notification scheduled | id=[{}] user=[{}] channel=[{}] at=[{}]",
                saved.getId(), saved.getUserId(), saved.getChannel(), saved.getScheduledAt());
        return saved;
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * Cancels a PENDING scheduled notification.
     * Throws if the notification is already dispatched or cancelled.
     */
    @Transactional
    public void cancel(Long id) {
        ScheduledNotification sn = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Scheduled notification not found: " + id));

        if (sn.getStatus() != ScheduledNotification.Status.PENDING) {
            throw new IllegalStateException(
                    "Cannot cancel — notification is already " + sn.getStatus());
        }
        sn.setStatus(ScheduledNotification.Status.CANCELLED);
        repository.save(sn);
        log.info("[SCHEDULER] Notification cancelled | id=[{}]", id);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    public List<ScheduledNotification> getByUser(String userId) {
        return repository.findByUserIdOrderByScheduledAtAsc(userId);
    }

    // ── Background dispatcher ─────────────────────────────────────────────────

    /**
     * Runs every 60 seconds.
     * Finds all PENDING rows whose scheduledAt has passed, publishes each to Kafka,
     * then marks them DISPATCHED. The normal NotificationDispatchService pipeline
     * handles actual delivery (idempotency, rate-limit, circuit breaker, DLQ — all work
     * unchanged because the same NotificationRequest shape is used).
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void dispatchDue() {
        List<ScheduledNotification> due = repository.findDueNotifications(LocalDateTime.now());
        if (due.isEmpty()) return;

        log.info("[SCHEDULER] Dispatching {} due notification(s)", due.size());

        for (ScheduledNotification sn : due) {
            try {
                NotificationRequest req = toNotificationRequest(sn);

                // Create PENDING log row so the consumer pipeline can update it to SENT/FAILED
                NotificationLog logEntry = NotificationLog.builder()
                        .userId(sn.getUserId())
                        .channel(sn.getChannel())
                        .status(NotificationLog.Status.PENDING)
                        .provider("SCHEDULED")
                        .message(sn.getMessage())
                        .idempotencyKey(sn.getIdempotencyKey())
                        .build();
                notificationLogRepository.save(logEntry);

                producer.publish(req);

                sn.setStatus(ScheduledNotification.Status.DISPATCHED);
                sn.setDispatchedAt(LocalDateTime.now());
                repository.save(sn);

                log.info("[SCHEDULER] Dispatched | id=[{}] user=[{}] channel=[{}]",
                        sn.getId(), sn.getUserId(), sn.getChannel());
            } catch (Exception e) {
                // Log but don't rethrow — let the other items in the batch still dispatch.
                log.error("[SCHEDULER] Failed to dispatch id=[{}] | error=[{}]",
                        sn.getId(), e.getMessage(), e);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private NotificationRequest toNotificationRequest(ScheduledNotification sn) {
        Map<String, String> templateData = deserializeTemplateData(sn.getTemplateData());

        // Map ScheduledNotification.Priority → NotificationRequest.Priority
        NotificationRequest.Priority priority =
                NotificationRequest.Priority.valueOf(sn.getPriority().name());

        return NotificationRequest.builder()
                .userId(sn.getUserId())
                .channel(sn.getChannel())
                .priority(priority)
                .templateName(sn.getTemplateName())
                .templateData(templateData)
                .message(sn.getMessage())
                .idempotencyKey(sn.getIdempotencyKey())
                .build();
    }

    private String serializeTemplateData(Map<String, String> data) {
        if (data == null || data.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("[SCHEDULER] Could not serialize templateData: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> deserializeTemplateData(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[SCHEDULER] Could not deserialize templateData: {}", e.getMessage());
            return null;
        }
    }
}
