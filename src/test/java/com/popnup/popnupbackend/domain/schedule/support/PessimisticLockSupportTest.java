package com.popnup.popnupbackend.domain.schedule.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.querydsl.jpa.impl.JPAQuery;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryTimeoutException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;

class PessimisticLockSupportTest {

  private JPAQuery<String> query;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    query = mock(JPAQuery.class);
    given(query.setLockMode(any(LockModeType.class))).willReturn(query);
    given(query.setHint(any(), any())).willReturn(query);
  }

  @Test
  @DisplayName("쓰기 잠금과 쿼리 타임아웃(3초) 힌트를 걸어 조회한다")
  void appliesWriteLockAndQueryTimeout() {
    given(query.fetchOne()).willReturn("row");

    Optional<String> result = PessimisticLockSupport.fetchOneForUpdate(query);

    assertThat(result).contains("row");
    verify(query).setLockMode(LockModeType.PESSIMISTIC_WRITE);
    verify(query)
        .setHint(
            eq("jakarta.persistence.query.timeout"),
            eq(PessimisticLockSupport.LOCK_WAIT_TIMEOUT_MILLIS));
    assertThat(PessimisticLockSupport.LOCK_WAIT_TIMEOUT_MILLIS).isEqualTo(3000);
  }

  @Test
  @DisplayName("조회 결과가 없으면 빈 Optional을 반환한다")
  void emptyWhenNoRow() {
    given(query.fetchOne()).willReturn(null);

    assertThat(PessimisticLockSupport.fetchOneForUpdate(query)).isEmpty();
  }

  @Test
  @DisplayName("대기 시간을 넘겨 쿼리가 취소되면 락 획득 실패 예외로 바꿔 던진다 (409 LOCK_TIMEOUT으로 응답되는 예외)")
  void queryTimeoutBecomesLockFailure() {
    QueryTimeoutException timeout = new QueryTimeoutException();
    given(query.fetchOne()).willThrow(timeout);

    assertThatThrownBy(() -> PessimisticLockSupport.fetchOneForUpdate(query))
        .isInstanceOf(CannotAcquireLockException.class)
        .isInstanceOf(PessimisticLockingFailureException.class)
        .hasCause(timeout);
  }
}
