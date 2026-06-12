package notification_service.provider.sms;

/**
 * Contract for all SMS providers.
 * Primary  : TwilioSmsProvider  (when twilio.enabled=true)
 * Local dev: LogSmsProvider     (default — just prints to console)
 */
public interface SmsProvider {

    /**
     * @param to   recipient's phone number in E.164 format, e.g. "+919876543210"
     * @param body the SMS text body
     */
    void send(String to, String body);

    String getProviderName();
}
