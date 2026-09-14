package com.popnup.popnupbackend.global.security;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

  private final RedisTemplate<String, String> redisTemplate;

  // 블랙리스트 키
  private static final String BLACKLIST_PREFIX = "blacklist:";

  public void blacklist(String token, long expirationMillis) {
    String key = BLACKLIST_PREFIX + token;

    redisTemplate.opsForValue().set(key, "logout", Duration.ofMillis(expirationMillis));
    // 로그아웃 토큰 저장 ( key, value, TTL )
  }

  public boolean isBlacklisted(String token) {
    String key = BLACKLIST_PREFIX + token;

    return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    // 블랙리스트인지 확인
  }
}
