package notification_service.provider.push;

/**
 * Contract for all push-notification providers.
 * Primary  : FirebasePushProvider  (when firebase.enabled=true)
 * Local dev: LogPushProvider       (default — just prints to console)
 */
public interface PushProvider {

    /**
     * @param deviceToken FCM registration token of the target device
     * @param title       notification title shown in the OS tray
     * @param body        notification body text
     */
    void send(String deviceToken, String title, String body);

    String getProviderName();
}
