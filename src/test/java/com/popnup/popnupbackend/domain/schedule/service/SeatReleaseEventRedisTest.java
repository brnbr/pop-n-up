package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// Outbox 좌석 반환 Lua 스크립트를 실제 Redis에서 검증한다. 로컬 Redis(localhost:6379)가 없으면 건너뛴다.
class SeatReleaseEventRedisTest {

  private LettuceConnectionFactory factory;
  private StringRedisTemplate redis;
  private ScheduleCapacityCache cache;
  private long scheduleA;
  private long scheduleB;
  private long eventId;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(redisAvailable(), "로컬 Redis가 없어 건너뜀");
    factory = new LettuceConnectionFactory("localhost", 6379);
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
    cache = new ScheduleCapacityCache(redis, mock(ScheduleCapacityCounterStore.class));
    long base = 8_000_000_000L + ThreadLocalRandom.current().nextInt(1_000_000) * 2L;
    scheduleA = base;
    scheduleB = base + 1;
    eventId = base;
  }

  @AfterEach
  void tearDown() {
    if (redis != null) {
      redis.delete(
          java.util.List.of(
              ScheduleCapacityKeys.usedKey(scheduleA),
              ScheduleCapacityKeys.usedKey(scheduleB),
              "outbox:applied:" + eventId));
      factory.destroy();
    }
  }

  private static boolean redisAvailable() {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("localhost", 6379), 300);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  @DisplayName("같은 반환 이벤트가 두 번 와도 한 번만 반영한다 (사용량 키가 없는 회차는 되살리지 않음)")
  void appliesOnce() {
    redis.opsForValue().set(ScheduleCapacityKeys.usedKey(scheduleA), "10");
    Map<Long, Integer> seats = new LinkedHashMap<>();
    seats.put(scheduleA, 3);
    seats.put(scheduleB, 2);

    String count = ScheduleCapacityCache.APPLIED_EVENT_COUNT_KEY;
    long before =
        Long.parseLong(java.util.Objects.requireNonNullElse(redis.opsForValue().get(count), "0"));
    long startMs = System.currentTimeMillis();

    assertThat(cache.applySeatRelease(eventId, seats)).isTrue();
    assertThat(cache.applySeatRelease(eventId, seats)).isFalse();

    assertThat(redis.opsForValue().get(ScheduleCapacityKeys.usedKey(scheduleA))).isEqualTo("7");
    assertThat(redis.hasKey(ScheduleCapacityKeys.usedKey(scheduleB))).isFalse();
    // 적용한 이벤트 수는 한 번만 늘고, 적용 표시에는 반영 시각(ms)이 남는다 (전달 지연 측정용)
    assertThat(Long.parseLong(redis.opsForValue().get(count))).isEqualTo(before + 1);
    assertThat(Long.parseLong(redis.opsForValue().get("outbox:applied:" + eventId)))
        .isBetween(startMs - 5_000, System.currentTimeMillis() + 5_000);
  }

  @Test
  @DisplayName("반환으로 사용량이 0 아래로 내려가지 않는다")
  void neverBelowZero() {
    redis.opsForValue().set(ScheduleCapacityKeys.usedKey(scheduleA), "1");

    cache.applySeatRelease(eventId, Map.of(scheduleA, 5));

    assertThat(redis.opsForValue().get(ScheduleCapacityKeys.usedKey(scheduleA))).isEqualTo("0");
  }
}
