package com.popnup.popnupbackend.domain.schedule.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class SeatReleaseKafkaConsumerTest {

  private ScheduleCapacityCache cache;
  private SimpleMeterRegistry meters;
  private SeatReleaseKafkaConsumer consumer;

  @BeforeEach
  void setUp() {
    cache = mock(ScheduleCapacityCache.class);
    meters = new SimpleMeterRegistry();
    consumer = new SeatReleaseKafkaConsumer(cache, meters);
  }

  // Debezium Outbox Event Router가 보내는 모양: 키 = 이벤트 id, 값 = payload, 헤더 id · createdAt(µs)
  private static ConsumerRecord<String, String> record(
      long id, String payload, LocalDateTime createdAt) {
    ConsumerRecord<String, String> r =
        new ConsumerRecord<>("outbox.SEAT_RELEASE", 0, 0L, String.valueOf(id), payload);
    r.headers().add("id", String.valueOf(id).getBytes(StandardCharsets.UTF_8));
    long micros = createdAt.toEpochSecond(ZoneOffset.UTC) * 1_000_000 + createdAt.getNano() / 1_000;
    r.headers().add("createdAt", String.valueOf(micros).getBytes(StandardCharsets.UTF_8));
    return r;
  }

  @Test
  @DisplayName("이벤트 id와 회차별 반환 인원으로 Redis에 반영하고, 생성부터 반영까지 시간을 남긴다")
  void appliesEvent() {
    given(cache.applySeatRelease(anyLong(), anyMap())).willReturn(true);

    consumer.onSeatRelease(record(42L, "10:1,20:5", LocalDateTime.now().minusSeconds(1)));

    verify(cache).applySeatRelease(42L, Map.of(10L, 1, 20L, 5));
    assertThat(meters.counter("outbox.relay", "outcome", OutboxRelay.APPLIED).count()).isEqualTo(1);
    assertThat(
            meters.timer("outbox.delivery").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
        .isGreaterThanOrEqualTo(900);
  }

  @Test
  @DisplayName("Redis 오류는 삼키지 않고 던진다 (오류 처리기가 같은 레코드를 다시 시도해 이벤트가 사라지지 않음)")
  void redisFailure_rethrown() {
    given(cache.applySeatRelease(anyLong(), anyMap()))
        .willThrow(new QueryTimeoutException("Redis command timed out"));

    assertThatThrownBy(() -> consumer.onSeatRelease(record(42L, "10:1", LocalDateTime.now())))
        .isInstanceOf(QueryTimeoutException.class);
  }

  @Test
  @DisplayName("Debezium의 DATETIME µs 값은 적힌 시각 그대로의 LocalDateTime으로 되돌린다")
  void debeziumMicros_roundTrip() {
    LocalDateTime t = LocalDateTime.of(2026, 10, 29, 23, 30, 1, 123_456_000);
    long micros = t.toEpochSecond(ZoneOffset.UTC) * 1_000_000 + 123_456;

    assertThat(SeatReleaseKafkaConsumer.fromDebeziumMicros(String.valueOf(micros))).isEqualTo(t);
  }
}
