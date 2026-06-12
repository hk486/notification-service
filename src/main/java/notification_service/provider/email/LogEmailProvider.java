package notification_service.provider.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Local dev fallback — active when aws.ses.enabled=false (default).
 * Logs the email instead of sending it so you can test without AWS credentials.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "aws.ses.enabled", havingValue = "false", matchIfMissing = true)
public class LogEmailProvider implements EmailProvider {

    @Override
    public void send(String to, String subject, String body) {
        log.info("========== [MOCK EMAIL] ==========");
        log.info("  To      : {}", to);
        log.info("  Subject : {}", subject);
        log.info("  Body    : {}", body);
        log.info("==================================");
    }

    @Override
    public String getProviderName() {
        return "MOCK_EMAIL";
    }
}
