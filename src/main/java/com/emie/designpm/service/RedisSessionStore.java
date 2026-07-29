package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis-backed session storage with a safe in-memory fallback. */
@Component
public class RedisSessionStore {
    private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);
    private static final String PREFIX = "designpm:session:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void put(String token, AuthController.AuthSession session) {
        try {
            long ttlMillis = session.expiresAt() - System.currentTimeMillis();
            if (ttlMillis <= 0) return;
            redis.opsForValue().set(key(token), objectMapper.writeValueAsString(session),
                    Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            log.warn("Redis 会话写入失败，将使用本地会话缓存 reason={}", e.getClass().getSimpleName());
        }
    }

    public AuthController.AuthSession get(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String value = redis.opsForValue().get(key(token));
            return value == null ? null : objectMapper.readValue(value, AuthController.AuthSession.class);
        } catch (Exception e) {
            log.warn("Redis 会话读取失败，将使用本地会话缓存 reason={}", e.getClass().getSimpleName());
            return null;
        }
    }

    public void remove(String token) {
        if (token == null || token.isBlank()) return;
        try {
            redis.delete(key(token));
        } catch (Exception e) {
            log.warn("Redis 会话删除失败 reason={}", e.getClass().getSimpleName());
        }
    }

    private String key(String token) {
        return PREFIX + token;
    }
}
