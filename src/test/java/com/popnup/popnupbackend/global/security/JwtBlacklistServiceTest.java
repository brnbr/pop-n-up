package com.popnup.popnupbackend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.popnup.popnupbackend.global.error.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

@ExtendWith(MockitoExtension.class)
class JwtBlacklistServiceTest {

  @Mock private RedisTemplate<String, String> redisTemplate;

  @InjectMocks private JwtBlacklistService jwtBlacklistService;

  @Test
  void 블랙리스트에_없는_토큰은_false를_반환한다() {
    // given
    String token = "test.jwt.token";

    when(redisTemplate.hasKey("blacklist:" + token)).thenReturn(false);

    // when
    boolean result = jwtBlacklistService.isBlacklisted(token);

    // then
    assertThat(result).isFalse();
  }

  @Test
  void 블랙리스트에_있는_토큰은_true를_반환한다() {
    // given
    String token = "test.jwt.token";

    when(redisTemplate.hasKey("blacklist:" + token)).thenReturn(true);

    // when
    boolean result = jwtBlacklistService.isBlacklisted(token);

    // then
    assertThat(result).isTrue();
  }

  @Test
  void Redis_장애시_인증_서비스_일시적_사용불가_에러가_발생한다() {
    // given
    String token = "test.jwt.token";

    when(redisTemplate.hasKey("blacklist:" + token))
        .thenThrow(new RuntimeException("Redis connection failed"));

    // when & then
    assertThatThrownBy(() -> jwtBlacklistService.isBlacklisted(token))
        .isInstanceOf(ServiceException.class)
        .hasMessage("인증 서비스를 일시적으로 사용할 수 없습니다.");
  }
}
