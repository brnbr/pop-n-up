package com.popnup.popnupbackend.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.payment.entity.Payment;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
class PaymentRepositoryTest {

  @Autowired private PaymentRepository paymentRepository;

  @Autowired private EntityManager entityManager;

  @Test
  @Transactional
  void 하나의_예약에_Payment를_두개_생성할_수_없다() {

    // 1. 회원 생성
    Member member = Member.createLocal("test@test.com", "password", "테스트회원");

    entityManager.persist(member);

    // 2. 팝업 생성
    Popup popup =
        Popup.builder()
            .title("테스트 팝업")
            .description("테스트용 팝업")
            .category(PopupCategory.FASHION)
            .region("대구")
            .address("대구광역시")
            .startDate(java.time.LocalDate.now())
            .endDate(java.time.LocalDate.now().plusDays(10))
            .isFree(false)
            .price(10000)
            .status(PopupStatus.OPEN)
            .build();

    entityManager.persist(popup);

    // 3. 스케줄 생성
    Schedule schedule =
        Schedule.createSchedule(
            popup,
            java.time.LocalDate.now().plusDays(1),
            java.time.LocalTime.of(14, 0),
            java.time.LocalTime.of(15, 0),
            100);

    entityManager.persist(schedule);

    // 4. 예약 생성
    Reservation reservation = Reservation.createReservation("R20260914TEST", member, schedule, 1);

    entityManager.persist(reservation);

    entityManager.flush();

    // 5. 첫 번째 Payment 저장
    Payment payment1 = new Payment(reservation, "R20260914TEST", 10000);

    paymentRepository.save(payment1);

    entityManager.flush();

    // 6. 같은 예약으로 두 번째 Payment 생성

    Payment payment2 = new Payment(reservation, "R20260914TEST-2", 10000);

    assertThatThrownBy(
            () -> {
              paymentRepository.save(payment2);
              entityManager.flush();
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
