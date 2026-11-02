package com.popnup.popnupbackend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

  @Mock private RedisTemplate<String, String> redisTemplate;

  @Mock private ValueOperations<String, String> valueOperations;

  @InjectMocks private LoginAttemptService loginAttemptService;

  @Test
  void 실패_횟수가_5회_미만이면_로그인이_차단되지_않는다() {
    // given
    String email = "test@test.com";
    String clientIp = "127.0.0.1";

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    when(valueOperations.get("login:fail:email:" + email)).thenReturn("4");

    when(valueOperations.get("login:fail:ip:" + clientIp)).thenReturn("4");

    // when
    boolean blocked = loginAttemptService.isBlocked(email, clientIp);

    // then
    assertThat(blocked).isFalse();
  }

  @Test
  void 실패_횟수가_5회면_로그인이_차단된다() {
    // given
    String email = "test@test.com";
    String clientIp = "127.0.0.1";

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    when(valueOperations.get("login:fail:email:" + email)).thenReturn("5");

    // when
    boolean blocked = loginAttemptService.isBlocked(email, clientIp);

    // then
    assertThat(blocked).isTrue();
  }

  @Test
  void 실패_횟수가_5회를_초과해도_로그인이_차단된다() {
    // given
    String email = "test@test.com";
    String clientIp = "127.0.0.1";

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    when(valueOperations.get("login:fail:email:" + email)).thenReturn("6");

    // when
    boolean blocked = loginAttemptService.isBlocked(email, clientIp);

    // then
    assertThat(blocked).isTrue();
  }

  @Test
  void 로그인_실패시_이메일과_IP의_실패횟수가_증가한다() {
    // given
    String email = "test@test.com";
    String clientIp = "127.0.0.1";

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    when(valueOperations.increment("login:fail:email:" + email)).thenReturn(1L);

    when(valueOperations.increment("login:fail:ip:" + clientIp)).thenReturn(1L);

    // when
    loginAttemptService.recordFailure(email, clientIp);

    // then
    verify(valueOperations).increment("login:fail:email:" + email);

    verify(valueOperations).increment("login:fail:ip:" + clientIp);
  }

  @Test
  void 로그인_실패횟수가_처음_증가하면_10분_TTL이_설정된다() {
    // given
    String email = "test@test.com";
    String clientIp = "127.0.0.1";

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    when(valueOperations.increment("login:fail:email:" + email)).thenReturn(1L);

    when(valueOperations.increment("login:fail:ip:" + clientIp)).thenReturn(1L);

    // when
    loginAttemptService.recordFailure(email, clientIp);

    // then
    verify(redisTemplate).expire("login:fail:email:" + email, Duration.ofMinutes(10));

    verify(redisTemplate).expire("login:fail:ip:" + clientIp, Duration.ofMinutes(10));
  }

  @Test
  void 로그인_성공시_이메일과_IP의_실패카운터가_삭제된다() {
    // given
    String email = "test@test.com";
    String clientIp = "127.0.0.1";

    // when
    loginAttemptService.clear(email, clientIp);

    // then
    verify(redisTemplate).delete("login:fail:email:" + email);

    verify(redisTemplate).delete("login:fail:ip:" + clientIp);
  }
}
