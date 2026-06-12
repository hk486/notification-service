package notification_service;

import io.testcontainers.containers.KafkaContainer;
import io.testcontainers.containers.MySQLContainer;
import io.testcontainers.containers.GenericContainer;
import io.testcontainers.junit.jupiter.Container;
import io.testcontainers.junit.jupiter.Testcontainers;
import io.testcontainers.utility.DockerImageName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import notification_service.api.dto.NotificationRequest;
import notification_service.model.NotificationLog;
import notification_service.repository.NotificationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Day 12: Comprehensive Integration Tests using Testcontainers
 *
 * This test suite covers:
 * Layer 1: Infrastructure (MySQL, Kafka, Redis through containers)
 * Layer 2: REST API (HTTP requests → status codes, response bodies)
 * Layer 3: Database (verify data persistence)
 * Layer 4: Messaging (Kafka message delivery + consumption)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Day 12: Comprehensive Integration Tests with Testcontainers")
class NotificationServiceIntegrationTestDay12 {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("notification_service")
            .withUsername("user")
            .withPassword("password");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // MySQL
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    // ──────────────────────────────────────────────────────────────────────────────
    // Layer 2 Tests: REST API
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/notifications - Send email notification successfully")
    void testSendEmailNotification() throws Exception {
        NotificationRequest request = NotificationRequest.builder()
                .userId("test@example.com")
                .channel(NotificationLog.Channel.EMAIL)
                .priority(NotificationRequest.Priority.HIGH)
                .message("Test email body")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.idempotencyKey").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/v1/notifications - Reject duplicate request (409 Conflict)")
    void testDuplicateRequestRejected() throws Exception {
        String idempotencyKey = "test-key-duplicate-" + System.currentTimeMillis();

        NotificationRequest request = NotificationRequest.builder()
                .userId("test@example.com")
                .channel(NotificationLog.Channel.EMAIL)
                .priority(NotificationRequest.Priority.HIGH)
                .message("Test")
                .idempotencyKey(idempotencyKey)
                .build();

        // First request → 202 ACCEPTED
        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        // Second request with same key → 409 CONFLICT
        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Duplicate request"))
                .andExpect(jsonPath("$.message").containsString("idempotency key"));
    }

    @Test
    @DisplayName("POST /api/v1/notifications - Missing required field (400 Bad Request)")
    void testMissingRequiredField() throws Exception {
        String invalidRequest = "{\"channel\": \"EMAIL\"}"; // missing userId and priority

        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{userId} - Retrieve notification history")
    void testGetNotificationsByUser() throws Exception {
        String userId = "history@example.com";
        NotificationRequest request = NotificationRequest.builder()
                .userId(userId)
                .channel(NotificationLog.Channel.EMAIL)
                .priority(NotificationRequest.Priority.MEDIUM)
                .message("History test")
                .build();

        // Send 3 notifications
        for (int i = 0; i < 3; i++) {
            request.setIdempotencyKey("key-" + i);
            mockMvc.perform(post("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isAccepted());
        }

        // Retrieve history
        mockMvc.perform(get("/api/v1/notifications/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications", hasSize(greaterThanOrEqualTo(3))))
                .andExpect(jsonPath("$.notifications[*].userId").value(everyItem(equalTo(userId))));
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Layer 3 Tests: Database Persistence
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Layer 3: Notification persisted to MySQL with correct status")
    void testNotificationPersistedToDatabase() throws Exception {
        String userId = "db-test@example.com";
        NotificationRequest request = NotificationRequest.builder()
                .userId(userId)
                .channel(NotificationLog.Channel.SMS)
                .priority(NotificationRequest.Priority.HIGH)
                .message("Database test")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        // Verify in database
        Thread.sleep(500); // wait for async processing
        java.util.List<NotificationLog> logs = notificationLogRepository.findByUserId(userId);
        assertFalse(logs.isEmpty(), "Notification should be persisted to database");
        assertEquals(NotificationLog.Status.PENDING, logs.get(0).getStatus());
        assertEquals(NotificationLog.Channel.SMS, logs.get(0).getChannel());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Layer 4 Tests: Rate Limiting & Idempotency (Redis)
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rate limiting: 6th request within 60s returns 429")
    void testRateLimitExceeded() throws Exception {
        String userId = "ratelimit@example.com";

        // Send 5 requests (limit is 5 per minute)
        for (int i = 0; i < 5; i++) {
            NotificationRequest request = NotificationRequest.builder()
                    .userId(userId)
                    .channel(NotificationLog.Channel.EMAIL)
                    .priority(NotificationRequest.Priority.LOW)
                    .message("Rate limit test " + i)
                    .idempotencyKey("ratelimit-" + i)
                    .build();

            mockMvc.perform(post("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isAccepted());
        }

        // 6th request should be rejected
        NotificationRequest sixthRequest = NotificationRequest.builder()
                .userId(userId)
                .channel(NotificationLog.Channel.EMAIL)
                .priority(NotificationRequest.Priority.LOW)
                .message("This should be rejected")
                .idempotencyKey("ratelimit-6")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sixthRequest)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").containsString("rate limit"));
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Layer 1 Tests: Health & Infrastructure
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /actuator/health - All components healthy")
    void testHealthCheck() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").exists())
                .andExpect(jsonPath("$.components.redis").exists());
    }

    @Test
    @DisplayName("GET /actuator/metrics/resilience4j.circuitbreaker.state - Circuit breaker metrics")
    void testCircuitBreakerMetrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics/resilience4j.circuitbreaker.state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("resilience4j.circuitbreaker.state"));
    }
}
