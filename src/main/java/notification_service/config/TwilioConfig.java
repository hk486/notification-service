package notification_service.config;

import com.twilio.Twilio;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Initialises the Twilio client at startup.
 *
 * Twilio's SDK (unlike AWS) is not bean-based — it stores credentials
 * in a static context via Twilio.init().  Once called, every
 * TwilioSmsProvider.Message.creator(...).create() call will use them.
 *
 * Active only when twilio.enabled=true.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
public class TwilioConfig {

    @Value("${twilio.account-sid}")
    private String accountSid;

    @Value("${twilio.auth-token}")
    private String authToken;

    /**
     * Spring calls @PostConstruct after all fields are injected.
     * We use it to call Twilio.init() exactly once.
     */
    @PostConstruct
    public void initTwilio() {
        Twilio.init(accountSid, authToken);
        log.info("Twilio client initialised for account-sid=[{}]", accountSid);
    }
}
