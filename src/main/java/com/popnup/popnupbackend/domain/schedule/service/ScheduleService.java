package com.popnup.popnupbackend.domain.schedule.service;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleBatchCreateRequest;
import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleCreateRequest;
import com.popnup.popnupbackend.domain.schedule.dto.response.ScheduleResponse;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScheduleService {

  private final ScheduleRepository scheduleRepository;
  private final PopupRepository popupRepository;
  private final ScheduleCapacityCache scheduleCapacityCache;
  private final ScheduleListCache scheduleListCache;
  private final TransactionTemplate readOnlyTransaction;

  public ScheduleService(
      ScheduleRepository scheduleRepository,
      PopupRepository popupRepository,
      ScheduleCapacityCache scheduleCapacityCache,
      ScheduleListCache scheduleListCache,
      PlatformTransactionManager transactionManager) {
    this.scheduleRepository = scheduleRepository;
    this.popupRepository = popupRepository;
    this.scheduleCapacityCache = scheduleCapacityCache;
    this.scheduleListCache = scheduleListCache;
    this.readOnlyTransaction = new TransactionTemplate(transactionManager);
    this.readOnlyTransaction.setReadOnly(true);
  }

  public List<ScheduleResponse> getScheduleByDate(Long popupId, LocalDate date) {
    LocalDateTime now = LocalDateTime.now();
    return findInCache(popupId, date, now).orElseGet(() -> loadFromDb(popupId, date, now));
  }

  private Optional<List<ScheduleResponse>> findInCache(
      Long popupId, LocalDate date, LocalDateTime now) {
    if (!scheduleListCache.enabled()) {
      return Optional.empty();
    }
    return scheduleListCache
        .get(popupId, date)
        .map(snapshots -> snapshots.stream().map(snapshot -> snapshot.toResponse(now)).toList());
  }

  private List<ScheduleResponse> loadFromDb(Long popupId, LocalDate date, LocalDateTime now) {
    List<Schedule> schedules =
        readOnlyTransaction.execute(
            status -> scheduleRepository.findActiveSchedulesByDate(popupId, date));
    if (scheduleListCache.enabled()) {
      scheduleListCache.put(popupId, date, schedules);
    }
    return schedules.stream().map(schedule -> ScheduleResponse.from(schedule, now)).toList();
  }

  @Transactional
  public Long createSchedule(ScheduleCreateRequest request) {
    Popup popup =
        findCreatablePopup(
            request.getPopupId(),
            request.getScheduleDate(),
            request.getStartTime(),
            request.getEndTime());

    Schedule schedule =
        Schedule.createSchedule(
            popup,
            request.getScheduleDate(),
            request.getStartTime(),
            request.getEndTime(),
            request.getMaxCapacity());

    return saveSchedules(popup, request.getScheduleDate(), List.of(schedule)).get(0).getId();
  }

  @Transactional
  public int createBatchSchedules(ScheduleBatchCreateRequest request) {
    Popup popup =
        findCreatablePopup(
            request.getPopupId(),
            request.getScheduleDate(),
            request.getOpenTime(),
            request.getCloseTime());

    List<Schedule> schedules =
        Schedule.createSlots(
            popup,
            request.getScheduleDate(),
            request.getOpenTime(),
            request.getCloseTime(),
            request.getIntervalMinutes(),
            request.getMaxCapacity());

    return saveSchedules(popup, request.getScheduleDate(), schedules).size();
  }

  // 저장한 회차의 Redis 정원 키를 만들고 그날 일정 목록 캐시를 지운다 (모두 커밋 후)
  private List<Schedule> saveSchedules(
      Popup popup, LocalDate scheduleDate, List<Schedule> schedules) {
    List<Schedule> saved = scheduleRepository.saveAll(schedules);
    scheduleCapacityCache.initAfterCommit(saved);
    scheduleListCache.evictAfterCommit(popup.getId(), scheduleDate);
    return saved;
  }

  @Transactional
  public void updateScheduleStatus(Long scheduleId, boolean isActive) {
    Schedule schedule =
        scheduleRepository
            .findById(scheduleId)
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);
    schedule.updateActiveStatus(isActive);
    evictScheduleList(schedule);
  }

  @Transactional
  public void deleteSchedule(Long scheduleId) {
    Schedule schedule =
        scheduleRepository
            .findByIdWithPessimisticLock(scheduleId)
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

    if (schedule.getNowCapacity() > 0) {
      throw ScheduleErrorCode.CANNOT_DELETE_RESERVED_SCHEDULE.toException();
    }

    scheduleRepository.delete(schedule);
    evictScheduleList(schedule);
  }

  private void evictScheduleList(Schedule schedule) {
    if (scheduleListCache.enabled()) {
      scheduleListCache.evictAfterCommit(schedule.getPopup().getId(), schedule.getScheduleDate());
    }
  }

  private Popup findCreatablePopup(
      Long popupId, LocalDate scheduleDate, LocalTime startTime, LocalTime endTime) {
    Popup popup =
        popupRepository
            .findById(popupId)
            .orElseThrow(ScheduleErrorCode.POPUP_NOT_FOUND::toException);

    validateScheduleWithinPopupPeriod(popup, scheduleDate);

    if (scheduleRepository.existOverlappingSchedule(
        popup.getId(), scheduleDate, startTime, endTime)) {
      throw ScheduleErrorCode.DUPLICATE_TIME_SLOT.toException();
    }
    return popup;
  }

  private void validateScheduleWithinPopupPeriod(Popup popup, LocalDate scheduleDate) {
    if (scheduleDate.isBefore(popup.getStartDate()) || scheduleDate.isAfter(popup.getEndDate())) {
      throw ScheduleErrorCode.SCHEDULE_OUT_OF_POPUP_PERIOD.toException();
    }
  }
}
