package notification_service.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.api.dto.NotificationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topics.high}")
    private String highTopic;

    @Value("${kafka.topics.medium}")
    private String mediumTopic;

    @Value("${kafka.topics.low}")
    private String lowTopic;

    public void publish(NotificationRequest request) {
        String topic = resolveTopic(request.getPriority());
        String payload = serialize(request);

        // Use userId as the Kafka key so all messages for a user go to same partition
        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(topic, request.getUserId(), payload);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish notification to topic [{}] for user [{}]: {}",
                        topic, request.getUserId(), ex.getMessage());
            } else {
                log.info("Published notification to topic [{}] partition [{}] offset [{}] for user [{}]",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        request.getUserId());
            }
        });
    }

    private String resolveTopic(NotificationRequest.Priority priority) {
        return switch (priority) {
            case HIGH   -> highTopic;
            case MEDIUM -> mediumTopic;
            case LOW    -> lowTopic;
        };
    }

    public String getTopicForPriority(NotificationRequest.Priority priority) {
        return resolveTopic(priority);
    }

    private String serialize(NotificationRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize NotificationRequest", e);
        }
    }
}
