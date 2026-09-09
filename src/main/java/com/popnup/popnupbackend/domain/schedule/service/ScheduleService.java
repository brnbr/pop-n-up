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

  // 사용자 - 특정 팝업의 날짜별 스케쥴 목록 조회
  @Transactional(readOnly = true)
  public List<ScheduleResponse> getScheduleByDate(Long popupId, LocalDate date) {
    List<Schedule> schedules = scheduleRepository.findActiveSchedulesByDate(popupId, date);
    LocalDateTime now = LocalDateTime.now();

    return schedules.stream().map(schedule -> ScheduleResponse.from(schedule, now)).toList();
  }

  // 스케줄 단 건 등록
  @Transactional
  public Long createSchedule(ScheduleCreateRequest request) {
    Popup popup =
        popupRepository
            .findById(request.getPopupId())
            .orElseThrow(() -> new PopupNotFoundException("존재하지 않는 팝업입니다."));

    if (scheduleRepository.existOverlappingSchedule(
            popup.getId(),
            request.getScheduleDate(),
            request.getStartTime(),
            request.getEndTime()
    )) {
      throw ScheduleErrorCode.SCHEDULE_TIME_OVERLAPPED.toException();
    }

    Schedule schedule =
        Schedule.createSchedule(
            popup,
            request.getScheduleDate(),
            request.getStartTime(),
            request.getEndTime(),
            request.getMaxCapacity());

    Schedule savedSchedule = scheduleRepository.save(schedule);
    return savedSchedule.getId();
  }

  // 타임 슬롯 일괄 생성
  @Transactional
  public int createBatchSchedules(ScheduleBatchCreateRequest request) {
    if (!request.getCloseTime().isAfter(request.getOpenTime())) {
      throw ScheduleErrorCode.INVALID_TIME_RANGE.toException();
    }

    Popup popup =
        popupRepository
            .findById(request.getPopupId())
            .orElseThrow(() -> new PopupNotFoundException("존재하지 않는 팝업입니다."));

    if (scheduleRepository.existOverlappingSchedule(
            popup.getId(),
            request.getScheduleDate(),
            request.getOpenTime(),
            request.getCloseTime())) {
      throw ScheduleErrorCode.SCHEDULE_TIME_OVERLAPPED.toException();
    }

    List<Schedule> schedules = new ArrayList<>();
    LocalTime currentStartTime = request.getOpenTime();

    // openTime부터 시작해 interval 단위로 슬롯을 쪼갬
    while (true) {
      LocalTime currentEndTime = currentStartTime.plusMinutes(request.getIntervalMinutes());

      // 다음 종료 시각이 운영 종료 시각을 넘어서면 중단
      if (currentEndTime.isAfter(request.getCloseTime())) {
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

      // 자정을 넘어가거나 closeTime에 도달한 경우
      if (currentEndTime.equals(request.getCloseTime())
          || currentEndTime.isBefore(currentStartTime)) {
        break;
      }
      currentStartTime = currentEndTime;
    }

    if (schedules.isEmpty()) {
      throw ScheduleErrorCode.INVALID_TIME_RANGE.toException();
    }

    scheduleRepository.saveAll(schedules);
    return schedules.size();
  }

  // 타임 슬롯 활성화/비활성화
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
            .findById(scheduleId)
            .orElseThrow(ScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);

    if (schedule.getNowCapacity() > 0) {
      throw ScheduleErrorCode.CANNOT_DELETE_RESERVED_SCHEDULE.toException();
    }

    scheduleRepository.delete(schedule);
  }
}
