package notification_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.model.NotificationLog;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fires an HTTP POST to the caller's registered callback URL after a notification
 * reaches a terminal state (SENT or FAILED).
 *
 * Runs entirely on Spring's async executor (@Async) so it never blocks the
 * Kafka consumer thread. Retries up to 3 times with exponential back-off
 * (1 s, 2 s) before giving up and logging the failure.
 *
 * Payload sent to the callback URL:
 * {
 *   "idempotencyKey": "...",
 *   "userId":         "...",
 *   "channel":        "EMAIL",
 *   "status":         "SENT",
 *   "provider":       "MOCK_EMAIL",
 *   "failureReason":  null,
 *   "timestamp":      "2026-05-25T22:00:00"
 * }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookCallbackService {

    private final RestTemplate restTemplate;

    private static final int MAX_ATTEMPTS = 3;

    @Async
    public void fireAndForget(NotificationLog notificationLog) {
        String callbackUrl = notificationLog.getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotencyKey", notificationLog.getIdempotencyKey());
        payload.put("userId",         notificationLog.getUserId());
        payload.put("channel",        notificationLog.getChannel());
        payload.put("status",         notificationLog.getStatus());
        payload.put("provider",       notificationLog.getProvider());
        payload.put("failureReason",  notificationLog.getFailureReason());
        payload.put("timestamp",      LocalDateTime.now().toString());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restTemplate.postForEntity(callbackUrl, entity, Void.class);
                log.info("[WEBHOOK] Delivered | key=[{}] url=[{}] attempt=[{}]",
                        notificationLog.getIdempotencyKey(), callbackUrl, attempt);
                return;
            } catch (Exception e) {
                log.warn("[WEBHOOK] Attempt {}/{} failed | key=[{}] url=[{}] error=[{}]",
                        attempt, MAX_ATTEMPTS, notificationLog.getIdempotencyKey(), callbackUrl, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(1000L * attempt);   // 1 s, then 2 s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        log.error("[WEBHOOK] All {} attempts exhausted | key=[{}] url=[{}]",
                MAX_ATTEMPTS, notificationLog.getIdempotencyKey(), callbackUrl);
    }
}
