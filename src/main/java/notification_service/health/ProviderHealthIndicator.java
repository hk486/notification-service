package notification_service.health;

import lombok.RequiredArgsConstructor;
import notification_service.provider.email.EmailProvider;
import notification_service.provider.push.PushProvider;
import notification_service.provider.sms.SmsProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom actuator health indicator that surfaces which concrete provider
 * is active for each notification channel.
 *
 * Visible at: GET /actuator/health  (under the "providers" key)
 *
 * Example output when all mocks are active (dev):
 * {
 *   "status": "UP",
 *   "components": {
 *     "providers": {
 *       "status": "UP",
 *       "details": {
 *         "email":  "MOCK_EMAIL",
 *         "sms":    "MOCK_SMS",
 *         "push":   "MOCK_PUSH",
 *         "mode":   "MOCK — no real messages are being sent"
 *       }
 *     }
 *   }
 * }
 *
 * Example output when all real providers are active (prod):
 * {
 *   "details": {
 *     "email":  "AWS_SES",
 *     "sms":    "TWILIO",
 *     "push":   "FIREBASE_FCM",
 *     "mode":   "LIVE — real messages are being sent"
 *   }
 * }
 */
@Component("providers")
@RequiredArgsConstructor
public class ProviderHealthIndicator implements HealthIndicator {

    private final EmailProvider emailProvider;
    private final SmsProvider   smsProvider;
    private final PushProvider  pushProvider;

    @Override
    public Health health() {
        String emailName = emailProvider.getProviderName();
        String smsName   = smsProvider.getProviderName();
        String pushName  = pushProvider.getProviderName();

        boolean allMock = emailName.startsWith("MOCK") && smsName.startsWith("MOCK") && pushName.startsWith("MOCK");
        boolean allLive = !emailName.startsWith("MOCK") && !smsName.startsWith("MOCK") && !pushName.startsWith("MOCK");

        String mode;
        if (allMock) {
            mode = "MOCK — no real messages are being sent";
        } else if (allLive) {
            mode = "LIVE — real messages are being sent";
        } else {
            mode = "MIXED — some channels use real providers";
        }

        return Health.up()
                .withDetail("email", emailName)
                .withDetail("sms",   smsName)
                .withDetail("push",  pushName)
                .withDetail("mode",  mode)
                .build();
    }
}
