package notification_service.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.metrics.NotificationMetrics;
import notification_service.model.DlqMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Publishes failed notification envelopes to the Dead Letter Queue topic.
 *
 * Called by all three fallback methods in NotificationDispatchService
 * after Resilience4j retries are exhausted.
 *
 * In production the DlqConsumer on the other end would:
 *   - Trigger a PagerDuty / Slack / ops-email alert
 *   - Write to a dead-letter table for manual replay
 *   - Feed a monitoring dashboard
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationMetrics metrics;

    @Value("${kafka.topics.dlq}")
    private String dlqTopic;

    /**
     * Builds a DlqMessage envelope and publishes it to notification.dlq.
     *
     * @param idempotencyKey links back to the NotificationLog row
     * @param userId         intended recipient
     * @param channel        EMAIL / SMS / PUSH
     * @param failureReason  the exception message from the last attempt
     */
    public void publish(String idempotencyKey, String userId, String channel, String failureReason) {
        try {
            DlqMessage message = DlqMessage.builder()
                    .idempotencyKey(idempotencyKey)
                    .userId(userId)
                    .channel(channel)
                    .failureReason(failureReason)
                    .failedAt(LocalDateTime.now())
                    .build();

            String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(dlqTopic, idempotencyKey, payload);
            metrics.incrementDlqPublished(channel);

            log.warn("[DLQ] Published failed notification | user=[{}] channel=[{}] reason=[{}]",
                    userId, channel, failureReason);

        } catch (Exception e) {
            log.error("[DLQ] Failed to publish to DLQ topic | user=[{}] error=[{}]", userId, e.getMessage(), e);
        }
    }
}
