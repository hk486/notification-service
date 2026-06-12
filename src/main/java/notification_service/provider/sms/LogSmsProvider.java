package notification_service.provider.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Mock SMS provider for local development.
 * Active when twilio.enabled=false (the default).
 * Just prints the SMS content to the console — no real message is sent.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "false", matchIfMissing = true)
public class LogSmsProvider implements SmsProvider {

    @Override
    public void send(String to, String body) {
        log.info("========== [MOCK SMS] ==========");
        log.info("  To   : {}", to);
        log.info("  Body : {}", body);
        log.info("================================");
    }

    @Override
    public String getProviderName() {
        return "MOCK_SMS";
    }
}
