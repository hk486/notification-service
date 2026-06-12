package notification_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Prevents duplicate notifications by tracking idempotency keys in Redis.
 *
 * How it works (think of it like a stamp on a letter):
 *  - When we receive a notification request, we check if its idempotencyKey
 *    already exists in Redis (was this letter already stamped?).
 *  - If YES  → reject it immediately. Same request already processed.
 *  - If NO   → stamp it in Redis with a 24-hour TTL, then proceed.
 *
 * Redis key pattern:  idempotency:<idempotencyKey>
 * Redis value:        "1" (we only care about key existence, not the value)
 * TTL:                24 hours (configurable via notification.idempotency.ttl-hours)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisIdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${notification.idempotency.ttl-hours:24}")
    private long ttlHours;

    /**
     * @return true  if this key is NEW (safe to proceed)
     *         false if this key was ALREADY SEEN (duplicate — reject)
     */
    public boolean isNewRequest(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;

        // setIfAbsent = SET key value NX EX ttl
        // Returns TRUE if key was newly set (first time)
        // Returns FALSE if key already existed (duplicate)
        Boolean wasNew = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", Duration.ofHours(ttlHours));

        boolean isNew = Boolean.TRUE.equals(wasNew);

        if (!isNew) {
            log.warn("Duplicate request detected | idempotencyKey=[{}]", idempotencyKey);
        } else {
            log.debug("New idempotency key registered | key=[{}] ttlHours=[{}]", idempotencyKey, ttlHours);
        }

        return isNew;
    }
}
