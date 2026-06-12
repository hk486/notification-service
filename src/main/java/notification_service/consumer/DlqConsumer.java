package notification_service.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.model.DlqMessage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Consumes failed notification envelopes from the Dead Letter Queue topic.
 *
 * Every message arriving here represents a notification that could NOT be
 * delivered after all Resilience4j retries were exhausted.
 *
 * Current behaviour: log at ERROR so it appears in monitoring dashboards.
 *
 * Production extensions to add here:
 *   - Send alert to PagerDuty / Slack / ops email
 *   - Write to a dead_letter_log table for manual replay UI
 *   - Increment a custom metric (micrometer counter)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${kafka.topics.dlq}",
            groupId = "dlq-consumer-group"
    )
    public void consume(String rawMessage) {
        try {
            DlqMessage message = objectMapper.readValue(rawMessage, DlqMessage.class);

            log.error("""
                    [DLQ] *** DELIVERY FAILURE — ACTION REQUIRED ***
                      idempotencyKey : {}
                      userId         : {}
                      channel        : {}
                      failureReason  : {}
                      failedAt       : {}""",
                    message.getIdempotencyKey(),
                    message.getUserId(),
                    message.getChannel(),
                    message.getFailureReason(),
                    message.getFailedAt());

        } catch (Exception e) {
            log.error("[DLQ] Failed to deserialise DLQ message: {}", e.getMessage(), e);
        }
    }
}
