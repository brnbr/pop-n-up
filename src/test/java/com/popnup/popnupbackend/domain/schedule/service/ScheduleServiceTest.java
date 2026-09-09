package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import com.popnup.popnupbackend.domain.popup.exception.PopupNotFoundException;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleBatchCreateRequest;
import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleCreateRequest;
import com.popnup.popnupbackend.domain.schedule.dto.response.ScheduleResponse;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

  @InjectMocks private ScheduleService scheduleService;

  @Mock private ScheduleRepository scheduleRepository;
  @Mock private PopupRepository popupRepository;

  private Popup popup;

  @BeforeEach
  void setUp() {
    popup =
        Popup.builder()
            .title("성수 팝업스토어")
            .category(PopupCategory.ETC)
            .startDate(LocalDate.of(2026, 10, 1))
            .endDate(LocalDate.of(2026, 10, 31))
            .status(PopupStatus.OPEN)
            .build();
    ReflectionTestUtils.setField(popup, "id", 1L);
  }

  @Nested
  @DisplayName("스케줄 날짜별 조회 [getScheduleByDate]")
  class GetScheduleByDate {

    @Test
    @DisplayName("성공: 활성화된 스케줄 목록을 ScheduleResponse DTO로 변환하여 반환한다")
    void success() {
      Schedule schedule =
          Schedule.createSchedule(
              popup, LocalDate.of(2026, 10, 5), LocalTime.of(12, 0), LocalTime.of(13, 0), 20);
      ReflectionTestUtils.setField(schedule, "id", 100L);

      given(scheduleRepository.findActiveSchedulesByDate(1L, LocalDate.of(2026, 10, 5)))
          .willReturn(List.of(schedule));

      List<ScheduleResponse> results =
          scheduleService.getScheduleByDate(1L, LocalDate.of(2026, 10, 5));

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getScheduleId()).isEqualTo(100L);
      assertThat(results.get(0).getPopupTitle()).isEqualTo("성수 팝업스토어");
      assertThat(results.get(0).getRemainingCapacity()).isEqualTo(20);
    }
  }

  @Nested
  @DisplayName("스케줄 단건 등록 [createSchedule]")
  class CreateSchedule {

    private ScheduleCreateRequest createRequest(
        LocalDate date, LocalTime start, LocalTime end, int capacity) {
      ScheduleCreateRequest req = new ScheduleCreateRequest();
      ReflectionTestUtils.setField(req, "popupId", 1L);
      ReflectionTestUtils.setField(req, "scheduleDate", date);
      ReflectionTestUtils.setField(req, "startTime", start);
      ReflectionTestUtils.setField(req, "endTime", end);
      ReflectionTestUtils.setField(req, "maxCapacity", capacity);
      return req;
    }

    @Test
    @DisplayName("성공: 겹치는 스케줄이 없고 운영 기간 내이면 저장 후 ID를 반환한다")
    void success() {
      ScheduleCreateRequest req =
          createRequest(LocalDate.of(2026, 10, 5), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);

      given(popupRepository.findById(1L)).willReturn(Optional.of(popup));
      given(
              scheduleRepository.existOverlappingSchedule(
                  1L, req.getScheduleDate(), req.getStartTime(), req.getEndTime()))
          .willReturn(false);

      given(scheduleRepository.save(any(Schedule.class)))
          .willAnswer(
              invocation -> {
                Schedule s = invocation.getArgument(0);
                ReflectionTestUtils.setField(s, "id", 100L);
                return s;
              });

      Long scheduleId = scheduleService.createSchedule(req);

      assertThat(scheduleId).isEqualTo(100L);
      verify(scheduleRepository).save(any(Schedule.class));
    }

    @Test
    @DisplayName("실패: 팝업이 존재하지 않으면 PopupNotFoundException이 발생한다")
    void popupNotFound() {
      ScheduleCreateRequest req =
          createRequest(LocalDate.of(2026, 10, 5), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
      given(popupRepository.findById(1L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> scheduleService.createSchedule(req))
          .isInstanceOf(PopupNotFoundException.class);
    }

    @Test
    @DisplayName("실패: 스케줄 날짜가 팝업 운영 기간 이전이면 IllegalArgumentException이 발생한다")
    void beforePopupPeriod() {
      ScheduleCreateRequest req =
          createRequest(LocalDate.of(2026, 9, 30), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
      given(popupRepository.findById(1L)).willReturn(Optional.of(popup));

      assertThatThrownBy(() -> scheduleService.createSchedule(req))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("스케줄 날짜는 팝업 운영 기간 내여야 합니다.");
    }

    @Test
    @DisplayName("실패: 해당 시간대에 이미 중복/겹치는 스케줄이 존재하면 SCHEDULE_TIME_OVERLAPPED 예외 발생")
    void overlappedSchedule() {
      ScheduleCreateRequest req =
          createRequest(LocalDate.of(2026, 10, 5), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
      given(popupRepository.findById(1L)).willReturn(Optional.of(popup));
      given(
              scheduleRepository.existOverlappingSchedule(
                  1L, req.getScheduleDate(), req.getStartTime(), req.getEndTime()))
          .willReturn(true);

      assertThatThrownBy(() -> scheduleService.createSchedule(req))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_TIME_OVERLAPPED);
              });
    }
  }

  @Nested
  @DisplayName("타임 슬롯 일괄 등록 [createBatchSchedules]")
  class CreateBatchSchedules {

    @Test
    @DisplayName("성공: 10:00부터 12:00까지 30분 간격 요청 시 4개의 슬롯이 생성된다")
    void success() {
      ScheduleBatchCreateRequest req =
          new ScheduleBatchCreateRequest(
              1L, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0), LocalTime.of(12, 0), 30, 10);

      given(popupRepository.findById(1L)).willReturn(Optional.of(popup));
      given(
              scheduleRepository.existOverlappingSchedule(
                  1L, req.getScheduleDate(), req.getOpenTime(), req.getCloseTime()))
          .willReturn(false);

      int createdCount = scheduleService.createBatchSchedules(req);

      assertThat(createdCount).isEqualTo(4);
      verify(scheduleRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("실패: 회차 간격보다 운영 시간이 짧아 슬롯이 1개도 안 만들어지면 INVALID_TIME_RANGE 예외 발생")
    void noSchedulesGenerated() {
      ScheduleBatchCreateRequest req =
          new ScheduleBatchCreateRequest(
              1L,
              LocalDate.of(2026, 10, 5),
              LocalTime.of(10, 0),
              LocalTime.of(10, 10), // 운영 시간이 10분인데
              30, // 간격은 30분
              10);

      given(popupRepository.findById(1L)).willReturn(Optional.of(popup));
      given(
              scheduleRepository.existOverlappingSchedule(
                  1L, req.getScheduleDate(), req.getOpenTime(), req.getCloseTime()))
          .willReturn(false);

      assertThatThrownBy(() -> scheduleService.createBatchSchedules(req))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode()).isEqualTo(ScheduleErrorCode.INVALID_TIME_RANGE);
              });
    }

    @Test
    @DisplayName("실패: 일괄 생성 시간 범위에 이미 겹치는 스케줄이 있으면 SCHEDULE_TIME_OVERLAPPED 예외 발생")
    void batchOverlapped() {
      ScheduleBatchCreateRequest req =
          new ScheduleBatchCreateRequest(
              1L, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0), LocalTime.of(12, 0), 30, 10);

      given(popupRepository.findById(1L)).willReturn(Optional.of(popup));
      given(
              scheduleRepository.existOverlappingSchedule(
                  1L, req.getScheduleDate(), req.getOpenTime(), req.getCloseTime()))
          .willReturn(true);

      assertThatThrownBy(() -> scheduleService.createBatchSchedules(req))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_TIME_OVERLAPPED);
              });
    }
  }

  @Nested
  @DisplayName("스케줄 활성화 상태 변경 [updateScheduleStatus]")
  class UpdateScheduleStatus {

    @Test
    @DisplayName("성공: 스케줄의 활성화 상태(isActive)를 변경한다")
    void success() {
      Schedule schedule =
          Schedule.createSchedule(
              popup, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
      ReflectionTestUtils.setField(schedule, "id", 100L);

      given(scheduleRepository.findById(100L)).willReturn(Optional.of(schedule));

      scheduleService.updateScheduleStatus(100L, false);

      assertThat(schedule.isActive()).isFalse();
    }

    @Test
    @DisplayName("실패: 스케줄이 존재하지 않으면 SCHEDULE_NOT_FOUND 예외 발생")
    void notFound() {
      given(scheduleRepository.findById(999L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> scheduleService.updateScheduleStatus(999L, false))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
              });
    }
  }

  @Nested
  @DisplayName("스케줄 삭제 [deleteSchedule]")
  class DeleteSchedule {

    @Test
    @DisplayName("성공: 현재 예약자(nowCapacity)가 0명이면 스케줄 삭제를 수행한다")
    void success() {
      Schedule schedule =
          Schedule.createSchedule(
              popup, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
      ReflectionTestUtils.setField(schedule, "id", 100L);

      given(scheduleRepository.findById(100L)).willReturn(Optional.of(schedule));

      scheduleService.deleteSchedule(100L);

      verify(scheduleRepository).delete(schedule);
    }

    @Test
    @DisplayName("실패: 현재 예약자(nowCapacity)가 1명 이상이면 CANNOT_DELETE_RESERVED_SCHEDULE 예외 발생")
    void cannotDeleteIfReserved() {
      Schedule schedule =
          Schedule.createSchedule(
              popup, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
      ReflectionTestUtils.setField(schedule, "id", 100L);
      schedule.addReservation(2); // 2명 예약 발생

      given(scheduleRepository.findById(100L)).willReturn(Optional.of(schedule));

      assertThatThrownBy(() -> scheduleService.deleteSchedule(100L))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e -> {
                ServiceException se = (ServiceException) e;
                assertThat(se.getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CANNOT_DELETE_RESERVED_SCHEDULE);
              });

      verify(scheduleRepository, never()).delete(any());
    }
  }
}
