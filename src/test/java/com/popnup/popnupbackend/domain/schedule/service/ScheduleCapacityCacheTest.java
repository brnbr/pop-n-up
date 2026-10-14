package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ScheduleCapacityCacheTest {

  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOps;
  private ScheduleCapacityCache cache;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    given(redisTemplate.opsForValue()).willReturn(valueOps);
    cache = new ScheduleCapacityCache(redisTemplate);
  }

  // multiGet 순서: [max, used]
  private void redisHas(String max, String used) {
    given(valueOps.multiGet(anyList())).willReturn(Arrays.asList(max, used));
  }

  @Nested
  @DisplayName("정원 마감 선확인 [isSoldOut]")
  class IsSoldOutTest {

    @Test
    @DisplayName("마감: 사용량 + 요청 인원이 정원을 넘으면 true")
    void soldOut_whenOverCapacity() {
      redisHas("100", "100");

      assertThat(cache.isSoldOut(1L, 1)).isTrue();
    }

    @Test
    @DisplayName("마감: 한 명 자리는 남았지만 요청 인원이 남은 자리보다 많으면 true")
    void soldOut_whenRequestExceedsRemaining() {
      redisHas("100", "99");

      assertThat(cache.isSoldOut(1L, 2)).isTrue();
    }

    @Test
    @DisplayName("경계: 사용량 + 요청 인원이 정확히 정원이면 마감이 아니다 (마지막 자리)")
    void notSoldOut_atExactCapacity() {
      redisHas("100", "99");

      assertThat(cache.isSoldOut(1L, 1)).isFalse();
    }

    @Test
    @DisplayName("여유: 정원이 남아 있으면 false")
    void notSoldOut_whenRoomLeft() {
      redisHas("100", "10");

      assertThat(cache.isSoldOut(1L, 1)).isFalse();
    }

    @Test
    @DisplayName("fail-open: 키가 없으면 false (일괄 등록 이전에 만든 회차 등, DB가 판단)")
    void notSoldOut_whenKeysMissing() {
      redisHas(null, null);

      assertThat(cache.isSoldOut(1L, 1)).isFalse();
    }

    @Test
    @DisplayName("fail-open: 한쪽 키만 없어도 false")
    void notSoldOut_whenOneKeyMissing() {
      redisHas("100", null);

      assertThat(cache.isSoldOut(1L, 1)).isFalse();
    }

    @Test
    @DisplayName("fail-open: Redis 장애 시 예외를 던지지 않고 false (선확인이 예약을 막으면 안 된다)")
    void notSoldOut_whenRedisFails() {
      given(valueOps.multiGet(anyList())).willThrow(new RuntimeException("redis down"));

      assertThat(cache.isSoldOut(1L, 1)).isFalse();
    }

    @Test
    @DisplayName("fail-open: Redis 응답이 비어 있으면 false")
    void notSoldOut_whenResponseEmpty() {
      given(valueOps.multiGet(anyList())).willReturn(List.of());

      assertThat(cache.isSoldOut(1L, 1)).isFalse();
    }
  }
}
