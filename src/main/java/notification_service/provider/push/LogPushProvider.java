package notification_service.provider.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Mock push provider for local development.
 * Active when firebase.enabled=false (the default).
 * Just prints the push content to the console — no real notification is sent.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "false", matchIfMissing = true)
public class LogPushProvider implements PushProvider {

    @Override
    public void send(String deviceToken, String title, String body) {
        log.info("========== [MOCK PUSH] ==========");
        log.info("  Token : {}...", deviceToken.substring(0, Math.min(deviceToken.length(), 12)));
        log.info("  Title : {}", title);
        log.info("  Body  : {}", body);
        log.info("=================================");
    }

    @Override
    public String getProviderName() {
        return "MOCK_PUSH";
    }
}
