package com.popnup.popnupbackend.global.security;

import com.popnup.popnupbackend.global.error.AuthErrorCode;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

  private static final String BLACKLIST_PREFIX = "blacklist:";

  private final RedisTemplate<String, String> redisTemplate;

  public void blacklist(String token, long expirationMillis) {
    String key = BLACKLIST_PREFIX + token;

    redisTemplate.opsForValue().set(key, "logout", Duration.ofMillis(expirationMillis));
  }

  public boolean isBlacklisted(String token) {
    String key = BLACKLIST_PREFIX + token;

    try {
      return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    } catch (Exception e) {
      throw AuthErrorCode.REDIS_UNAVAILABLE.toException();
    }
  }
}
