package com.popnup.popnupbackend.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.popnup.popnupbackend.domain.payment.enums.PaymentStatus;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import org.junit.jupiter.api.Test;

class PaymentTest {

  @Test
  void Payment_생성시_READY_상태로_생성된다() {
    // given
    Reservation reservation = mock(Reservation.class);

    // when
    Payment payment = new Payment(reservation, "R20260914TEST", 10000);

    // then
    assertThat(payment.getReservation()).isSameAs(reservation);

    assertThat(payment.getOrderId()).isEqualTo("R20260914TEST");

    assertThat(payment.getAmount()).isEqualTo(10000);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
  }

  @Test
  void 결제_승인시_PAID_상태가_된다() {
    // given
    Payment payment = new Payment(mock(Reservation.class), "R20260914TEST", 10000);

    // when
    payment.approve();

    // then
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
  }

  @Test
  void 결제_실패시_FAILED_상태가_된다() {
    // given
    Payment payment = new Payment(mock(Reservation.class), "R20260914TEST", 10000);

    // when
    payment.fail();

    // then
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
  }

  @Test
  void 카카오페이_tid를_저장할_수_있다() {
    // given
    Payment payment = new Payment(mock(Reservation.class), "R20260914TEST", 10000);

    // when
    payment.setTid("T123456789");

    // then
    assertThat(payment.getTid()).isEqualTo("T123456789");
  }
}
