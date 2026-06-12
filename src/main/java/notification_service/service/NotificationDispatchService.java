package notification_service.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.api.dto.NotificationRequest;
import notification_service.metrics.NotificationMetrics;
import notification_service.model.NotificationLog;
import notification_service.producer.DlqProducer;
import notification_service.provider.email.EmailProvider;
import notification_service.provider.push.PushProvider;
import notification_service.provider.sms.SmsProvider;
import notification_service.repository.NotificationLogRepository;
import org.springframework.stereotype.Service;

/**
 * Central dispatch layer between Kafka consumers and external notification providers.
 *
 * Every send() method has two Resilience4j decorators (outermost first):
 *
 *   @CircuitBreaker  — watches overall health; if too many failures happen,
 *                      it "trips the switch" and skips calling the provider
 *                      entirely for a cooldown window, running the fallback instead.
 *
 *   @Retry           — before counting a call as failed, it automatically retries
 *                      it up to 3 times with exponential backoff (500ms → 1s → 2s).
 *
 * Execution order for a single notification:
 *   CircuitBreaker check → Retry wrapper → actual provider.send()
 *
 * On exhausted retries / open circuit → fallbackMethod is called →
 *   logs the error + marks NotificationLog status as FAILED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final EmailProvider emailProvider;
    private final SmsProvider   smsProvider;
    private final PushProvider  pushProvider;
    private final NotificationLogRepository notificationLogRepository;
    private final TemplateService templateService;
    private final UserPreferenceService userPreferenceService;
    private final DlqProducer dlqProducer;
    private final NotificationMetrics metrics;
    private final WebhookCallbackService webhookCallbackService;

    // ─────────────────────────────────────────────────────────────────────────
    // EMAIL
    // ─────────────────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "email-cb", fallbackMethod = "emailFallback")
    @Retry(name = "email-retry")
    public void sendEmail(NotificationRequest request) {
        if (!userPreferenceService.isDeliveryAllowed(request.getUserId(), NotificationLog.Channel.EMAIL)) {
            updateStatus(request.getIdempotencyKey(), NotificationLog.Status.SKIPPED, "PREFERENCE");
            metrics.incrementSkipped("EMAIL");
            return;
        }
        long start = System.currentTimeMillis();
        String recipient = request.getUserId();
        String subject   = buildEmailSubject(request);
        String body      = resolveMessage(request);

        log.info("[EMAIL] Attempting send | user=[{}] attempt via provider=[{}]",
                recipient, emailProvider.getProviderName());

        emailProvider.send(recipient, subject, body);

        updateStatus(request.getIdempotencyKey(), NotificationLog.Status.SENT, emailProvider.getProviderName());
        metrics.incrementSent("EMAIL", emailProvider.getProviderName());
        metrics.recordSendDuration("EMAIL", start);
    }

    /**
     * Called automatically by Resilience4j when:
     *  - All retry attempts are exhausted, OR
     *  - The circuit breaker is OPEN (skipping straight to fallback)
     *
     * Must have the same parameters as sendEmail() + a Throwable at the end.
     */
    public void emailFallback(NotificationRequest request, Throwable t) {
        log.error("[EMAIL] All retries exhausted / circuit OPEN | user=[{}] error=[{}]",
                request.getUserId(), t.getMessage());
        updateStatus(request.getIdempotencyKey(), NotificationLog.Status.FAILED, "FAILED", t.getMessage());
        dlqProducer.publish(request.getIdempotencyKey(), request.getUserId(), "EMAIL", t.getMessage());
        metrics.incrementFailed("EMAIL");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMS
    // ─────────────────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "sms-cb", fallbackMethod = "smsFallback")
    @Retry(name = "sms-retry")
    public void sendSms(NotificationRequest request) {
        if (!userPreferenceService.isDeliveryAllowed(request.getUserId(), NotificationLog.Channel.SMS)) {
            updateStatus(request.getIdempotencyKey(), NotificationLog.Status.SKIPPED, "PREFERENCE");
            metrics.incrementSkipped("SMS");
            return;
        }
        long start = System.currentTimeMillis();
        String body = resolveMessage(request);

        log.info("[SMS] Attempting send | user=[{}] via provider=[{}]",
                request.getUserId(), smsProvider.getProviderName());

        smsProvider.send(request.getUserId(), body);

        updateStatus(request.getIdempotencyKey(), NotificationLog.Status.SENT, smsProvider.getProviderName());
        metrics.incrementSent("SMS", smsProvider.getProviderName());
        metrics.recordSendDuration("SMS", start);
    }

    public void smsFallback(NotificationRequest request, Throwable t) {
        log.error("[SMS] All retries exhausted / circuit OPEN | user=[{}] error=[{}]",
                request.getUserId(), t.getMessage());
        updateStatus(request.getIdempotencyKey(), NotificationLog.Status.FAILED, "FAILED", t.getMessage());
        dlqProducer.publish(request.getIdempotencyKey(), request.getUserId(), "SMS", t.getMessage());
        metrics.incrementFailed("SMS");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUSH
    // ─────────────────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "push-cb", fallbackMethod = "pushFallback")
    @Retry(name = "push-retry")
    public void sendPush(NotificationRequest request) {
        if (!userPreferenceService.isDeliveryAllowed(request.getUserId(), NotificationLog.Channel.PUSH)) {
            updateStatus(request.getIdempotencyKey(), NotificationLog.Status.SKIPPED, "PREFERENCE");
            metrics.incrementSkipped("PUSH");
            return;
        }
        long start = System.currentTimeMillis();
        String title = buildPushTitle(request);
        String body  = resolveMessage(request);

        log.info("[PUSH] Attempting send | user=[{}] via provider=[{}]",
                request.getUserId(), pushProvider.getProviderName());

        pushProvider.send(request.getUserId(), title, body);

        updateStatus(request.getIdempotencyKey(), NotificationLog.Status.SENT, pushProvider.getProviderName());
        metrics.incrementSent("PUSH", pushProvider.getProviderName());
        metrics.recordSendDuration("PUSH", start);
    }

    public void pushFallback(NotificationRequest request, Throwable t) {
        log.error("[PUSH] All retries exhausted / circuit OPEN | user=[{}] error=[{}]",
                request.getUserId(), t.getMessage());
        updateStatus(request.getIdempotencyKey(), NotificationLog.Status.FAILED, "FAILED", t.getMessage());
        dlqProducer.publish(request.getIdempotencyKey(), request.getUserId(), "PUSH", t.getMessage());
        metrics.incrementFailed("PUSH");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updateStatus(String idempotencyKey, NotificationLog.Status status, String provider) {
        updateStatus(idempotencyKey, status, provider, null);
    }

    private void updateStatus(String idempotencyKey, NotificationLog.Status status,
                               String provider, String failureReason) {
        if (idempotencyKey == null) return;
        notificationLogRepository.findByIdempotencyKey(idempotencyKey)
                .ifPresent(entry -> {
                    entry.setStatus(status);
                    entry.setProvider(provider);
                    if (failureReason != null) {
                        entry.setFailureReason(failureReason);
                    }
                    NotificationLog saved = notificationLogRepository.save(entry);
                    log.info("NotificationLog updated | key=[{}] status=[{}] provider=[{}]",
                            idempotencyKey, status, provider);
                    // Fire webhook for terminal states
                    if (status == NotificationLog.Status.SENT || status == NotificationLog.Status.FAILED) {
                        webhookCallbackService.fireAndForget(saved);
                    }
                });
    }

    /**
     * Delegates to TemplateService to get the final message body.
     * Throws IllegalArgumentException (→ 400) if neither message nor templateName is set.
     * Throws TemplateNotFoundException (→ 404) if the named template doesn't exist in DB.
     */
    private String resolveMessage(NotificationRequest request) {
        return templateService.resolve(
                request.getTemplateName(),
                request.getChannel(),
                request.getTemplateData(),
                request.getMessage()
        );
    }

    private String buildEmailSubject(NotificationRequest request) {
        return switch (request.getPriority()) {
            case HIGH   -> "[URGENT] Notification";
            case MEDIUM -> "Update from Notification Service";
            case LOW    -> "You have a new message";
        };
    }

    private String buildPushTitle(NotificationRequest request) {
        return switch (request.getPriority()) {
            case HIGH   -> "⚠️ Urgent Notification";
            case MEDIUM -> "📢 New Update";
            case LOW    -> "💬 New Message";
        };
    }
}
