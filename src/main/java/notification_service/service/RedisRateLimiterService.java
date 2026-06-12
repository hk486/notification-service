package notification_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Prevents notification spam by enforcing a per-user-per-channel rate limit.
 *
 * How it works (think of it like a punch card):
 *  - Every user gets a punch card per channel (e.g. "harmeet:EMAIL").
 *  - Each notification attempt adds one punch.
 *  - The card disappears after 60 seconds (configurable sliding window).
 *  - If the card has more punches than the limit → reject the request.
 *
 * Redis key pattern:  rate:<userId>:<channel>
 * Redis value:        integer count of attempts in the current window
 * TTL:                window-seconds (auto-set on first punch, refreshed never
 *                     — sliding window using INCR + EXPIRE on first call only)
 *
 * Sliding-window algorithm (INCR + EXPIRE):
 *  1. INCR the counter.  Redis atomically increments (or creates at 0 then increments).
 *  2. If the result == 1 (first request in window) → SET EXPIRE so the window resets.
 *  3. If counter > limit → rate-limited.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisRateLimiterService {

    private static final String KEY_PREFIX = "rate:";

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${notification.rate-limit.max-per-minute:5}")
    private int maxPerMinute;

    @Value("${notification.rate-limit.window-seconds:60}")
    private long windowSeconds;

    /**
     * @param userId  the user sending the notification
     * @param channel EMAIL | SMS | PUSH
     * @return true  if the user is within limits (safe to proceed)
     *         false if the user has exceeded the rate limit
     */
    public boolean isAllowed(String userId, String channel) {
        String redisKey = KEY_PREFIX + userId + ":" + channel;

        // INCR is atomic — safe for concurrent requests
        Long count = redisTemplate.opsForValue().increment(redisKey);

        if (count == null) {
            // Redis unreachable — fail open (don't block users if Redis is down)
            log.warn("Redis returned null for INCR on key=[{}] — allowing request (fail-open)", redisKey);
            return true;
        }

        // First request in this window: set the expiry so the window auto-resets
        if (count == 1) {
            redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
        }

        boolean allowed = count <= maxPerMinute;

        if (!allowed) {
            log.warn("Rate limit exceeded | user=[{}] channel=[{}] count=[{}] max=[{}]",
                    userId, channel, count, maxPerMinute);
        } else {
            log.debug("Rate limit check passed | user=[{}] channel=[{}] count=[{}/{}]",
                    userId, channel, count, maxPerMinute);
        }

        return allowed;
    }
}
