package notification_service.provider.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

/**
 * Day 9: SendGrid Email Provider — fallback for AWS SES
 *
 * Sends emails via SendGrid API instead of AWS SES.
 *
 * Prerequisites:
 *   1. Create SendGrid account (sendgrid.com)
 *   2. Create an API key
 *   3. Set environment variable: SENDGRID_API_KEY=SG.xxx...
 *      OR add to application.yaml:
 *        sendgrid:
 *          api-key: SG.xxx...
 *
 * This provider is active when:
 *   - sendgrid.enabled=true
 *   - AWS SES fails (circuit breaker opens)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sendgrid.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SendGridEmailProvider implements EmailProvider {

    private final RestTemplate restTemplate;
    private final SendGridProperties sendGridProperties;

    @Override
    public void send(String toEmail, String subject, String body) {
        try {
            // Build SendGrid API request using REST
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + sendGridProperties.getApiKey());

            Map<String, Object> request = new HashMap<>();
            request.put("subject", subject);
            request.put("to_email", toEmail);
            request.put("html_content", body);
            request.put("from_email", "noreply@notification-service.com");
            request.put("from_name", "Notification Service");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            // Call SendGrid API
            String response = restTemplate.postForObject(
                    "https://api.sendgrid.com/v3/mail/send",
                    entity,
                    String.class
            );

            log.info("✉️ SendGrid email sent to {} (subject: {})", toEmail, subject);
        } catch (Exception e) {
            log.error("❌ SendGrid email send failed for {}: {}", toEmail, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getProviderName() {
        return "SENDGRID";
    }

    /**
     * SendGrid properties holder
     */
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "sendgrid")
    public static class SendGridProperties {
        private String apiKey;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
