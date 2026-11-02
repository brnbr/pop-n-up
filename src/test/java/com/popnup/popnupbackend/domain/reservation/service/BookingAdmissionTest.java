package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.popnup.popnupbackend.domain.reservation.exception.BookingOverloadException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.ObjectProvider;

class BookingAdmissionTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ExecutorService pool = Executors.newSingleThreadExecutor();

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @SuppressWarnings("unchecked")
  private BookingAdmission admission(
      boolean enabled,
      int max,
      long waitMs,
      boolean rateLimit,
      ObjectProvider<RedissonClient> provider) {
    return new BookingAdmission(
        enabled,
        max,
        waitMs,
        rateLimit,
        100,
        1,
        3,
        provider == null ? mock(ObjectProvider.class) : provider,
        meterRegistry);
  }

  @Test
  @DisplayName("꺼져 있으면 그대로 실행한다")
  void disabled_passesThrough() {
    BookingAdmission a = admission(false, 1, 10, false, null);

    assertThat(a.run(() -> "ok")).isEqualTo("ok");
  }

  @Test
  @DisplayName("동시 처리 한도를 다 쓰고 대기 시간 안에 자리가 안 나면 503(Retry-After 1~3초)으로 거절한다")
  void full_rejectedWithRetryAfter() throws Exception {
    BookingAdmission a = admission(true, 1, 20, false, null);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pool.submit(
        () ->
            a.run(
                () -> {
                  holding.countDown();
                  try {
                    release.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return null;
                }));
    assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> a.run(() -> "second"))
        .isInstanceOf(BookingOverloadException.class)
        .satisfies(
            e -> assertThat(((BookingOverloadException) e).getRetryAfterSeconds()).isBetween(1, 3));
    assertThat(
            meterRegistry.counter("reservation.overload.rejected", "reason", "concurrency").count())
        .isEqualTo(1);

    release.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    assertThat(a.run(() -> "after")).isEqualTo("after"); // 반납된 퍼밋으로 다시 처리
  }

  @Test
  @DisplayName("실행 중 예외가 나도 퍼밋을 돌려준다")
  void exception_releasesPermit() {
    BookingAdmission a = admission(true, 1, 10, false, null);

    assertThatThrownBy(
            () ->
                a.run(
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(a.run(() -> "next")).isEqualTo("next");
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("전역 입장량 제한이 Redis 오류로 실패하면 건너뛰고 로컬 한도만 적용한다")
  void rateLimiter_redisError_skipped() {
    ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
    given(provider.getObject()).willThrow(new IllegalStateException("redis down"));
    BookingAdmission a = admission(true, 1, 10, true, provider);

    assertThat(a.run(() -> "ok")).isEqualTo("ok");
    assertThat(meterRegistry.counter("reservation.overload.skipped", "limiter", "rate").count())
        .isEqualTo(1);
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<RedissonClient> providerOf(RedissonClient client) {
    ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
    given(provider.getObject()).willReturn(client);
    return provider;
  }

  @Test
  @DisplayName("Redis에서 입장량 설정이 사라졌으면 다시 만들고 제한을 계속 적용한다")
  void rateLimiter_configLost_restored() {
    RedissonClient client = mock(RedissonClient.class);
    RRateLimiter limiter = mock(RRateLimiter.class);
    given(client.getRateLimiter(anyString())).willReturn(limiter);
    given(limiter.tryAcquire())
        .willThrow(new RedisException("ERR RateLimiter is not initialized"))
        .willReturn(false);
    given(limiter.trySetRate(eq(RateType.OVERALL), eq(100L), any(Duration.class))).willReturn(true);
    BookingAdmission a = admission(true, 1, 10, true, providerOf(client));

    assertThatThrownBy(() -> a.run(() -> "ok")).isInstanceOf(BookingOverloadException.class);
    assertThat(meterRegistry.counter("reservation.overload.rejected", "reason", "rate").count())
        .isEqualTo(1);
    assertThat(meterRegistry.counter("reservation.overload.skipped", "limiter", "rate").count())
        .isZero();
    assertThat(meterRegistry.counter("reservation.overload.restored", "limiter", "rate").count())
        .isEqualTo(1);
  }
}
