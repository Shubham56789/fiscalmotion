package com.fiscalmotion.ledger.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private static final String IDEMPOTENCY_PREFIX = "idempotency:";

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Attempts to acquire an idempotency lock for a key with a given TTL.
     * @return true if key was acquired (first request), false if duplicate/locked.
     */
    public boolean lockKey(String idempotencyKey, Duration ttl) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        Boolean success = redisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSING", ttl);
        return Boolean.TRUE.equals(success);
    }

    public void releaseKey(String idempotencyKey) {
        redisTemplate.delete(IDEMPOTENCY_PREFIX + idempotencyKey);
    }
}