package com.popnup.popnupbackend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;

class JwtBlacklistServiceTest {

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("Redis가 응답하지 않으면 블랙리스트 조회는 전용 시간 초과(100ms)만큼만 기다리고 실패한다 (일반 Redis 호출은 500ms)")
  void lookup_timesOutQuickly() throws Exception {
    // 연결은 받지만 아무 응답도 하지 않는 서버 (멈춘 Redis)
    try (ServerSocket silent = new ServerSocket(0)) {
      JwtBlacklistService service =
          new JwtBlacklistService(
              mock(RedisTemplate.class),
              (ClientResources) null,
              new SimpleMeterRegistry(),
              "localhost",
              silent.getLocalPort(),
              "",
              "",
              0,
              false,
              100);
      try {
        // 첫 호출은 Lettuce 클라이언트 준비(Netty 이벤트 루프 · 클래스 로딩)가 함께 들어가 앱 시작 뒤 한 번만 느리다
        assertThatThrownBy(() -> service.isBlacklisted("warm-up"))
            .isInstanceOf(DataAccessException.class);

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.isBlacklisted("token"))
            .isInstanceOf(DataAccessException.class); // JwtFilter가 받아 조회를 건너뜀
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(450));
      } finally {
        service.close();
      }
    }
  }
}
