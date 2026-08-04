package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import org.springframework.data.redis.core.ScanOptions;
import java.util.Map;

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
            if (session.expiresAt() <= 0 || session.expiresAt() >= Long.MAX_VALUE / 2) {
                redis.opsForValue().set(key(token), objectMapper.writeValueAsString(session));
            } else if (ttlMillis > 0) {
                redis.opsForValue().set(key(token), objectMapper.writeValueAsString(session),
                        Duration.ofMillis(ttlMillis));
            }
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

    /** 撤销指定用户的全部会话，用于改密、删号和禁用账号。 */
    public void removeUserTokens(String userId) {
        if (userId == null || userId.isBlank()) return;
        try {
            var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").count(256).build());
            try (keys) {
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = redis.opsForValue().get(key);
                    if (value == null) continue;
                    AuthController.AuthSession session = objectMapper.readValue(value, AuthController.AuthSession.class);
                    if (userId.equals(session.userId())) redis.delete(key);
                }
            }
        } catch (Exception e) {
            log.warn("Redis 用户会话撤销失败 userId={} reason={}", userId, e.getClass().getSimpleName());
        }
    }

    private String key(String token) {
        return PREFIX + token;
    }
}
