package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.AdminCursor;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepositoryCustom.MemberCursor;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReservationCursorTest {

  @Test
  @DisplayName("관리자 · 회원 커서는 인코딩한 값을 그대로 되돌린다")
  void roundTrip() {
    AdminCursor admin = new AdminCursor(LocalDate.of(2030, 1, 2), LocalTime.of(13, 0), 950_001L);
    MemberCursor member =
        new MemberCursor(LocalDateTime.of(2026, 3, 1, 0, 0, 0, 123_456_000), 933_381L);

    assertThat(ReservationCursor.decodeAdmin(ReservationCursor.encode(admin))).isEqualTo(admin);
    assertThat(ReservationCursor.decodeMember(ReservationCursor.encode(member))).isEqualTo(member);
  }

  @Test
  @DisplayName("빈 커서는 첫 페이지(null)")
  void blank_isFirstPage() {
    assertThat(ReservationCursor.decodeAdmin("")).isNull();
    assertThat(ReservationCursor.decodeMember(null)).isNull();
  }

  @Test
  @DisplayName("다른 목록의 커서를 넣으면 거절한다")
  void wrongKind_rejected() {
    String memberCursor =
        ReservationCursor.encode(new MemberCursor(LocalDateTime.of(2026, 1, 1, 0, 0), 1L));

    assertThatThrownBy(() -> ReservationCursor.decodeAdmin(memberCursor))
        .isInstanceOf(ServiceException.class)
        .extracting("errorCode")
        .isEqualTo(ReservationErrorCode.INVALID_CURSOR);
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-base64!!", "YTF8MjAzMC0wMS0wMg", "YTF8eHx5fHo"})
  @DisplayName("해석할 수 없는 커서는 INVALID_CURSOR")
  void garbage_rejected(String cursor) {
    assertThatThrownBy(() -> ReservationCursor.decodeAdmin(cursor))
        .isInstanceOf(ServiceException.class)
        .extracting("errorCode")
        .isEqualTo(ReservationErrorCode.INVALID_CURSOR);
  }
}
