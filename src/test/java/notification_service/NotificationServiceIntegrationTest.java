package notification_service;

import notification_service.model.NotificationLog;
import notification_service.model.ScheduledNotification;
import notification_service.model.UserPreference;
import notification_service.repository.NotificationLogRepository;
import notification_service.repository.ScheduledNotificationRepository;
import notification_service.repository.UserPreferenceRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for all 14 days of features.
 *
 * Uses Testcontainers (MySQL, Kafka, Redis) — no external infrastructure needed.
 * Each @Nested class groups tests for one feature area and runs in @Order sequence
 * so earlier tests can set up state that later tests depend on.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationServiceIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired NotificationLogRepository logRepo;
    @Autowired ScheduledNotificationRepository scheduledRepo;
    @Autowired UserPreferenceRepository preferenceRepo;

    // ─────────────────────────────────────────────────────────────────────────
    // Days 1–7: Core API — send, idempotency, rate limiting
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("D1-7 | POST /notifications → 202 and persists PENDING log")
    void sendNotification_returns202_andPersistsLog() {
        HttpHeaders headers = jsonHeaders();
        String body = """
                {
                  "userId": "it-user-1",
                  "channel": "SMS",
                  "priority": "HIGH",
                  "templateName": "OTP",
                  "templateData": {"otp": "123456"},
                  "idempotencyKey": "it-key-001"
                }
                """;

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "ACCEPTED");
        assertThat(response.getBody()).containsEntry("requestId", "it-key-001");

        // Log row must exist
        assertThat(logRepo.findByIdempotencyKey("it-key-001")).isPresent();
    }

    @Test
    @Order(2)
    @DisplayName("D1-7 | Duplicate idempotency key → 409")
    void duplicateKey_returns409() {
        HttpHeaders headers = jsonHeaders();
        String body = """
                {
                  "userId": "it-user-1",
                  "channel": "SMS",
                  "priority": "HIGH",
                  "message": "duplicate test",
                  "idempotencyKey": "it-key-001"
                }
                """;

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @Order(3)
    @DisplayName("D1-7 | Rate limit — 6th request on same user+channel → 429")
    void rateLimitExceeded_returns429() {
        HttpHeaders headers = jsonHeaders();
        // Send 5 allowed requests (max-per-minute = 5)
        for (int i = 2; i <= 6; i++) {
            String body = """
                    {
                      "userId": "it-ratelimit-user",
                      "channel": "EMAIL",
                      "priority": "LOW",
                      "message": "rate limit probe %d",
                      "idempotencyKey": "it-rl-%d"
                    }
                    """.formatted(i, i);
            rest.exchange("/api/v1/notifications", HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);
        }

        // 6th must be rejected
        String body = """
                {
                  "userId": "it-ratelimit-user",
                  "channel": "EMAIL",
                  "priority": "LOW",
                  "message": "should be rejected",
                  "idempotencyKey": "it-rl-6"
                }
                """;
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @Order(4)
    @DisplayName("D1-7 | Missing both message and templateName → 400")
    void missingMessageAndTemplate_returns400() {
        HttpHeaders headers = jsonHeaders();
        String body = """
                {
                  "userId": "it-user-1",
                  "channel": "EMAIL",
                  "priority": "MEDIUM",
                  "idempotencyKey": "it-key-bad-001"
                }
                """;

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(5)
    @DisplayName("D1-7 | Unknown templateName → 404")
    void unknownTemplate_returns404() {
        HttpHeaders headers = jsonHeaders();
        String body = """
                {
                  "userId": "it-user-1",
                  "channel": "EMAIL",
                  "priority": "MEDIUM",
                  "templateName": "DOES_NOT_EXIST",
                  "idempotencyKey": "it-key-bad-002"
                }
                """;

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(6)
    @DisplayName("D1-7 | GET /notifications/{userId} returns history")
    void getHistory_returnsLogEntries() {
        ResponseEntity<NotificationLog[]> response = rest.getForEntity(
                "/api/v1/notifications/it-user-1", NotificationLog[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Day 8: Template engine
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("D8 | Template with {{tokens}} is accepted and queued")
    void templateWithTokens_accepted() {
        HttpHeaders headers = jsonHeaders();
        String body = """
                {
                  "userId": "it-tmpl-user",
                  "channel": "EMAIL",
                  "priority": "MEDIUM",
                  "templateName": "ORDER",
                  "templateData": {"orderId": "ORD-999", "status": "SHIPPED"},
                  "idempotencyKey": "it-tmpl-001"
                }
                """;

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @Order(11)
    @DisplayName("D8 | PUSH template (PROMO) on PUSH channel is accepted")
    void pushTemplate_accepted() {
        HttpHeaders headers = jsonHeaders();
        String body = """
                {
                  "userId": "it-push-user",
                  "channel": "PUSH",
                  "priority": "LOW",
                  "templateName": "PROMO",
                  "templateData": {"name": "Harmeet", "offer": "50% off"},
                  "idempotencyKey": "it-push-001"
                }
                """;

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Day 9: User preferences
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("D9 | PUT /preferences sets opt-out, notification → SKIPPED")
    void optOut_causesSkip() throws InterruptedException {
        // Opt the user out of SMS
        HttpHeaders headers = jsonHeaders();
        String prefBody = """
                {"enabled": false}
                """;
        ResponseEntity<UserPreference> prefResponse = rest.exchange(
                "/api/v1/preferences/it-pref-user/SMS", HttpMethod.PUT,
                new HttpEntity<>(prefBody, headers), UserPreference.class);
        assertThat(prefResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prefResponse.getBody().isEnabled()).isFalse();

        // Send an SMS
        String notifBody = """
                {
                  "userId": "it-pref-user",
                  "channel": "SMS",
                  "priority": "HIGH",
                  "message": "This should be skipped",
                  "idempotencyKey": "it-pref-001"
                }
                """;
        ResponseEntity<Map> notifResponse = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(notifBody, headers), Map.class);
        assertThat(notifResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Give Kafka consumer time to process
        Thread.sleep(3000);

        // Log must be SKIPPED
        assertThat(logRepo.findByIdempotencyKey("it-pref-001"))
                .isPresent()
                .hasValueSatisfying(log ->
                        assertThat(log.getStatus()).isEqualTo(NotificationLog.Status.SKIPPED));
    }

    @Test
    @Order(21)
    @DisplayName("D9 | GET /preferences/{userId} returns stored preferences")
    void getPreferences_returnsData() {
        ResponseEntity<UserPreference[]> response = rest.getForEntity(
                "/api/v1/preferences/it-pref-user", UserPreference[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).anyMatch(p ->
                p.getChannel() == NotificationLog.Channel.SMS && !p.isEnabled());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Day 12: Scheduled notifications
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("D12 | POST /scheduled → 202 with PENDING status")
    void scheduleNotification_returns202() {
        HttpHeaders headers = jsonHeaders();
        // Schedule 10 minutes from now (satisfies @Future)
        String scheduledAt = LocalDateTime.now().plusMinutes(10)
                .toString().replace("T", " ").substring(0, 19);

        String body = """
                {
                  "userId": "it-sched-user",
                  "channel": "EMAIL",
                  "priority": "MEDIUM",
                  "templateName": "WELCOME",
                  "templateData": {"name": "IT Test", "app": "TestApp"},
                  "idempotencyKey": "it-sched-001",
                  "scheduledAt": "%s"
                }
                """.formatted(scheduledAt);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/scheduled", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "PENDING");
        assertThat(response.getBody()).containsKey("id");
    }

    @Test
    @Order(31)
    @DisplayName("D12 | GET /scheduled/{userId} returns scheduled items")
    void getScheduled_returnsItems() {
        ResponseEntity<Map[]> response = rest.getForEntity(
                "/api/v1/scheduled/it-sched-user", Map[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()[0]).containsEntry("idempotencyKey", "it-sched-001");
    }

    @Test
    @Order(32)
    @DisplayName("D12 | DELETE /scheduled/{id} → 204, then repeat → 409")
    void cancelScheduled_thenRepeat_gives409() {
        // Find the scheduled ID
        ScheduledNotification sn = scheduledRepo
                .findByUserIdOrderByScheduledAtAsc("it-sched-user").get(0);
        Long id = sn.getId();

        // First cancel → 204
        ResponseEntity<Void> first = rest.exchange(
                "/api/v1/scheduled/" + id, HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Second cancel → 409
        ResponseEntity<Map> second = rest.exchange(
                "/api/v1/scheduled/" + id, HttpMethod.DELETE,
                HttpEntity.EMPTY, Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @Order(33)
    @DisplayName("D12 | Past-time via @Future validation → 400")
    void schedulePastTime_returns400() {
        HttpHeaders headers = jsonHeaders();
        String pastTime = LocalDateTime.now().minusHours(1)
                .toString().replace("T", " ").substring(0, 19);

        String body = """
                {
                  "userId": "it-sched-user",
                  "channel": "EMAIL",
                  "priority": "LOW",
                  "message": "too late",
                  "idempotencyKey": "it-sched-past-001",
                  "scheduledAt": "%s"
                }
                """.formatted(pastTime);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/scheduled", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Day 13: Webhook callback — URL is persisted on the log row
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("D13 | callbackUrl is stored in notification_logs")
    void callbackUrl_persistedOnLog() {
        HttpHeaders headers = jsonHeaders();
        String body = """
                {
                  "userId": "it-webhook-user",
                  "channel": "EMAIL",
                  "priority": "HIGH",
                  "templateName": "WELCOME",
                  "templateData": {"name": "IT", "app": "TestApp"},
                  "idempotencyKey": "it-wh-001",
                  "callbackUrl": "http://localhost:19999/callback"
                }
                """;

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(logRepo.findByIdempotencyKey("it-wh-001"))
                .isPresent()
                .hasValueSatisfying(log ->
                        assertThat(log.getCallbackUrl())
                                .isEqualTo("http://localhost:19999/callback"));
    }

    @Test
    @Order(41)
    @DisplayName("D13 | Invalid callbackUrl scheme → 400")
    void invalidCallbackUrl_returns400() {
        HttpHeaders headers = jsonHeaders();
        String body = """
                {
                  "userId": "it-webhook-user",
                  "channel": "EMAIL",
                  "priority": "HIGH",
                  "message": "test",
                  "idempotencyKey": "it-wh-bad-001",
                  "callbackUrl": "ftp://not-valid"
                }
                """;

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Day 14: Provider health indicator
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("D14 | /actuator/health exposes providers component with MOCK mode")
    void actuatorHealth_showsProviders() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertThat(components).containsKey("providers");

        @SuppressWarnings("unchecked")
        Map<String, Object> providers = (Map<String, Object>) components.get("providers");
        assertThat(providers).containsEntry("status", "UP");

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) providers.get("details");
        assertThat(details.get("email").toString()).contains("MOCK");
        assertThat(details.get("sms").toString()).contains("MOCK");
        assertThat(details.get("push").toString()).contains("MOCK");
        assertThat(details.get("mode").toString()).contains("MOCK");
    }

    @Test
    @Order(51)
    @DisplayName("D11 | /actuator/prometheus exposes notification metrics")
    void actuatorPrometheus_exposesMetrics() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("notifications_accepted_total");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
