package com.popnup.popnupbackend.global.support;

import com.querydsl.jpa.impl.JPAQuery;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryTimeoutException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.CannotAcquireLockException;

public final class PessimisticLockSupport {

  public static final int LOCK_WAIT_TIMEOUT_MILLIS = 3000;

  private static final String QUERY_TIMEOUT_HINT = "jakarta.persistence.query.timeout";
  private static final String LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout";
  // Hibernate에서 잠금 대기 -2 = SKIP LOCKED (잠긴 행은 기다리지 않고 건너뜀)
  private static final int SKIP_LOCKED = -2;

  private PessimisticLockSupport() {}

  public static <T> Optional<T> fetchOneForUpdate(JPAQuery<T> query) {
    try {
      return Optional.ofNullable(
          query
              .setLockMode(LockModeType.PESSIMISTIC_WRITE)
              .setHint(QUERY_TIMEOUT_HINT, LOCK_WAIT_TIMEOUT_MILLIS)
              .fetchOne());
    } catch (QueryTimeoutException e) {
      throw new CannotAcquireLockException(
          "행 잠금을 " + LOCK_WAIT_TIMEOUT_MILLIS + "ms 안에 얻지 못했습니다.", e);
    }
  }

  public static <T> List<T> fetchForUpdateSkipLocked(JPAQuery<T> query) {
    return query
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .setHint(LOCK_TIMEOUT_HINT, SKIP_LOCKED)
        .fetch();
  }
}
