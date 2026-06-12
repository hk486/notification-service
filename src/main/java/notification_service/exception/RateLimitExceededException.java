package notification_service.exception;

/**
 * Thrown when a user exceeds the allowed notification rate for a channel.
 * The caller receives HTTP 429 Too Many Requests.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String userId, String channel) {
        super("Rate limit exceeded for user=[" + userId + "] channel=[" + channel + "]. Please try again later.");
    }
}
