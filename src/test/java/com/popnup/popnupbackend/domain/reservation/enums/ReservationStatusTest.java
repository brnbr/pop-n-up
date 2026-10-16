package com.popnup.popnupbackend.domain.reservation.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationStatusTest {

  private List<ReservationStatus> statusesMatching(
      java.util.function.Predicate<ReservationStatus> rule) {
    return Arrays.stream(ReservationStatus.values()).filter(rule).toList();
  }

  @Test
  @DisplayName("확정할 수 있는 상태는 PENDING뿐이다")
  void canConfirm_onlyPending() {
    assertThat(statusesMatching(ReservationStatus::canConfirm))
        .containsExactly(ReservationStatus.PENDING);
  }

  @Test
  @DisplayName("체크인할 수 있는 상태는 CONFIRMED뿐이다")
  void canCheckIn_onlyConfirmed() {
    assertThat(statusesMatching(ReservationStatus::canCheckIn))
        .containsExactly(ReservationStatus.CONFIRMED);
  }

  @Test
  @DisplayName("끝난 상태는 CANCELED와 EXPIRED다 (USED는 포함하지 않는다)")
  void isEnded_canceledAndExpired() {
    assertThat(statusesMatching(ReservationStatus::isEnded))
        .containsExactly(ReservationStatus.CANCELED, ReservationStatus.EXPIRED);
  }
}
