package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.exception.PopupNotFoundException;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleBatchCreateRequest;
import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleCreateRequest;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
    properties = {
      "jwt.secret=784dK3Hk+sSIkd37at/v1xMeOYLDaZviwulL2vHU0KvM5PK2cRAjbNMCodkD88gw7O6ueENnWQ94AG0WztDLCA=="
    })
@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

  @InjectMocks private ScheduleService scheduleService;

  @Mock private ScheduleRepository scheduleRepository;
  @Mock private PopupRepository popupRepository;
  @Mock private ScheduleCapacityCache scheduleCapacityCache;

  @Nested
  @DisplayName("스케줄 단건 등록 [createSchedule]")
  class CreateScheduleTest {

    @Test
    @DisplayName("성공: 유효한 요청 시 스케줄이 저장되고 Redis 캐시가 초기화된다")
    void createSchedule_success() {
      // given
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 20);
      LocalTime startTime = LocalTime.of(10, 0);
      LocalTime endTime = LocalTime.of(11, 0);
      int maxCapacity = 30;

      ScheduleCreateRequest request = new ScheduleCreateRequest();
      ReflectionTestUtils.setField(request, "popupId", popupId);
      ReflectionTestUtils.setField(request, "scheduleDate", scheduleDate);
      ReflectionTestUtils.setField(request, "startTime", startTime);
      ReflectionTestUtils.setField(request, "endTime", endTime);
      ReflectionTestUtils.setField(request, "maxCapacity", maxCapacity);

      Popup popup = mock(Popup.class);
      given(popup.getId()).willReturn(popupId);
      given(popup.getStartDate()).willReturn(LocalDate.of(2026, 9, 1));
      given(popup.getEndDate()).willReturn(LocalDate.of(2026, 9, 30));

      Schedule savedSchedule = mock(Schedule.class);
      given(savedSchedule.getId()).willReturn(100L);
      given(savedSchedule.getScheduleDate()).willReturn(scheduleDate);

      given(popupRepository.findById(popupId)).willReturn(Optional.of(popup));
      given(scheduleRepository.existOverlappingSchedule(popupId, scheduleDate, startTime, endTime))
          .willReturn(false);
      given(scheduleRepository.save(any(Schedule.class))).willReturn(savedSchedule);

      // when
      Long createdId = scheduleService.createSchedule(request);

      // then
      assertThat(createdId).isEqualTo(100L);
      verify(scheduleRepository).save(any(Schedule.class));
      verify(scheduleCapacityCache).init(100L, scheduleDate, maxCapacity);
    }

    @Test
    @DisplayName("실패: 팝업이 존재하지 않으면 PopupNotFoundException이 발생한다")
    void createSchedule_popupNotFound() {
      // given
      ScheduleCreateRequest request = new ScheduleCreateRequest();
      ReflectionTestUtils.setField(request, "popupId", 999L);

      given(popupRepository.findById(anyLong())).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> scheduleService.createSchedule(request))
          .isInstanceOf(PopupNotFoundException.class);
    }

    @Test
    @DisplayName("실패: 스케줄 날짜가 팝업 운영 기간 이전이거나 이후이면 IllegalArgumentException이 발생한다")
    void createSchedule_outOfPeriod() {
      // given
      Long popupId = 1L;
      ScheduleCreateRequest request = new ScheduleCreateRequest();
      ReflectionTestUtils.setField(request, "popupId", popupId);
      ReflectionTestUtils.setField(request, "scheduleDate", LocalDate.of(2026, 10, 15));

      Popup popup = mock(Popup.class);
      given(popup.getStartDate()).willReturn(LocalDate.of(2026, 9, 1));
      given(popup.getEndDate()).willReturn(LocalDate.of(2026, 9, 30));

      given(popupRepository.findById(popupId)).willReturn(Optional.of(popup));

      // when & then
      assertThatThrownBy(() -> scheduleService.createSchedule(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("스케줄 날짜는 팝업 운영 기간 내여야 합니다.");
    }

    @Test
    @DisplayName("실패: 겹치는 시간대의 스케줄이 존재하면 DUPLICATE_TIME_SLOT 예외가 발생한다")
    void createSchedule_duplicateTimeSlot() {
      // given
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 20);
      LocalTime startTime = LocalTime.of(10, 0);
      LocalTime endTime = LocalTime.of(11, 0);

      ScheduleCreateRequest request = new ScheduleCreateRequest();
      ReflectionTestUtils.setField(request, "popupId", popupId);
      ReflectionTestUtils.setField(request, "scheduleDate", scheduleDate);
      ReflectionTestUtils.setField(request, "startTime", startTime);
      ReflectionTestUtils.setField(request, "endTime", endTime);

      Popup popup = mock(Popup.class);
      given(popup.getId()).willReturn(popupId);
      given(popup.getStartDate()).willReturn(LocalDate.of(2026, 9, 1));
      given(popup.getEndDate()).willReturn(LocalDate.of(2026, 9, 30));

      given(popupRepository.findById(popupId)).willReturn(Optional.of(popup));
      given(scheduleRepository.existOverlappingSchedule(popupId, scheduleDate, startTime, endTime))
          .willReturn(true);

      // when & then
      assertThatThrownBy(() -> scheduleService.createSchedule(request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.DUPLICATE_TIME_SLOT));
    }
  }

  @Nested
  @DisplayName("타임 슬롯 일괄 등록 [createBatchSchedules]")
  class CreateBatchSchedulesTest {

    @Test
    @DisplayName("성공: 간격에 맞게 분할된 스케줄들이 한 번에 저장되고 생성된 개수를 반환한다")
    void createBatchSchedules_success() {
      // given
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 20);
      LocalTime openTime = LocalTime.of(10, 0);
      LocalTime closeTime = LocalTime.of(13, 0);
      int intervalMinutes = 60;
      int maxCapacity = 20;

      ScheduleBatchCreateRequest request = new ScheduleBatchCreateRequest();
      ReflectionTestUtils.setField(request, "popupId", popupId);
      ReflectionTestUtils.setField(request, "scheduleDate", scheduleDate);
      ReflectionTestUtils.setField(request, "openTime", openTime);
      ReflectionTestUtils.setField(request, "closeTime", closeTime);
      ReflectionTestUtils.setField(request, "intervalMinutes", intervalMinutes);
      ReflectionTestUtils.setField(request, "maxCapacity", maxCapacity);

      Popup popup = mock(Popup.class);
      given(popup.getId()).willReturn(popupId);
      given(popup.getStartDate()).willReturn(LocalDate.of(2026, 9, 1));
      given(popup.getEndDate()).willReturn(LocalDate.of(2026, 9, 30));

      given(popupRepository.findById(popupId)).willReturn(Optional.of(popup));
      given(scheduleRepository.existOverlappingSchedule(popupId, scheduleDate, openTime, closeTime))
          .willReturn(false);

      // when
      int createdCount = scheduleService.createBatchSchedules(request);

      // then: 10:00~11:00, 11:00~12:00, 12:00~13:00 -> 총 3개 슬롯
      assertThat(createdCount).isEqualTo(3);
      verify(scheduleRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("실패: 종료 시간이 시작 시간보다 같거나 빠르면 INVALID_TIME_RANGE 예외가 발생한다")
    void createBatchSchedules_invalidTimeRange() {
      // given
      ScheduleBatchCreateRequest request = new ScheduleBatchCreateRequest();
      ReflectionTestUtils.setField(request, "openTime", LocalTime.of(15, 0));
      ReflectionTestUtils.setField(request, "closeTime", LocalTime.of(13, 0));

      // when & then
      assertThatThrownBy(() -> scheduleService.createBatchSchedules(request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.INVALID_TIME_RANGE));
    }

    @Test
    @DisplayName("실패: 인터벌 간격이 0 이하이거나 null이면 INVALID_TIME_RANGE 예외가 발생한다")
    void createBatchSchedules_invalidInterval() {
      // given
      ScheduleBatchCreateRequest request = new ScheduleBatchCreateRequest();
      ReflectionTestUtils.setField(request, "openTime", LocalTime.of(10, 0));
      ReflectionTestUtils.setField(request, "closeTime", LocalTime.of(15, 0));
      ReflectionTestUtils.setField(request, "intervalMinutes", 0);

      // when & then
      assertThatThrownBy(() -> scheduleService.createBatchSchedules(request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.INVALID_TIME_RANGE));
    }
  }

  @Nested
  @DisplayName("타임 슬롯 활성화/비활성화 [updateScheduleStatus]")
  class UpdateScheduleStatusTest {

    @Test
    @DisplayName("성공: 스케줄을 조회하여 상태 변경 메서드를 호출한다")
    void updateScheduleStatus_success() {
      // given
      Long scheduleId = 10L;
      Schedule schedule = mock(Schedule.class);
      given(scheduleRepository.findById(scheduleId)).willReturn(Optional.of(schedule));

      // when
      scheduleService.updateScheduleStatus(scheduleId, false);

      // then
      verify(schedule).updateActiveStatus(false);
    }

    @Test
    @DisplayName("실패: 스케줄이 존재하지 않으면 SCHEDULE_NOT_FOUND 예외가 발생한다")
    void updateScheduleStatus_notFound() {
      // given
      Long scheduleId = 999L;
      given(scheduleRepository.findById(scheduleId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> scheduleService.updateScheduleStatus(scheduleId, true))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND));
    }
  }

  @Nested
  @DisplayName("스케줄 삭제 [deleteSchedule]")
  class DeleteScheduleTest {

    @Test
    @DisplayName("성공: 예약 인원이 없는 스케줄은 정상 삭제된다")
    void deleteSchedule_success() {
      // given
      Long scheduleId = 10L;
      Schedule schedule = mock(Schedule.class);
      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.of(schedule));
      given(schedule.getNowCapacity()).willReturn(0);

      // when
      scheduleService.deleteSchedule(scheduleId);

      // then
      verify(scheduleRepository).delete(schedule);
    }

    @Test
    @DisplayName("실패: 이미 예약 인원이 존재하는 스케줄은 CANNOT_DELETE_RESERVED_SCHEDULE 예외가 발생한다")
    void deleteSchedule_hasReservations() {
      // given
      Long scheduleId = 10L;
      Schedule schedule = mock(Schedule.class);
      given(scheduleRepository.findByIdWithPessimisticLock(scheduleId))
          .willReturn(Optional.of(schedule));
      given(schedule.getNowCapacity()).willReturn(3);

      // when & then
      assertThatThrownBy(() -> scheduleService.deleteSchedule(scheduleId))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.CANNOT_DELETE_RESERVED_SCHEDULE));

      verify(scheduleRepository, never()).delete(any(Schedule.class));
    }
  }
}
