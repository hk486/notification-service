package notification_service.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Day 9: SendGrid Configuration
 *
 * Configures SendGrid when sendgrid.enabled=true and API key is provided.
 *
 * Configuration in application.yaml:
 *   sendgrid:
 *     enabled: true                          # set true to enable SendGrid
 *     api-key: ${SENDGRID_API_KEY}           # SendGrid API key from environment
 */
@Configuration
@ConditionalOnProperty(name = "sendgrid.enabled", havingValue = "true")
@EnableConfigurationProperties(SendGridConfig.SendGridProperties.class)
public class SendGridConfig {

    /**
     * SendGrid properties binding
     */
    @ConfigurationProperties(prefix = "sendgrid")
    public static class SendGridProperties {
        private boolean enabled = false;
        private String apiKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}

