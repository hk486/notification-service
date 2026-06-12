package notification_service.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.api.dto.NotificationRequest;
import notification_service.model.NotificationLog;
import notification_service.service.NotificationDispatchService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Thin Kafka consumer for EMAIL channel.
 *
 * Responsibility: receive raw JSON from Kafka, filter by channel == EMAIL,
 * then hand off to NotificationDispatchService which handles:
 *   - Retry (up to 3 attempts with exponential backoff)
 *   - Circuit breaker (open after 3 consecutive failures)
 *   - Status update in DB (SENT or FAILED)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailConsumer {

    private final NotificationDispatchService dispatchService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {"${kafka.topics.high}", "${kafka.topics.medium}", "${kafka.topics.low}"},
            groupId = "email-consumer-group"
    )
    public void consume(String rawMessage) {
        try {
            NotificationRequest request = objectMapper.readValue(rawMessage, NotificationRequest.class);

            if (request.getChannel() != NotificationLog.Channel.EMAIL) {
                return; // not EMAIL — SmsConsumer / PushConsumer handle the rest
            }

            log.info("EmailConsumer received | user=[{}] key=[{}]",
                    request.getUserId(), request.getIdempotencyKey());

            dispatchService.sendEmail(request);

        } catch (Exception e) {
            log.error("EmailConsumer failed to deserialise message: {}", e.getMessage(), e);
        }
    }
}
