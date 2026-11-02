package com.popnup.popnupbackend.domain.reservation.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.queue.QueueStatus.State;
import com.popnup.popnupbackend.domain.reservation.service.ReservationNumberGenerator;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleCapacityCounterStore;
import com.popnup.popnupbackend.global.error.ServiceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;

// 대기열 Lua 스크립트를 실제 Redis에서 검증한다. 로컬 Redis(localhost:6379)가 없으면 건너뛴다.
// 측정용 앱이 같은 Redis를 써도 간섭하지 않게 테스트 전용 DB(15번)를 쓴다
class ReservationQueueServiceRedisTest {

  static final int TEST_DB = 15;
  private static final long IDLE_TTL_SECONDS = 3_600;

  private RedissonClient redisson;
  private ScheduleCapacityCounterStore counterStore;
  private ReservationQueueService queue;
  private Long scheduleId;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(redisAvailable(), "로컬 Redis가 없어 건너뜀");
    redisson = testRedisson();
    counterStore = mock(ScheduleCapacityCounterStore.class);
    queue = newQueue(redisson, counterStore);
    scheduleId = 9_000_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);
    queue.setEnabled(scheduleId, true);
  }

  @AfterEach
  void tearDown() {
    if (redisson != null) {
      redisson.getKeys().flushdb();
      redisson.shutdown();
    }
  }

  static RedissonClient testRedisson() {
    Config config = new Config();
    config.setCodec(StringCodec.INSTANCE);
    config
        .useSingleServer()
        .setAddress("redis://localhost:6379")
        .setDatabase(TEST_DB)
        .setTimeout(1000);
    return Redisson.create(config);
  }

  @SuppressWarnings("unchecked")
  static ReservationQueueService newQueue(
      RedissonClient redisson, ScheduleCapacityCounterStore counterStore) {
    ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
    given(provider.getObject()).willReturn(redisson);
    return new ReservationQueueService(
        true,
        200,
        IDLE_TTL_SECONDS,
        provider,
        new ReservationNumberGenerator(),
        mock(ScheduleRepository.class),
        counterStore,
        new SimpleMeterRegistry());
  }

  static boolean redisAvailable() {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("localhost", 6379), 300);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private void capacity(int max, int used) {
    redisson.getBucket("schedule:capacity:max:" + scheduleId).set(String.valueOf(max));
    redisson.getBucket("schedule:capacity:used:" + scheduleId).set(String.valueOf(used));
  }

  private long used() {
    return Long.parseLong(
        (String) redisson.getBucket("schedule:capacity:used:" + scheduleId).get());
  }

  private Map<StreamMessageId, Map<String, String>> stream() {
    return redisson
        .<String, String>getStream(ReservationQueueService.STREAM_KEY)
        .range(StreamMessageId.MIN, StreamMessageId.MAX);
  }

  @Test
  @DisplayName("들어온 순서대로 번호를 받고, 같은 회원이 다시 들어오면 처음 번호 · 인원 그대로다")
  void join_inOrder_idempotent() {
    QueueStatus a = queue.join(scheduleId, 1L, 2);
    QueueStatus b = queue.join(scheduleId, 2L, 1);
    QueueStatus again = queue.join(scheduleId, 1L, 5);

    assertThat(a.number()).isEqualTo(1);
    assertThat(b.number()).isEqualTo(2);
    assertThat(again.number()).isEqualTo(1);
    assertThat(again.personCount()).isEqualTo(2);
    assertThat(b.state()).isEqualTo(State.WAITING);
    assertThat(b.ahead()).isEqualTo(1);
  }

  @Test
  @DisplayName("인원이 1~10명 밖이면 줄에 세우지 않는다")
  void join_invalidPersonCount() {
    assertThatThrownBy(() -> queue.join(scheduleId, 1L, 0))
        .isInstanceOf(ServiceException.class)
        .extracting("errorCode")
        .isEqualTo(ReservationErrorCode.INVALID_PERSON_COUNT);
    assertThatThrownBy(() -> queue.status(scheduleId, 1L))
        .isInstanceOf(ServiceException.class)
        .extracting("errorCode")
        .isEqualTo(ReservationErrorCode.QUEUE_NOT_JOINED);
  }

  @Test
  @DisplayName("앞에 기다리는 신청이 없으면 진입 때 바로 확정하고, 자리가 모자란 신청은 매진으로 끝낸다")
  void join_decidesImmediately_whenNoOneWaiting() {
    capacity(3, 0);

    QueueStatus first = queue.join(scheduleId, 1L, 2);
    QueueStatus second = queue.join(scheduleId, 2L, 2); // 남은 1석보다 많음
    QueueStatus third = queue.join(scheduleId, 3L, 1);
    QueueStatus fourth = queue.join(scheduleId, 4L, 1);

    assertThat(first.state()).isEqualTo(State.ALLOCATED);
    assertThat(first.reservationNumber()).startsWith("R");
    assertThat(second.state()).isEqualTo(State.SOLD_OUT);
    assertThat(third.state()).isEqualTo(State.ALLOCATED);
    assertThat(fourth.state()).isEqualTo(State.SOLD_OUT);
    assertThat(used()).isEqualTo(3);
    assertThat(stream().values())
        .extracting(m -> m.get("member") + "/" + m.get("seq") + "/" + m.get("pc"))
        .containsExactly("1/1/2", "3/3/1");
    QueueStatus again = queue.join(scheduleId, 1L, 5); // 다시 들어와도 같은 결과 · 같은 예약 번호
    assertThat(again.reservationNumber()).isEqualTo(first.reservationNumber());
    assertThat(stream()).hasSize(2);
  }

  @Test
  @DisplayName("줄에 기다리는 신청이 있으면 새 신청도 줄 뒤에 서고, 줄이 빈 뒤에는 다시 진입 때 확정한다")
  void join_waitsBehindQueue() {
    queue.join(scheduleId, 1L, 1); // 정원 키가 없어 줄에 섬
    capacity(10, 0);

    QueueStatus second = queue.join(scheduleId, 2L, 1);
    assertThat(second.state()).isEqualTo(State.WAITING);
    assertThat(second.ahead()).isEqualTo(1);

    queue.allocate(10);
    assertThat(queue.join(scheduleId, 3L, 1).state()).isEqualTo(State.ALLOCATED);
    assertThat(stream().values()).extracting(m -> m.get("member")).containsExactly("1", "2", "3");
  }

  @Test
  @DisplayName("줄에 선 신청은 한 주기에 꺼낼 수만큼 번호 순서대로 확정하고, 확정을 Stream에 남긴다")
  void allocate_inOrder_perTick() {
    queue.join(scheduleId, 1L, 2);
    queue.join(scheduleId, 2L, 1);
    queue.join(scheduleId, 3L, 1);
    capacity(10, 0);

    queue.allocate(2);

    QueueStatus first = queue.status(scheduleId, 1L);
    assertThat(first.state()).isEqualTo(State.ALLOCATED);
    assertThat(first.reservationNumber()).startsWith("R");
    assertThat(first.reservationId()).isNull(); // DB 저장 전
    assertThat(queue.status(scheduleId, 2L).state()).isEqualTo(State.ALLOCATED);
    QueueStatus third = queue.status(scheduleId, 3L);
    assertThat(third.state()).isEqualTo(State.WAITING);
    assertThat(third.ahead()).isZero();
    assertThat(used()).isEqualTo(3);

    assertThat(stream().values())
        .extracting(m -> m.get("member") + "/" + m.get("seq") + "/" + m.get("pc"))
        .containsExactly("1/1/2", "2/2/1");
    assertThat(stream().values())
        .extracting(m -> m.get("no"))
        .containsExactly(
            first.reservationNumber(), queue.status(scheduleId, 2L).reservationNumber());
  }

  @Test
  @DisplayName("줄에 선 신청도 좌석이 모자라면 매진이고, 이미 매진이면 남은 대기자를 한 번에 매진으로 끝낸다")
  void allocate_soldOut() {
    queue.join(scheduleId, 1L, 2);
    queue.join(scheduleId, 2L, 2); // 남은 1석보다 많음
    queue.join(scheduleId, 3L, 1);
    for (long m = 4; m <= 8; m++) {
      queue.join(scheduleId, m, 1);
    }
    capacity(3, 0);

    queue.allocate(3);

    assertThat(queue.status(scheduleId, 1L).state()).isEqualTo(State.ALLOCATED);
    QueueStatus second = queue.status(scheduleId, 2L);
    assertThat(second.state()).isEqualTo(State.SOLD_OUT);
    assertThat(second.soldOut()).isTrue();
    assertThat(queue.status(scheduleId, 3L).state()).isEqualTo(State.ALLOCATED);
    assertThat(used()).isEqualTo(3);

    queue.allocate(1); // 매진: 꺼낼 수와 상관없이 남은 5명을 모두 끝낸다
    for (long m = 4; m <= 8; m++) {
      assertThat(queue.status(scheduleId, m).state()).isEqualTo(State.SOLD_OUT);
    }
    assertThat(stream()).hasSize(2);
  }

  @Test
  @DisplayName("회차 정원 키가 없으면 줄을 건드리지 않고 DB 값으로 채운다 (다음 주기에 확정)")
  void allocate_missingCapacity_restores() {
    queue.join(scheduleId, 1L, 1);

    queue.allocate(10);

    assertThat(queue.status(scheduleId, 1L).state()).isEqualTo(State.WAITING);
    assertThat(stream()).isEmpty();
    // DB에 회차가 없으면(테스트 목) 채울 것이 없다. 있는 회차는 restoreAll로 채운다
    verify(counterStore, org.mockito.Mockito.never()).restoreAll(anyList());
  }

  @Test
  @DisplayName("DB 저장 결과를 남기면 상태 확인이 예약 id · 중복 · 실패를 돌려준다")
  void recordResult_reflectedInStatus() {
    capacity(10, 0);
    queue.join(scheduleId, 1L, 1);
    queue.join(scheduleId, 2L, 1);
    queue.join(scheduleId, 3L, 1);
    String no = queue.status(scheduleId, 1L).reservationNumber();

    queue.recordResult(scheduleId, 1L, "A:" + no + ":77");
    queue.recordResult(scheduleId, 2L, "D");
    queue.recordResult(scheduleId, 3L, "F");

    QueueStatus first = queue.status(scheduleId, 1L);
    assertThat(first.state()).isEqualTo(State.ALLOCATED);
    assertThat(first.reservationNumber()).isEqualTo(no);
    assertThat(first.reservationId()).isEqualTo(77L);
    assertThat(queue.status(scheduleId, 2L).state()).isEqualTo(State.DUPLICATE);
    assertThat(queue.status(scheduleId, 3L).state()).isEqualTo(State.FAILED);
  }

  @Test
  @DisplayName("진입 · 상태 확인 한 번에 회차 매진 여부도 함께 읽는다 (정원 키가 없으면 매진 아님)")
  void status_readsSoldOutInSameCall() {
    assertThat(queue.join(scheduleId, 1L, 2).soldOut()).isFalse();

    capacity(2, 1);
    assertThat(queue.status(scheduleId, 1L).soldOut()).isTrue(); // 1석 남음 < 2명

    capacity(2, 0);
    assertThat(queue.status(scheduleId, 1L).soldOut()).isFalse();
  }

  @Test
  @DisplayName("대기열이 켜진 회차만 예약 API로 바로 예약하는 것을 막는다")
  void rejectDirectBooking_onlyQueued() {
    assertThatThrownBy(() -> queue.rejectDirectBooking(scheduleId))
        .isInstanceOf(ServiceException.class)
        .extracting("errorCode")
        .isEqualTo(ReservationErrorCode.QUEUE_ENTRY_REQUIRED);
    queue.rejectDirectBooking(scheduleId + 1); // 꺼진 회차는 통과
  }

  @Test
  @DisplayName("다음 상태 확인 시간은 내 차례가 올 예상 시각까지이고 0.2~15초 안이다")
  void pollAfter_scalesWithAhead() {
    assertThat(queue.pollAfterMs(0)).isEqualTo(200); // 1명 ÷ 초당 200명 = 5ms → 최소 0.2초
    assertThat(queue.pollAfterMs(999)).isEqualTo(5_000); // 1,000명 ÷ 초당 200명 = 5초
    assertThat(queue.pollAfterMs(100_000)).isEqualTo(15_000);
    assertThat(queue.join(scheduleId, 1L, 1).pollAfterMs()).isEqualTo(200);
  }

  @Test
  @DisplayName("진입 · 확정하면 회차 키에 마지막 활동 기준 만료가 다시 걸린다")
  void joinAndAllocate_refreshIdleTtl() {
    queue.join(scheduleId, 1L, 1);
    queue.join(scheduleId, 2L, 1);
    capacity(10, 0);
    assertThat(stateKeyTtls())
        .allSatisfy(ttl -> assertThat(ttl).isBetween(1L, IDLE_TTL_SECONDS * 1_000));

    shortenTtl(10);
    queue.allocate(1);
    assertThat(stateKeyTtls()).allSatisfy(ttl -> assertThat(ttl).isGreaterThan(10_000L));
  }

  private long ttlMs(String key) {
    return redisson.getKeys().remainTimeToLive(key);
  }

  private List<String> stateKeys() {
    return List.of(
        ReservationQueueService.membersKey(scheduleId),
        ReservationQueueService.seqKey(scheduleId),
        ReservationQueueService.waitingKey(scheduleId));
  }

  private List<Long> stateKeyTtls() {
    return stateKeys().stream().map(this::ttlMs).toList();
  }

  private void shortenTtl(long seconds) {
    for (String key : stateKeys()) {
      redisson.getKeys().expire(key, seconds, TimeUnit.SECONDS);
    }
  }
}
