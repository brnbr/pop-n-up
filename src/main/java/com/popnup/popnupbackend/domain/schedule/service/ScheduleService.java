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
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScheduleService {

  private final ScheduleRepository scheduleRepository;
  private final PopupRepository popupRepository;
  private final ScheduleCapacityCache scheduleCapacityCache;

  @Transactional(readOnly = true)
  public List<ScheduleResponse> getScheduleByDate(Long popupId, LocalDate date) {
    List<Schedule> schedules = scheduleRepository.findActiveSchedulesByDate(popupId, date);
    LocalDateTime now = LocalDateTime.now();

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

    scheduleRepository.saveAll(schedules);
    return schedules.size();
  }

  @Transactional
  public void updateScheduleStatus(Long scheduleId, boolean isActive) {
    Schedule schedule =
        scheduleRepository
            .findById(scheduleId)
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);
    schedule.updateActiveStatus(isActive);
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
  }

  private void validateScheduleWithinPopupPeriod(Popup popup, LocalDate scheduleDate) {
    if (scheduleDate.isBefore(popup.getStartDate()) || scheduleDate.isAfter(popup.getEndDate())) {
      throw new IllegalArgumentException("스케줄 날짜는 팝업 운영 기간 내여야 합니다.");
    }
  }
}
