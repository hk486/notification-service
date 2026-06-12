package notification_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${kafka.topics.high}")
    private String highTopic;

    @Value("${kafka.topics.medium}")
    private String mediumTopic;

    @Value("${kafka.topics.low}")
    private String lowTopic;

    @Value("${kafka.topics.dlq}")
    private String dlqTopic;

    // notification.high → OTP, Alerts, Transactions
    @Bean
    public NewTopic highPriorityTopic() {
        return TopicBuilder.name(highTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    // notification.medium → Order updates, Reminders
    @Bean
    public NewTopic mediumPriorityTopic() {
        return TopicBuilder.name(mediumTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    // notification.low → Marketing, Promotions
    @Bean
    public NewTopic lowPriorityTopic() {
        return TopicBuilder.name(lowTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    // notification.dlq → Failed messages after all retries exhausted
    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(dlqTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
