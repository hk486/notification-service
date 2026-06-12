package notification_service.exception;

/**
 * Thrown when a caller provides a templateName that does not exist
 * in the notification_templates table for the requested channel.
 *
 * Maps to HTTP 404 Not Found via GlobalExceptionHandler.
 */
public class TemplateNotFoundException extends RuntimeException {

    public TemplateNotFoundException(String message) {
        super(message);
    }
}
