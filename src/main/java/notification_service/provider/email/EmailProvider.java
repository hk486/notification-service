package notification_service.provider.email;

/**
 * Contract for all email providers.
 * Primary: AwsSesProvider
 * Fallback: SendGridProvider (Day 9)
 * Local dev: LogEmailProvider
 */
public interface EmailProvider {

    void send(String to, String subject, String body);

    String getProviderName();
}
