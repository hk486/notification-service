package notification_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Central metrics facade for the notification service.
 *
 * All Prometheus metric names follow the Micrometer convention
 * (dot-separated → converted to underscores in Prometheus scrape).
 *
 * Metrics exposed:
 *  notifications.accepted          — HTTP 202 returned to caller (tagged: channel, priority)
 *  notifications.rejected.duplicate — HTTP 409 idempotency hit  (tagged: channel)
 *  notifications.rejected.ratelimit — HTTP 429 rate-limit hit   (tagged: channel)
 *  notifications.sent              — successfully delivered      (tagged: channel, provider)
 *  notifications.failed            — all retries exhausted       (tagged: channel)
 *  notifications.skipped           — DND / preference block      (tagged: channel)
 *  notifications.dlq.published     — message sent to DLQ topic   (tagged: channel)
 *  notifications.send.duration     — end-to-end dispatch time    (tagged: channel) — Timer
 */
@Component
public class NotificationMetrics {

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── Controller-layer counters ─────────────────────────────────────────────

    public void incrementAccepted(String channel, String priority) {
        Counter.builder("notifications.accepted")
                .description("Notifications accepted and queued to Kafka")
                .tag("channel", channel)
                .tag("priority", priority)
                .register(registry)
                .increment();
    }

    public void incrementRejectedDuplicate(String channel) {
        Counter.builder("notifications.rejected.duplicate")
                .description("Notifications rejected due to duplicate idempotency key")
                .tag("channel", channel)
                .register(registry)
                .increment();
    }

    public void incrementRejectedRateLimit(String channel) {
        Counter.builder("notifications.rejected.ratelimit")
                .description("Notifications rejected due to rate limit exceeded")
                .tag("channel", channel)
                .register(registry)
                .increment();
    }

    // ── Dispatch-layer counters ───────────────────────────────────────────────

    public void incrementSent(String channel, String provider) {
        Counter.builder("notifications.sent")
                .description("Notifications successfully delivered by a provider")
                .tag("channel", channel)
                .tag("provider", provider)
                .register(registry)
                .increment();
    }

    public void incrementFailed(String channel) {
        Counter.builder("notifications.failed")
                .description("Notifications that exhausted all retries and went to DLQ")
                .tag("channel", channel)
                .register(registry)
                .increment();
    }

    public void incrementSkipped(String channel) {
        Counter.builder("notifications.skipped")
                .description("Notifications skipped due to user preference or DND")
                .tag("channel", channel)
                .register(registry)
                .increment();
    }

    // ── DLQ counter ──────────────────────────────────────────────────────────

    public void incrementDlqPublished(String channel) {
        Counter.builder("notifications.dlq.published")
                .description("Messages published to the dead-letter queue topic")
                .tag("channel", channel)
                .register(registry)
                .increment();
    }

    // ── Timer ────────────────────────────────────────────────────────────────

    /**
     * Record how long a single dispatch attempt took (millis).
     * Use: long start = System.currentTimeMillis(); ... recordSendDuration(channel, start);
     */
    public void recordSendDuration(String channel, long startMillis) {
        Timer.builder("notifications.send.duration")
                .description("End-to-end time from consumer receipt to provider response (ms)")
                .tag("channel", channel)
                .register(registry)
                .record(System.currentTimeMillis() - startMillis, TimeUnit.MILLISECONDS);
    }
}
