package com.fiscalmotion.ledger.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class IdempotencyService {

    private static final String IDEMPOTENCY_PREFIX = "idempotency:";
    private static final String RESPONSE_PREFIX = "idempotency-response:";

    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean lockKey(String idempotencyKey, Duration ttl) {
        String key = IDEMPOTENCY_PREFIX + idempotencyKey;
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "PROCESSING", ttl));
    }

    public Optional<String> getResponse(String idempotencyKey) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(RESPONSE_PREFIX + idempotencyKey));
    }

    public void saveResponse(String idempotencyKey, String payload, Duration ttl) {
        redisTemplate.opsForValue().set(RESPONSE_PREFIX + idempotencyKey, payload, ttl);
    }

    public void releaseKey(String idempotencyKey) {
        redisTemplate.delete(IDEMPOTENCY_PREFIX + idempotencyKey);
        redisTemplate.delete(RESPONSE_PREFIX + idempotencyKey);
    }
}