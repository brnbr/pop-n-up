package com.popnup.popnupbackend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

  @InjectMocks private ScheduleService scheduleService;

  @Mock private ScheduleRepository scheduleRepository;
  @Mock private PopupRepository popupRepository;
  @Mock private ScheduleCapacityCache scheduleCapacityCache;
  @Mock private ScheduleListCache scheduleListCache;
  @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

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

      given(popupRepository.findById(popupId)).willReturn(Optional.of(popup));
      given(scheduleRepository.existOverlappingSchedule(popupId, scheduleDate, startTime, endTime))
          .willReturn(false);
      given(scheduleRepository.saveAll(anyList())).willReturn(List.of(savedSchedule));

      // when
      Long createdId = scheduleService.createSchedule(request);

      // then
      assertThat(createdId).isEqualTo(100L);
      verify(scheduleRepository).saveAll(anyList());
      verify(scheduleCapacityCache).initAfterCommit(List.of(savedSchedule));
      verify(scheduleListCache).evictAfterCommit(popupId, scheduleDate);
    }

    @Test
    @DisplayName("실패: 팝업이 존재하지 않으면 POPUP_NOT_FOUND 예외가 발생한다")
    void createSchedule_popupNotFound() {
      // given
      ScheduleCreateRequest request = new ScheduleCreateRequest();
      ReflectionTestUtils.setField(request, "popupId", 999L);

      given(popupRepository.findById(anyLong())).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> scheduleService.createSchedule(request))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.POPUP_NOT_FOUND));
    }

    @Test
    @DisplayName("실패: 스케줄 날짜가 팝업 운영 기간 이전이거나 이후이면 SCHEDULE_OUT_OF_POPUP_PERIOD 예외가 발생한다")
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
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.SCHEDULE_OUT_OF_POPUP_PERIOD));
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
    @DisplayName("성공: 저장된 모든 회차의 Redis 용량 키를 초기화한다 (누락 시 사전 필터가 꺼진다)")
    void createBatchSchedules_initsRedisCapacityForEverySlot() {
      // given
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 20);
      LocalTime openTime = LocalTime.of(10, 0);
      LocalTime closeTime = LocalTime.of(13, 0);
      int maxCapacity = 20;

      ScheduleBatchCreateRequest request = new ScheduleBatchCreateRequest();
      ReflectionTestUtils.setField(request, "popupId", popupId);
      ReflectionTestUtils.setField(request, "scheduleDate", scheduleDate);
      ReflectionTestUtils.setField(request, "openTime", openTime);
      ReflectionTestUtils.setField(request, "closeTime", closeTime);
      ReflectionTestUtils.setField(request, "intervalMinutes", 60);
      ReflectionTestUtils.setField(request, "maxCapacity", maxCapacity);

      Popup popup = mock(Popup.class);
      given(popup.getId()).willReturn(popupId);
      given(popup.getStartDate()).willReturn(LocalDate.of(2026, 9, 1));
      given(popup.getEndDate()).willReturn(LocalDate.of(2026, 9, 30));
      given(popupRepository.findById(popupId)).willReturn(Optional.of(popup));
      given(scheduleRepository.existOverlappingSchedule(popupId, scheduleDate, openTime, closeTime))
          .willReturn(false);

      // 저장 시 ID가 부여되는 것처럼 흉내 낸다 (100, 101, 102)
      given(scheduleRepository.saveAll(anyList()))
          .willAnswer(
              invocation -> {
                List<Schedule> saved = invocation.getArgument(0);
                long id = 100L;
                for (Schedule schedule : saved) {
                  ReflectionTestUtils.setField(schedule, "id", id++);
                }
                return saved;
              });

      // when
      scheduleService.createBatchSchedules(request);

      // then: 3개 슬롯이 한 번의 호출로 모두 초기화되어야 한다
      @SuppressWarnings("unchecked")
      ArgumentCaptor<List<Schedule>> initialized = ArgumentCaptor.forClass(List.class);
      verify(scheduleCapacityCache, times(1)).initAfterCommit(initialized.capture());
      assertThat(initialized.getValue())
          .extracting(Schedule::getId, Schedule::getScheduleDate, Schedule::getMaxCapacity)
          .containsExactly(
              tuple(100L, scheduleDate, maxCapacity),
              tuple(101L, scheduleDate, maxCapacity),
              tuple(102L, scheduleDate, maxCapacity));
    }
  }

  @Nested
  @DisplayName("스케줄 삭제 [deleteSchedule]")
  class DeleteScheduleTest {

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

  @Nested
  @DisplayName("일정 목록 캐시 [getScheduleByDate]")
  class ScheduleListCacheTest {

    private final LocalDate date = LocalDate.of(2031, 5, 10);

    private Schedule realSchedule() {
      Popup popup = mock(Popup.class);
      given(popup.getTitle()).willReturn("팝업");
      return Schedule.createSchedule(popup, date, LocalTime.of(10, 0), LocalTime.of(11, 0), 10);
    }

    @Test
    @DisplayName("캐시를 끄면 캐시를 읽지도 쓰지도 않고 DB만 조회한다")
    void disabled_usesDbOnly() {
      given(scheduleListCache.enabled()).willReturn(false);
      given(scheduleRepository.findActiveSchedulesByDate(1L, date)).willReturn(List.of());

      scheduleService.getScheduleByDate(1L, date);

      verify(scheduleListCache, never()).get(any(), any());
      verify(scheduleListCache, never()).put(any(), any(), any());
    }

    @Test
    @DisplayName("캐시에 있으면 DB를 조회하지 않는다")
    void hit_skipsDb() {
      given(scheduleListCache.enabled()).willReturn(true);
      given(scheduleListCache.get(1L, date))
          .willReturn(
              Optional.of(
                  List.of(
                      new ScheduleListCache.Snapshot(
                          7L, "팝업", date, LocalTime.of(10, 0), LocalTime.of(11, 0), 10, 3, true))));

      var result = scheduleService.getScheduleByDate(1L, date);

      assertThat(result).hasSize(1);
      assertThat(result.get(0).getRemainingCapacity()).isEqualTo(7);
      verify(scheduleRepository, never()).findActiveSchedulesByDate(any(), any());
    }

    @Test
    @DisplayName("캐시에 없으면 DB를 조회하고 결과를 캐시에 넣는다")
    void miss_queriesDbAndPuts() {
      Schedule schedule = realSchedule();
      given(scheduleListCache.enabled()).willReturn(true);
      given(scheduleListCache.get(1L, date)).willReturn(Optional.empty());
      given(scheduleRepository.findActiveSchedulesByDate(1L, date)).willReturn(List.of(schedule));

      var result = scheduleService.getScheduleByDate(1L, date);

      assertThat(result).hasSize(1);
      verify(scheduleListCache).put(1L, date, List.of(schedule));
    }

    @Test
    @DisplayName("캐시에서 꺼낸 응답은 DB에서 만든 응답과 같다 (시작 전 · 시작 후)")
    void snapshotMatchesDbResponse() {
      Schedule schedule = realSchedule();
      var snapshot = ScheduleListCache.Snapshot.from(schedule);

      for (var now :
          List.of(
              java.time.LocalDateTime.of(2031, 5, 10, 9, 0),
              java.time.LocalDateTime.of(2031, 5, 10, 10, 30))) {
        assertThat(snapshot.toResponse(now))
            .usingRecursiveComparison()
            .isEqualTo(ScheduleResponse.from(schedule, now));
      }
    }
  }

  @Nested
  @DisplayName("타임 슬롯 분할 규칙 [createBatchSchedules]")
  class BatchSlotGenerationTest {

    private final LocalDate date = LocalDate.of(2026, 9, 20);

    private ScheduleBatchCreateRequest request(
        LocalTime open, LocalTime close, int interval, int capacity) {
      ScheduleBatchCreateRequest request = new ScheduleBatchCreateRequest();
      ReflectionTestUtils.setField(request, "popupId", 1L);
      ReflectionTestUtils.setField(request, "scheduleDate", date);
      ReflectionTestUtils.setField(request, "openTime", open);
      ReflectionTestUtils.setField(request, "closeTime", close);
      ReflectionTestUtils.setField(request, "intervalMinutes", interval);
      ReflectionTestUtils.setField(request, "maxCapacity", capacity);
      return request;
    }

    private void popupExists() {
      Popup popup = mock(Popup.class);
      given(popup.getId()).willReturn(1L);
      given(popup.getStartDate()).willReturn(LocalDate.of(2026, 9, 1));
      given(popup.getEndDate()).willReturn(LocalDate.of(2026, 9, 30));
      given(popupRepository.findById(1L)).willReturn(Optional.of(popup));
    }

    @SuppressWarnings("unchecked")
    private List<Schedule> saved() {
      ArgumentCaptor<List<Schedule>> captor = ArgumentCaptor.forClass(List.class);
      verify(scheduleRepository).saveAll(captor.capture());
      return captor.getValue();
    }

    private void stubSaveAll() {
      lenient()
          .when(scheduleRepository.saveAll(anyList()))
          .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("간격에 맞춰 연속된 슬롯으로 나눈다 (10:00~13:00, 60분 → 3개)")
    void splitsIntoConsecutiveSlots() {
      popupExists();
      stubSaveAll();

      int count =
          scheduleService.createBatchSchedules(
              request(LocalTime.of(10, 0), LocalTime.of(13, 0), 60, 20));

      assertThat(count).isEqualTo(3);
      List<Schedule> slots = saved();
      assertThat(slots)
          .extracting(Schedule::getStartTime)
          .containsExactly(LocalTime.of(10, 0), LocalTime.of(11, 0), LocalTime.of(12, 0));
      assertThat(slots)
          .extracting(Schedule::getEndTime)
          .containsExactly(LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(13, 0));
      assertThat(slots)
          .allSatisfy(
              slot -> {
                assertThat(slot.getScheduleDate()).isEqualTo(date);
                assertThat(slot.getMaxCapacity()).isEqualTo(20);
                assertThat(slot.getNowCapacity()).isZero();
                assertThat(slot.isActive()).isTrue();
              });
    }

    @Test
    @DisplayName("마감 시각에 못 미치는 마지막 남은 구간은 슬롯으로 만들지 않는다 (10:00~12:30, 60분 → 2개)")
    void dropsTrailingPartialSlot() {
      popupExists();
      stubSaveAll();

      int count =
          scheduleService.createBatchSchedules(
              request(LocalTime.of(10, 0), LocalTime.of(12, 30), 60, 20));

      assertThat(count).isEqualTo(2);
      assertThat(saved())
          .extracting(Schedule::getEndTime)
          .containsExactly(LocalTime.of(11, 0), LocalTime.of(12, 0));
    }

    @Test
    @DisplayName("자정을 넘기는 슬롯은 만들지 않는다 (22:00~23:59, 30분 → 22:00, 22:30, 23:00 시작 3개)")
    void doesNotCrossMidnight() {
      popupExists();
      stubSaveAll();

      int count =
          scheduleService.createBatchSchedules(
              request(LocalTime.of(22, 0), LocalTime.of(23, 59), 30, 20));

      assertThat(count).isEqualTo(3);
      assertThat(saved())
          .extracting(Schedule::getStartTime)
          .containsExactly(LocalTime.of(22, 0), LocalTime.of(22, 30), LocalTime.of(23, 0));
    }

    @Test
    @DisplayName("마지막 슬롯이 자정으로 넘어가면 그 슬롯만 빠진다 (23:00~23:59, 60분 → 없음 → INVALID_TIME_RANGE)")
    void onlySlotCrossingMidnight_isInvalid() {
      popupExists();
      stubSaveAll();

      assertThatThrownBy(
              () ->
                  scheduleService.createBatchSchedules(
                      request(LocalTime.of(23, 0), LocalTime.of(23, 59), 60, 20)))
          .isInstanceOf(ServiceException.class)
          .satisfies(
              e ->
                  assertThat(((ServiceException) e).getErrorCode())
                      .isEqualTo(ScheduleErrorCode.INVALID_TIME_RANGE));
    }
  }
}
