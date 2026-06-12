package notification_service.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import notification_service.api.dto.NotificationRequest;
import notification_service.model.NotificationLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationProducerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private NotificationProducer producer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(producer, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(producer, "highTopic",   "notification.high");
        ReflectionTestUtils.setField(producer, "mediumTopic", "notification.medium");
        ReflectionTestUtils.setField(producer, "lowTopic",    "notification.low");
    }

    private void mockKafkaSend() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.complete(mock(SendResult.class));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);
    }

    @Test
    void shouldPublishToHighTopicWhenPriorityIsHigh() {
        mockKafkaSend();
        NotificationRequest request = NotificationRequest.builder()
                .userId("user-123")
                .channel(NotificationLog.Channel.SMS)
                .priority(NotificationRequest.Priority.HIGH)
                .message("Your OTP is 456789")
                .idempotencyKey("key-001")
                .build();

        producer.publish(request);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), eq("user-123"), anyString());
        assertThat(topicCaptor.getValue()).isEqualTo("notification.high");
    }

    @Test
    void shouldPublishToMediumTopicWhenPriorityIsMedium() {
        mockKafkaSend();
        NotificationRequest request = NotificationRequest.builder()
                .userId("user-456")
                .channel(NotificationLog.Channel.EMAIL)
                .priority(NotificationRequest.Priority.MEDIUM)
                .message("Your order has been shipped")
                .build();

        producer.publish(request);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), eq("user-456"), anyString());
        assertThat(topicCaptor.getValue()).isEqualTo("notification.medium");
    }

    @Test
    void shouldPublishToLowTopicWhenPriorityIsLow() {
        mockKafkaSend();
        NotificationRequest request = NotificationRequest.builder()
                .userId("user-789")
                .channel(NotificationLog.Channel.PUSH)
                .priority(NotificationRequest.Priority.LOW)
                .message("Check out our new deals!")
                .build();

        producer.publish(request);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), eq("user-789"), anyString());
        assertThat(topicCaptor.getValue()).isEqualTo("notification.low");
    }

    @Test
    void shouldResolveCorrectTopicForEachPriority() {
        assertThat(producer.getTopicForPriority(NotificationRequest.Priority.HIGH))
                .isEqualTo("notification.high");
        assertThat(producer.getTopicForPriority(NotificationRequest.Priority.MEDIUM))
                .isEqualTo("notification.medium");
        assertThat(producer.getTopicForPriority(NotificationRequest.Priority.LOW))
                .isEqualTo("notification.low");
    }
}
