package notification_service.provider.sms;

import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Real SMS via Twilio REST API.
 * Active only when twilio.enabled=true in application.yaml.
 *
 * How it works:
 *  1. TwilioConfig initialises Twilio.init() at startup with account-sid + auth-token.
 *  2. This class just calls Message.creator(...).create() which hits the Twilio REST API.
 *  3. Twilio delivers the SMS to the carrier network within seconds.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
public class TwilioSmsProvider implements SmsProvider {

    @Value("${twilio.from-number}")
    private String fromNumber;

    @Override
    public void send(String to, String body) {
        // Message.creator uses the Twilio client initialised in TwilioConfig
        Message message = Message.creator(
                new PhoneNumber(to),      // recipient E.164 number
                new PhoneNumber(fromNumber), // Twilio "from" number
                body
        ).create();

        log.info("SMS sent via Twilio | to=[{}] sid=[{}] status=[{}]",
                to, message.getSid(), message.getStatus());
    }

    @Override
    public String getProviderName() {
        return "TWILIO";
    }
}
