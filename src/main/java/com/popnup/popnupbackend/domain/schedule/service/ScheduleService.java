package com.popnup.popnupbackend.domain.schedule.service;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.exception.PopupNotFoundException;
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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ScheduleService {

  private final ScheduleRepository scheduleRepository;
  private final PopupRepository popupRepository;
  private final ScheduleCapacityCache scheduleCapacityCache;
  private final ScheduleListCache scheduleListCache;
  private final PlatformTransactionManager transactionManager;

  public List<ScheduleResponse> getScheduleByDate(Long popupId, LocalDate date) {
    LocalDateTime now = LocalDateTime.now();

    if (scheduleListCache.enabled()) {
      var cached = scheduleListCache.get(popupId, date);
      if (cached.isPresent()) {
        return cached.get().stream().map(snapshot -> snapshot.toResponse(now)).toList();
      }
    }

    TransactionTemplate readOnlyTx = new TransactionTemplate(transactionManager);
    readOnlyTx.setReadOnly(true);
    List<Schedule> schedules =
        readOnlyTx.execute(status -> scheduleRepository.findActiveSchedulesByDate(popupId, date));
    if (scheduleListCache.enabled()) {
      scheduleListCache.put(popupId, date, schedules);
    }

    return schedules.stream().map(schedule -> ScheduleResponse.from(schedule, now)).toList();
  }

  @Transactional
  public Long createSchedule(ScheduleCreateRequest request) {
    Popup popup =
        popupRepository
            .findById(request.getPopupId())
            .orElseThrow(() -> new PopupNotFoundException("존재하지 않는 팝업입니다."));

    validateScheduleWithinPopupPeriod(popup, request.getScheduleDate());

    if (scheduleRepository.existOverlappingSchedule(
        popup.getId(), request.getScheduleDate(), request.getStartTime(), request.getEndTime())) {
      throw ScheduleErrorCode.DUPLICATE_TIME_SLOT.toException();
    }

    Schedule schedule =
        Schedule.createSchedule(
            popup,
            request.getScheduleDate(),
            request.getStartTime(),
            request.getEndTime(),
            request.getMaxCapacity());

    Schedule savedSchedule = scheduleRepository.save(schedule);

    scheduleCapacityCache.init(
        savedSchedule.getId(), savedSchedule.getScheduleDate(), request.getMaxCapacity());
    scheduleListCache.evictAfterCommit(popup.getId(), savedSchedule.getScheduleDate());

    return savedSchedule.getId();
  }

  @Transactional
  public int createBatchSchedules(ScheduleBatchCreateRequest request) {
    if (!request.getCloseTime().isAfter(request.getOpenTime())) {
      throw ScheduleErrorCode.INVALID_TIME_RANGE.toException();
    }

    if (request.getIntervalMinutes() == null || request.getIntervalMinutes() <= 0) {
      throw ScheduleErrorCode.INVALID_TIME_RANGE.toException();
    }

    Popup popup =
        popupRepository
            .findById(request.getPopupId())
            .orElseThrow(() -> new PopupNotFoundException("존재하지 않는 팝업입니다."));

    validateScheduleWithinPopupPeriod(popup, request.getScheduleDate());

    if (scheduleRepository.existOverlappingSchedule(
        popup.getId(), request.getScheduleDate(), request.getOpenTime(), request.getCloseTime())) {
      throw ScheduleErrorCode.DUPLICATE_TIME_SLOT.toException();
    }

    List<Schedule> schedules = new ArrayList<>();
    LocalTime currentStartTime = request.getOpenTime();

    while (currentStartTime.isBefore(request.getCloseTime())) {
      LocalTime currentEndTime = currentStartTime.plusMinutes(request.getIntervalMinutes());

      if (currentEndTime.isAfter(request.getCloseTime())
          || currentEndTime.isBefore(currentStartTime)) {
        break;
      }

      Schedule schedule =
          Schedule.createSchedule(
              popup,
              request.getScheduleDate(),
              currentStartTime,
              currentEndTime,
              request.getMaxCapacity());
      schedules.add(schedule);

      currentStartTime = currentEndTime;
    }

    if (schedules.isEmpty()) {
      throw ScheduleErrorCode.INVALID_TIME_RANGE.toException();
    }

    List<Schedule> savedSchedules = scheduleRepository.saveAll(schedules);

    for (Schedule savedSchedule : savedSchedules) {
      scheduleCapacityCache.init(
          savedSchedule.getId(), savedSchedule.getScheduleDate(), request.getMaxCapacity());
    }
    scheduleListCache.evictAfterCommit(popup.getId(), request.getScheduleDate());
    return schedules.size();
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

  private void validateScheduleWithinPopupPeriod(Popup popup, LocalDate scheduleDate) {
    if (scheduleDate.isBefore(popup.getStartDate()) || scheduleDate.isAfter(popup.getEndDate())) {
      throw new IllegalArgumentException("스케줄 날짜는 팝업 운영 기간 내여야 합니다.");
    }
  }
}
