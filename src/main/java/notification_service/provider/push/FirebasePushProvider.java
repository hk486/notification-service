package notification_service.provider.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Real push notification via Firebase Cloud Messaging (FCM).
 * Active only when firebase.enabled=true in application.yaml.
 *
 * How it works:
 *  1. FirebaseConfig initialises FirebaseApp at startup with the service-account JSON.
 *  2. FirebaseMessaging.getInstance() picks up that initialised app.
 *  3. The FCM API delivers the push to the device within milliseconds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
public class FirebasePushProvider implements PushProvider {

    @Override
    public void send(String deviceToken, String title, String body) {
        Message message = Message.builder()
                .setToken(deviceToken)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .build();

        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("Push sent via FCM | token=[{}...] messageId=[{}]",
                    deviceToken.substring(0, Math.min(deviceToken.length(), 10)), messageId);
        } catch (Exception e) {
            log.error("FCM send failed | token=[{}...] error=[{}]",
                    deviceToken.substring(0, Math.min(deviceToken.length(), 10)), e.getMessage(), e);
            throw new RuntimeException("FCM send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String getProviderName() {
        return "FIREBASE_FCM";
    }
}
