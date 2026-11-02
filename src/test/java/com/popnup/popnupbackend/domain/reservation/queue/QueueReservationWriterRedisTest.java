package com.popnup.popnupbackend.domain.reservation.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.reservation.queue.QueueReservationPersister.Entry;
import com.popnup.popnupbackend.domain.reservation.queue.QueueReservationPersister.Outcome;
import com.popnup.popnupbackend.domain.reservation.queue.QueueReservationPersister.Result;
import com.popnup.popnupbackend.domain.reservation.queue.QueueStatus.State;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCache;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// DB 쓰기 작업자를 실제 Redis Stream으로 검증한다 (DB 저장은 목). 로컬 Redis가 없으면 건너뛴다
class QueueReservationWriterRedisTest {

  private RedissonClient redisson;
  private LettuceConnectionFactory connectionFactory;
  private ReservationQueueService queue;
  private QueueReservationPersister persister;
  private ScheduleCapacityCache capacityCache;
  private QueueReservationWriter writer;
  private Long scheduleId;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(ReservationQueueServiceRedisTest.redisAvailable(), "로컬 Redis가 없어 건너뜀");
    redisson = ReservationQueueServiceRedisTest.testRedisson();
    redisson.getKeys().flushdb();
    queue =
        ReservationQueueServiceRedisTest.newQueue(
            redisson, mock(ScheduleCapacityCounterStore.class));
    RedisStandaloneConfiguration server = new RedisStandaloneConfiguration("localhost", 6379);
    server.setDatabase(ReservationQueueServiceRedisTest.TEST_DB);
    connectionFactory = new LettuceConnectionFactory(server);
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();
    persister = mock(QueueReservationPersister.class);
    capacityCache = mock(ScheduleCapacityCache.class);
    writer =
        new QueueReservationWriter(
            new StringRedisTemplate(connectionFactory),
            persister,
            queue,
            capacityCache,
            new SimpleMeterRegistry(),
            500);

    scheduleId = 9_000_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);
    queue.setEnabled(scheduleId, true);
    redisson.getBucket("schedule:capacity:max:" + scheduleId).set("10");
    redisson.getBucket("schedule:capacity:used:" + scheduleId).set("0");
  }

  @AfterEach
  void tearDown() {
    if (redisson != null) {
      redisson.getKeys().flushdb();
      redisson.shutdown();
      connectionFactory.destroy();
    }
  }

  private void allocate(long... members) {
    for (long m : members) {
      queue.join(scheduleId, m, 2);
    }
    queue.allocate(members.length);
  }

  // 받은 신청 순서대로 결과를 준다. 저장된 신청의 예약 id = 100 + 순서
  private void answerWith(Result... results) {
    given(persister.persist(anyList()))
        .willAnswer(
            inv -> {
              List<Entry> entries = inv.getArgument(0);
              return IntStream.range(0, entries.size())
                  .mapToObj(
                      i ->
                          new Outcome(
                              entries.get(i),
                              results[i],
                              results[i] == Result.PERSISTED ? 100L + i : null))
                  .toList();
            });
  }

  @Test
  @DisplayName("확정된 신청을 번호 순서로 묶어 저장하고, 결과를 남긴 뒤 Stream에서 지운다")
  void drain_persistsAndRecords() {
    allocate(1L, 2L, 3L, 4L);
    answerWith(Result.PERSISTED, Result.DUPLICATE, Result.FAILED, Result.SOLD_OUT);

    writer.drain();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Entry>> captor = ArgumentCaptor.forClass(List.class);
    verify(persister).persist(captor.capture());
    assertThat(captor.getValue())
        .extracting(Entry::memberId, Entry::seq, Entry::personCount)
        .containsExactly(tuple(1L, 1L, 2), tuple(2L, 2L, 2), tuple(3L, 3L, 2), tuple(4L, 4L, 2));

    QueueStatus first = queue.status(scheduleId, 1L);
    assertThat(first.state()).isEqualTo(State.ALLOCATED);
    assertThat(first.reservationId()).isEqualTo(100L);
    assertThat(first.reservationNumber()).isEqualTo(captor.getValue().get(0).reservationNumber());
    assertThat(queue.status(scheduleId, 2L).state()).isEqualTo(State.DUPLICATE);
    assertThat(queue.status(scheduleId, 3L).state()).isEqualTo(State.FAILED);
    assertThat(queue.status(scheduleId, 4L).state()).isEqualTo(State.SOLD_OUT);

    // 중복 · 실패는 Redis 좌석을 돌려주고, DB 정원 초과(매진)는 돌려주지 않는다
    verify(capacityCache, times(2)).releaseNow(scheduleId, 2);
    assertThat(redisson.getStream(ReservationQueueService.STREAM_KEY).size()).isZero();

    writer.drain(); // 확인 응답을 보냈으므로 다시 받지 않는다
    verify(persister, times(1)).persist(anyList());
  }

  @Test
  @DisplayName("저장이 실패하면 받은 채로 남겨 다음 주기에 같은 신청을 다시 저장한다")
  void drain_retriesAfterFailure() {
    allocate(1L, 2L);
    given(persister.persist(anyList())).willThrow(new IllegalStateException("DB down"));

    writer.drain();
    assertThat(queue.status(scheduleId, 1L).reservationId()).isNull();
    assertThat(redisson.getStream(ReservationQueueService.STREAM_KEY).size()).isEqualTo(2);

    reset(persister);
    answerWith(Result.PERSISTED, Result.PERSISTED);
    writer.drain();

    assertThat(queue.status(scheduleId, 1L).reservationId()).isEqualTo(100L);
    assertThat(queue.status(scheduleId, 2L).reservationId()).isEqualTo(101L);
    assertThat(redisson.getStream(ReservationQueueService.STREAM_KEY).size()).isZero();
    verify(capacityCache, never()).releaseNow(anyLong(), anyInt());
  }

  @Test
  @DisplayName("Redis가 비워져 소비자 그룹이 사라져도 다시 만들어 이어서 저장한다")
  void drain_recreatesGroupAfterFlush() {
    answerWith(Result.PERSISTED);
    writer.drain(); // 그룹 생성
    redisson.getKeys().flushdb();
    queue.setEnabled(scheduleId, true);
    redisson.getBucket("schedule:capacity:max:" + scheduleId).set("10");
    redisson.getBucket("schedule:capacity:used:" + scheduleId).set("0");
    allocate(1L);

    writer.drain(); // 그룹이 없어 실패 → 다음 주기에 다시 만든다
    writer.drain();

    assertThat(queue.status(scheduleId, 1L).reservationId()).isEqualTo(100L);
    assertThat(redisson.getStream(ReservationQueueService.STREAM_KEY).size()).isZero();
  }
}
