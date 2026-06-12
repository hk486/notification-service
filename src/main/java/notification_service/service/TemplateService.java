package notification_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notification_service.exception.TemplateNotFoundException;
import notification_service.model.NotificationLog;
import notification_service.model.NotificationTemplate;
import notification_service.repository.NotificationTemplateRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Resolves the final message body for a notification.
 *
 * Resolution order (first match wins):
 *
 *   1. templateName provided → load from DB by name + channel
 *                            → replace all {key} tokens with templateData values
 *   2. raw message provided  → use it directly, no DB lookup needed
 *   3. neither provided      → throw IllegalArgumentException (→ 400 Bad Request)
 *
 * Example:
 *   Template body: "Your OTP is {otp}. Valid for {expiry}."
 *   templateData:  { "otp": "123456", "expiry": "10 minutes" }
 *   Result:        "Your OTP is 123456. Valid for 10 minutes."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final NotificationTemplateRepository templateRepository;

    /**
     * Returns the resolved message body ready to be passed to a provider.
     *
     * @param templateName  name of a pre-seeded template in the DB (e.g. "OTP_EMAIL"), or null
     * @param channel       the notification channel — used to disambiguate templates with the same name
     * @param templateData  key-value pairs replacing {placeholders} in the template body
     * @param rawMessage    direct message body when no template is used
     */
    public String resolve(String templateName,
                          NotificationLog.Channel channel,
                          Map<String, String> templateData,
                          String rawMessage) {

        // ── Path 1: template-based ─────────────────────────────────────────
        if (templateName != null && !templateName.isBlank()) {
            NotificationTemplate template = templateRepository
                    .findByNameAndChannel(templateName, channel)
                    .orElseThrow(() -> new TemplateNotFoundException(
                            "Template not found: name=[" + templateName + "] channel=[" + channel + "]"));

            String rendered = render(template.getBody(), templateData);
            log.info("[TEMPLATE] Resolved | template=[{}] channel=[{}]", templateName, channel);
            return rendered;
        }

        // ── Path 2: raw message ────────────────────────────────────────────
        if (rawMessage != null && !rawMessage.isBlank()) {
            return rawMessage;
        }

        // ── Path 3: nothing provided ───────────────────────────────────────
        throw new IllegalArgumentException(
                "Either 'message' or 'templateName' must be provided in the request");
    }

    /**
     * Pre-flight validation called before a request is queued to Kafka.
     * Ensures the request has either a valid template or a raw message,
     * so callers receive a proper HTTP 400/404 rather than a silent failure.
     */
    public void validate(String templateName,
                         NotificationLog.Channel channel,
                         String rawMessage) {
        if ((templateName == null || templateName.isBlank()) &&
                (rawMessage == null || rawMessage.isBlank())) {
            throw new IllegalArgumentException(
                    "Either 'message' or 'templateName' must be provided in the request");
        }
        if (templateName != null && !templateName.isBlank()) {
            templateRepository.findByNameAndChannel(templateName, channel)
                    .orElseThrow(() -> new TemplateNotFoundException(
                            "Template not found: name=[" + templateName + "] channel=[" + channel + "]"));
        }
    }

    /**
     * Replaces every {key} token in the template body with the matching value
     * from the data map. Unrecognised placeholders are left unchanged.
     */
    private String render(String body, Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return body;
        }
        String result = body;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}
