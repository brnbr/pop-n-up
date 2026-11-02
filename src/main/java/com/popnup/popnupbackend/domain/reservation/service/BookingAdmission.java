package com.popnup.popnupbackend.domain.reservation.service;

import com.popnup.popnupbackend.domain.reservation.exception.BookingOverloadException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.api.ratelimiter.RateLimiterArgs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BookingAdmission {

  private static final String RATE_LIMITER_KEY = "reservation:booking:rate";

  private final boolean enabled;
  private final int maxConcurrent;
  private final long maxWaitMs;
  private final boolean rateLimitEnabled;
  private final long ratePerSecond;
  private final int retryAfterMin;
  private final int retryAfterMax;
  private final Semaphore permits;
  private final ObjectProvider<RedissonClient> redissonProvider;
  private final MeterRegistry meterRegistry;

  private volatile RRateLimiter rateLimiter;

  public BookingAdmission(
      @Value("${reservation.overload.enabled:true}") boolean enabled,
      @Value("${reservation.overload.max-concurrent:40}") int maxConcurrent,
      @Value("${reservation.overload.max-wait-ms:48}") long maxWaitMs,
      @Value("${reservation.overload.rate-limit.enabled:false}") boolean rateLimitEnabled,
      @Value("${reservation.overload.rate-limit.per-second:1596}") long ratePerSecond,
      @Value("${reservation.overload.retry-after-min-seconds:1}") int retryAfterMin,
      @Value("${reservation.overload.retry-after-max-seconds:3}") int retryAfterMax,
      ObjectProvider<RedissonClient> redissonProvider,
      MeterRegistry meterRegistry) {
    this.enabled = enabled;
    this.maxConcurrent = maxConcurrent;
    this.maxWaitMs = maxWaitMs;
    this.rateLimitEnabled = rateLimitEnabled;
    this.ratePerSecond = ratePerSecond;
    this.retryAfterMin = retryAfterMin;
    this.retryAfterMax = Math.max(retryAfterMin, retryAfterMax);
    this.permits = new Semaphore(maxConcurrent, true);
    this.redissonProvider = redissonProvider;
    this.meterRegistry = meterRegistry;
    Gauge.builder(
            "reservation.overload.in_flight",
            this,
            a -> a.maxConcurrent - a.permits.availablePermits())
        .register(meterRegistry);
    log.info(
        "[BookingAdmission] enabled={}, maxConcurrent={}, maxWaitMs={}, rateLimit={}({}/s)",
        enabled,
        maxConcurrent,
        maxWaitMs,
        rateLimitEnabled,
        ratePerSecond);
  }

  public <T> T run(Supplier<T> action) {
    if (!enabled) {
      return action.get();
    }
    if (rateLimitEnabled && !tryRate()) {
      throw reject("rate");
    }
    boolean acquired;
    try {
      acquired = permits.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw reject("interrupted");
    }
    if (!acquired) {
      throw reject("concurrency");
    }
    try {
      return action.get();
    } finally {
      permits.release();
    }
  }

  private boolean tryRate() {
    try {
      return rateLimiter().tryAcquire();
    } catch (Exception e) {
      if (restoreRate()) {
        try {
          return rateLimiter().tryAcquire();
        } catch (Exception again) {
          skipped("rate", again);
          return true;
        }
      }
      skipped("rate", e);
      return true;
    }
  }

  private final AtomicLong lastRateRestoreMs = new AtomicLong();

  private static boolean restoreTurn(AtomicLong last) {
    long now = System.currentTimeMillis();
    long prev = last.get();
    return now - prev >= 1_000 && last.compareAndSet(prev, now);
  }

  private boolean restoreRate() {
    if (!restoreTurn(lastRateRestoreMs)) {
      return false;
    }
    try {
      boolean created =
          rateLimiter().trySetRate(RateType.OVERALL, ratePerSecond, Duration.ofSeconds(1));
      if (created) {
        log.warn("[BookingAdmission] Redis에 입장량 설정이 없어 다시 만들었습니다 ({}/s)", ratePerSecond);
        meterRegistry.counter("reservation.overload.restored", "limiter", "rate").increment();
      }
      return created;
    } catch (Exception e) {
      return false;
    }
  }

  private RRateLimiter rateLimiter() {
    RRateLimiter r = rateLimiter;
    if (r == null) {
      synchronized (this) {
        if (rateLimiter == null) {
          RRateLimiter created = redissonProvider.getObject().getRateLimiter(RATE_LIMITER_KEY);
          created.setRate(
              RateLimiterArgs.of(RateType.OVERALL, ratePerSecond, Duration.ofSeconds(1)));
          rateLimiter = created;
        }
        r = rateLimiter;
      }
    }
    return r;
  }

  private BookingOverloadException reject(String reason) {
    meterRegistry.counter("reservation.overload.rejected", "reason", reason).increment();
    int retryAfter = ThreadLocalRandom.current().nextInt(retryAfterMin, retryAfterMax + 1);
    return new BookingOverloadException(retryAfter);
  }

  private volatile long lastSkipLogMs;

  private void skipped(String what, Exception e) {
    meterRegistry.counter("reservation.overload.skipped", "limiter", what).increment();
    long now = System.currentTimeMillis();
    if (now - lastSkipLogMs > 5_000) {
      lastSkipLogMs = now;
      log.warn("[BookingAdmission] Redis 오류로 {} 제한을 건너뜁니다: {}", what, e.toString());
    }
  }
}
