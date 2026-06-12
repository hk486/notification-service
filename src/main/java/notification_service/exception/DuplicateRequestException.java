package notification_service.exception;

/**
 * Thrown when a request with an already-seen idempotency key arrives.
 * The caller receives HTTP 409 Conflict.
 */
public class DuplicateRequestException extends RuntimeException {

    public DuplicateRequestException(String idempotencyKey) {
        super("Duplicate request: idempotency key already processed — " + idempotencyKey);
    }
}
