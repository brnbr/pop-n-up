package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

  @Mock private ScheduleRepository scheduleRepository;
  @Mock private PopupRepository popupRepository;

  @InjectMocks private ScheduleService scheduleService;

  private Popup openPopup;

  @BeforeEach
  void setUp() {
    openPopup =
        Popup.builder()
            .title("테스트 팝업")
            .category(PopupCategory.ETC)
            .region("서울")
            .address("서울시 강남구")
            .startDate(LocalDate.now().minusDays(5))
            .endDate(LocalDate.now().plusDays(30))
            .isFree(true)
            .price(0)
            .status(PopupStatus.OPEN)
            .build();
    ReflectionTestUtils.setField(openPopup, "id", 1L);
  }

  // ScheduleCreateRequest는 생성자/빌더가 없는 @Getter 전용 DTO라 Mockito.mock()으로 stub
  private ScheduleCreateRequest mockCreateRequest(
      Long popupId,
      LocalDate scheduleDate,
      LocalTime startTime,
      LocalTime endTime,
      int maxCapacity) {
    ScheduleCreateRequest request = Mockito.mock(ScheduleCreateRequest.class);
    given(request.getPopupId()).willReturn(popupId);
    given(request.getScheduleDate()).willReturn(scheduleDate);
    given(request.getStartTime()).willReturn(startTime);
    given(request.getEndTime()).willReturn(endTime);
    given(request.getMaxCapacity()).willReturn(maxCapacity);
    return request;
  }

  @Nested
  @DisplayName("getScheduleByDate 검증")
  class GetScheduleByDate {

    @Test
    @DisplayName("활성 스케줄 목록을 ScheduleResponse로 변환해 반환한다")
    void success() {
      LocalDate date = LocalDate.now().plusDays(1);
      Schedule schedule =
          Schedule.createSchedule(openPopup, date, LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
      given(scheduleRepository.findActiveSchedulesByDate(1L, date)).willReturn(List.of(schedule));

      log.info("[getScheduleByDate.success] input(popupId=1, date={})", date);

      List<ScheduleResponse> result = scheduleService.getScheduleByDate(1L, date);

      log.info("[getScheduleByDate.success] expectedSize=1 actualSize={}", result.size());

      assertThat(result).hasSize(1);
      assertThat(result.get(0).getPopupTitle()).isEqualTo("테스트 팝업");
    }
  }

  @Nested
  @DisplayName("createSchedule 검증")
  class CreateSchedule {

    @Test
    @DisplayName("정상 등록 시 스케줄이 저장된다")
    void success() {
      LocalDate scheduleDate = LocalDate.now().plusDays(1);
      ScheduleCreateRequest request =
          mockCreateRequest(1L, scheduleDate, LocalTime.of(10, 0), LocalTime.of(11, 0), 10);

      given(popupRepository.findById(1L)).willReturn(Optional.of(openPopup));
      given(
              scheduleRepository.existOverlappingSchedule(
                  1L, scheduleDate, LocalTime.of(10, 0), LocalTime.of(11, 0)))
          .willReturn(false);
      given(scheduleRepository.save(any(Schedule.class)))
          .willAnswer(
              invocation -> {
                Schedule s = invocation.getArgument(0);
                ReflectionTestUtils.setField(s, "id", 200L);
                return s;
              });

      log.info("[createSchedule.success] input(popupId=1, date={})", scheduleDate);

      Long resultId = scheduleService.createSchedule(request);

      log.info("[createSchedule.success] expectedId=200 actualId={}", resultId);

      assertThat(resultId).isEqualTo(200L);
    }

    @Test
    @DisplayName("팝업이 존재하지 않으면 예외 발생")
    void popupNotFound() {
      ScheduleCreateRequest request = Mockito.mock(ScheduleCreateRequest.class);
      given(request.getPopupId()).willReturn(999L);
      given(popupRepository.findById(999L)).willReturn(Optional.empty());

      log.info("[createSchedule.popupNotFound] input(popupId=999)");

      assertThrows(PopupNotFoundException.class, () -> scheduleService.createSchedule(request));

      log.info("[createSchedule.popupNotFound] expected PopupNotFoundException thrown");
    }

    @Test
    @DisplayName("팝업 운영 기간을 벗어나면 예외 발생")
    void outsidePopupPeriod() {
      LocalDate outOfRangeDate = openPopup.getEndDate().plusDays(1);
      ScheduleCreateRequest request = Mockito.mock(ScheduleCreateRequest.class);
      given(request.getPopupId()).willReturn(1L);
      given(request.getScheduleDate()).willReturn(outOfRangeDate);
      given(popupRepository.findById(1L)).willReturn(Optional.of(openPopup));

      log.info(
          "[createSchedule.outsidePopupPeriod] input(scheduleDate={}, popupEndDate={})",
          outOfRangeDate,
          openPopup.getEndDate());

      assertThrows(IllegalArgumentException.class, () -> scheduleService.createSchedule(request));

      log.info("[createSchedule.outsidePopupPeriod] expected IllegalArgumentException thrown");
    }

    @Test
    @DisplayName("시간대가 겹치면 예외 발생")
    void overlapping() {
      LocalDate scheduleDate = LocalDate.now().plusDays(1);

      ScheduleCreateRequest request = Mockito.mock(ScheduleCreateRequest.class);
      given(request.getPopupId()).willReturn(1L);
      given(request.getScheduleDate()).willReturn(scheduleDate);
      given(request.getStartTime()).willReturn(LocalTime.of(10, 0));
      given(request.getEndTime()).willReturn(LocalTime.of(11, 0));

      given(popupRepository.findById(1L)).willReturn(Optional.of(openPopup));
      given(
              scheduleRepository.existOverlappingSchedule(
                  1L, scheduleDate, LocalTime.of(10, 0), LocalTime.of(11, 0)))
          .willReturn(true);

      log.info("[createSchedule.overlapping] input(popupId=1, date={})", scheduleDate);

      ServiceException exception =
          assertThrows(ServiceException.class, () -> scheduleService.createSchedule(request));

      log.info(
          "[createSchedule.overlapping] expectedErrorCode={} actualErrorCode={}",
          ScheduleErrorCode.DUPLICATE_TIME_SLOT,
          exception.getErrorCode());

      assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.DUPLICATE_TIME_SLOT);
    }

    @Nested
    @DisplayName("createBatchSchedules 검증")
    class CreateBatchSchedules {

      @Test
      @DisplayName("openTime~closeTime을 interval 단위로 쪼개 여러 스케줄을 생성한다")
      void success() {
        LocalDate scheduleDate = LocalDate.now().plusDays(1);
        // ScheduleBatchCreateRequest 필드 순서: popupId, scheduleDate, openTime, closeTime,
        // intervalMinutes, maxCapacity
        ScheduleBatchCreateRequest request =
            new ScheduleBatchCreateRequest(
                1L, scheduleDate, LocalTime.of(10, 0), LocalTime.of(12, 0), 60, 15);

        given(popupRepository.findById(1L)).willReturn(Optional.of(openPopup));
        given(scheduleRepository.existOverlappingSchedule(any(), any(), any(), any()))
            .willReturn(false);
        given(scheduleRepository.saveAll(any()))
            .willAnswer(invocation -> invocation.getArgument(0));

        log.info(
            "[createBatchSchedules.success] input(openTime=10:00, closeTime=12:00, interval=60)");

        int createdCount = scheduleService.createBatchSchedules(request);

        log.info("[createBatchSchedules.success] expectedCount=2 actualCount={}", createdCount);

        assertThat(createdCount).isEqualTo(2);
      }

      @Test
      @DisplayName("closeTime이 openTime보다 빠르면 예외 발생")
      void invalidTimeRange() {
        ScheduleBatchCreateRequest request =
            new ScheduleBatchCreateRequest(
                1L, LocalDate.now().plusDays(1), LocalTime.of(12, 0), LocalTime.of(10, 0), 60, 15);

        log.info("[createBatchSchedules.invalidTimeRange] input(openTime=12:00, closeTime=10:00)");

        ServiceException exception =
            assertThrows(
                ServiceException.class, () -> scheduleService.createBatchSchedules(request));

        log.info(
            "[createBatchSchedules.invalidTimeRange] expectedErrorCode={} actualErrorCode={}",
            ScheduleErrorCode.INVALID_TIME_RANGE,
            exception.getErrorCode());

        assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.INVALID_TIME_RANGE);
      }

      @Test
      @DisplayName("intervalMinutes가 0 이하면 예외 발생")
      void invalidInterval() {
        ScheduleBatchCreateRequest request =
            new ScheduleBatchCreateRequest(
                1L, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), 0, 15);

        log.info("[createBatchSchedules.invalidInterval] input(intervalMinutes=0)");

        ServiceException exception =
            assertThrows(
                ServiceException.class, () -> scheduleService.createBatchSchedules(request));

        log.info(
            "[createBatchSchedules.invalidInterval] expectedErrorCode={} actualErrorCode={}",
            ScheduleErrorCode.INVALID_TIME_RANGE,
            exception.getErrorCode());

        assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.INVALID_TIME_RANGE);
      }

      @Test
      @DisplayName("팝업이 존재하지 않으면 예외 발생")
      void popupNotFound() {
        ScheduleBatchCreateRequest request =
            new ScheduleBatchCreateRequest(
                999L,
                LocalDate.now().plusDays(1),
                LocalTime.of(10, 0),
                LocalTime.of(12, 0),
                60,
                15);
        given(popupRepository.findById(999L)).willReturn(Optional.empty());

        log.info("[createBatchSchedules.popupNotFound] input(popupId=999)");

        assertThrows(
            PopupNotFoundException.class, () -> scheduleService.createBatchSchedules(request));

        log.info("[createBatchSchedules.popupNotFound] expected PopupNotFoundException thrown");
      }

      @Test
      @DisplayName("팝업 운영 기간을 벗어나면 예외 발생")
      void outsidePopupPeriod() {
        LocalDate outOfRangeDate = openPopup.getEndDate().plusDays(1);
        ScheduleBatchCreateRequest request =
            new ScheduleBatchCreateRequest(
                1L, outOfRangeDate, LocalTime.of(10, 0), LocalTime.of(12, 0), 60, 15);
        given(popupRepository.findById(1L)).willReturn(Optional.of(openPopup));

        log.info(
            "[createBatchSchedules.outsidePopupPeriod] input(scheduleDate={}, popupEndDate={})",
            outOfRangeDate,
            openPopup.getEndDate());

        assertThrows(
            IllegalArgumentException.class, () -> scheduleService.createBatchSchedules(request));

        log.info(
            "[createBatchSchedules.outsidePopupPeriod] expected IllegalArgumentException thrown");
      }

      @Test
      @DisplayName("시간대가 겹치면 예외 발생")
      void overlapping() {
        LocalDate scheduleDate = LocalDate.now().plusDays(1);
        ScheduleBatchCreateRequest request =
            new ScheduleBatchCreateRequest(
                1L, scheduleDate, LocalTime.of(10, 0), LocalTime.of(12, 0), 60, 15);
        given(popupRepository.findById(1L)).willReturn(Optional.of(openPopup));
        given(
                scheduleRepository.existOverlappingSchedule(
                    1L, scheduleDate, LocalTime.of(10, 0), LocalTime.of(12, 0)))
            .willReturn(true);

        log.info("[createBatchSchedules.overlapping] input(popupId=1, date={})", scheduleDate);

        ServiceException exception =
            assertThrows(
                ServiceException.class, () -> scheduleService.createBatchSchedules(request));

        log.info(
            "[createBatchSchedules.overlapping] expectedErrorCode={} actualErrorCode={}",
            ScheduleErrorCode.DUPLICATE_TIME_SLOT,
            exception.getErrorCode());

        assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.DUPLICATE_TIME_SLOT);
      }
    }

    @Nested
    @DisplayName("updateScheduleStatus 검증")
    class UpdateScheduleStatus {

      @Test
      @DisplayName("정상적으로 활성 상태를 변경한다")
      void success() {
        Schedule schedule =
            Schedule.createSchedule(
                openPopup,
                LocalDate.now().plusDays(1),
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
                10);
        given(scheduleRepository.findById(200L)).willReturn(Optional.of(schedule));

        log.info("[updateScheduleStatus.success] input(scheduleId=200, isActive=false)");

        scheduleService.updateScheduleStatus(200L, false);

        log.info(
            "[updateScheduleStatus.success] expectedActive=false actualActive={}",
            schedule.isActive());

        assertThat(schedule.isActive()).isFalse();
      }

      @Test
      @DisplayName("스케줄이 존재하지 않으면 예외 발생")
      void notFound() {
        given(scheduleRepository.findById(999L)).willReturn(Optional.empty());

        log.info("[updateScheduleStatus.notFound] input(scheduleId=999)");

        ServiceException exception =
            assertThrows(
                ServiceException.class, () -> scheduleService.updateScheduleStatus(999L, true));

        log.info(
            "[updateScheduleStatus.notFound] expectedErrorCode={} actualErrorCode={}",
            ScheduleErrorCode.SCHEDULE_NOT_FOUND,
            exception.getErrorCode());

        assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
      }
    }

    @Nested
    @DisplayName("deleteSchedule 검증")
    class DeleteSchedule {

      @Test
      @DisplayName("예약자가 없으면 정상 삭제된다")
      void success() {
        Schedule schedule =
            Schedule.createSchedule(
                openPopup,
                LocalDate.now().plusDays(1),
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
                10);
        given(scheduleRepository.findByIdWithPessimisticLock(200L))
            .willReturn(Optional.of(schedule));

        log.info("[deleteSchedule.success] input(scheduleId=200, nowCapacity=0)");

        scheduleService.deleteSchedule(200L);

        log.info("[deleteSchedule.success] verify scheduleRepository.delete called");

        verify(scheduleRepository, times(1)).delete(schedule);
      }

      @Test
      @DisplayName("예약자가 있으면 삭제할 수 없다")
      void cannotDeleteReserved() {
        Schedule schedule =
            Schedule.createSchedule(
                openPopup,
                LocalDate.now().plusDays(1),
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
                10);
        schedule.addReservation(1, LocalDateTime.now());
        given(scheduleRepository.findByIdWithPessimisticLock(200L))
            .willReturn(Optional.of(schedule));

        log.info(
            "[deleteSchedule.cannotDeleteReserved] input(scheduleId=200, nowCapacity={})",
            schedule.getNowCapacity());

        ServiceException exception =
            assertThrows(ServiceException.class, () -> scheduleService.deleteSchedule(200L));

        log.info(
            "[deleteSchedule.cannotDeleteReserved] expectedErrorCode={} actualErrorCode={}",
            ScheduleErrorCode.CANNOT_DELETE_RESERVED_SCHEDULE,
            exception.getErrorCode());

        assertThat(exception.getErrorCode())
            .isEqualTo(ScheduleErrorCode.CANNOT_DELETE_RESERVED_SCHEDULE);
        verify(scheduleRepository, never()).delete(any());
      }

      @Test
      @DisplayName("스케줄이 존재하지 않으면 예외 발생")
      void notFound() {
        given(scheduleRepository.findByIdWithPessimisticLock(999L)).willReturn(Optional.empty());

        log.info("[deleteSchedule.notFound] input(scheduleId=999)");

        ServiceException exception =
            assertThrows(ServiceException.class, () -> scheduleService.deleteSchedule(999L));

        log.info(
            "[deleteSchedule.notFound] expectedErrorCode={} actualErrorCode={}",
            ScheduleErrorCode.SCHEDULE_NOT_FOUND,
            exception.getErrorCode());

        assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
      }
    }
  }
}
