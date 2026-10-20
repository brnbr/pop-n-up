package com.popnup.popnupbackend.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final String EMAIL_PREFIX = "login:fail:email:";
    private static final String IP_PREFIX = "login:fail:ip:";

    private static final int MAX_ATTEMPTS =5;
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(10);

    private final RedisTemplate<String, String > redisTemplate;

    public boolean isBlocked(String email, String clientIp) {
        return getCount(EMAIL_PREFIX + email) >= MAX_ATTEMPTS
                || getCount(IP_PREFIX + clientIp) >= MAX_ATTEMPTS;
    }

    public void recordFailure(String email, String clientIp) {
        increment(EMAIL_PREFIX + email);
        increment(IP_PREFIX + clientIp);
    }

    public void clear(String email, String clientIp) {
        redisTemplate.delete(EMAIL_PREFIX + email);
        redisTemplate.delete(IP_PREFIX + clientIp);
    }

    private void increment(String key) {
        Long count = redisTemplate.opsForValue().increment(key);

        if (count != null && count == 1L) {
            redisTemplate.expire(key, BLOCK_DURATION);
        }
    }

    private long getCount(String key) {
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return 0L;
        }

        return Long.parseLong(value);
    }

}
