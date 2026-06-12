package notification_service.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;

/**
 * Initialises the Firebase Admin SDK at startup.
 *
 * Firebase, like Twilio, uses a static singleton (FirebaseApp) rather than
 * Spring-managed beans. Once FirebaseApp.initializeApp() is called here,
 * FirebaseMessaging.getInstance() works anywhere in the application.
 *
 * To get the service-account JSON:
 *   Firebase Console → Project Settings → Service Accounts → Generate New Private Key
 *   Place the downloaded file at: src/main/resources/firebase-service-account.json
 *
 * Active only when firebase.enabled=true.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
public class FirebaseConfig {

    @Value("${firebase.service-account-path}")
    private Resource serviceAccountResource;

    @PostConstruct
    public void initFirebase() throws IOException {
        // Guard: don't initialise twice (can happen in tests)
        if (!FirebaseApp.getApps().isEmpty()) {
            log.info("FirebaseApp already initialised — skipping.");
            return;
        }

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccountResource.getInputStream()))
                .build();

        FirebaseApp.initializeApp(options);
        log.info("Firebase Admin SDK initialised.");
    }
}
