package com.popnup.popnupbackend.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationCancelManagerTest {

  @InjectMocks private ReservationCancelManager cancelManager;
  @Mock private ScheduleRepository scheduleRepository;

  @Test
  @DisplayName("cancel: 스케줄 비관적 락을 획득하고 예약 상태를 CANCELED로 만든 뒤 좌석을 원복한다")
  void cancel_Success() {
    Schedule schedule =
        Schedule.createSchedule(
            null, LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
    ReflectionTestUtils.setField(schedule, "id", 1L);
    schedule.addReservation(4); // nowCapacity = 4

    Reservation reservation = Reservation.createReservation("R1", null, schedule, 4);

    given(scheduleRepository.findByIdWithPessimisticLock(1L)).willReturn(Optional.of(schedule));

    cancelManager.cancel(reservation);

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    assertThat(schedule.getNowCapacity()).isEqualTo(0);
    verify(scheduleRepository).findByIdWithPessimisticLock(1L);
  }
}
