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
 * Thin Kafka consumer for SMS channel.
 *
 * Responsibility: receive raw JSON from Kafka, filter by channel == SMS,
 * then hand off to NotificationDispatchService which handles:
 *   - Retry (up to 3 attempts with exponential backoff)
 *   - Circuit breaker (open after 3 consecutive failures)
 *   - Status update in DB (SENT or FAILED)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsConsumer {

    private final NotificationDispatchService dispatchService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {"${kafka.topics.high}", "${kafka.topics.medium}", "${kafka.topics.low}"},
            groupId = "sms-consumer-group"
    )
    public void consume(String rawMessage) {
        try {
            NotificationRequest request = objectMapper.readValue(rawMessage, NotificationRequest.class);

            if (request.getChannel() != NotificationLog.Channel.SMS) {
                return; // not SMS — EmailConsumer / PushConsumer handle the rest
            }

            log.info("SmsConsumer received | user=[{}] key=[{}]",
                    request.getUserId(), request.getIdempotencyKey());

            dispatchService.sendSms(request);

        } catch (Exception e) {
            log.error("SmsConsumer failed to deserialise message: {}", e.getMessage(), e);
        }
    }
}
