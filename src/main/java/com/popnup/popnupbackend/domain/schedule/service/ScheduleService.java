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

  // 사용자 - 특정 팝업의 날짜별 스케줄 목록 조회
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

    validateScheduleWithinPopupPeriod(popup, request.getScheduleDate());

    if (scheduleRepository.existOverlappingSchedule(
        popup.getId(), request.getScheduleDate(), request.getStartTime(), request.getEndTime())) {
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
      throw ScheduleErrorCode.SCHEDULE_TIME_OVERLAPPED.toException();
    }

    List<Schedule> schedules = new ArrayList<>();
    LocalTime currentStartTime = request.getOpenTime();

    // openTime부터 시작해 interval 단위로 슬롯 생성
    while (currentStartTime.isBefore(request.getCloseTime())) {
      LocalTime currentEndTime = currentStartTime.plusMinutes(request.getIntervalMinutes());

      // 다음 종료 시각이 운영 종료 시각을 넘어서거나, 24시 자정을 넘어 시간이 역전되는 경우 중단
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

      // 무한 루프 방지용. 다음 슬롯 생성을 위해 시작 시간을 현재 종료 시간으로 전진
      currentStartTime = currentEndTime;
    }

    // 생성된 슬롯이 진짜 없는지 루프 끝나고 다시 검사
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

  // 팝업 운영 기간 유효성 검증 공통 메서드
  private void validateScheduleWithinPopupPeriod(Popup popup, LocalDate scheduleDate) {
    if (scheduleDate.isBefore(popup.getStartDate()) || scheduleDate.isAfter(popup.getEndDate())) {
      throw new IllegalArgumentException("스케줄 날짜는 팝업 운영 기간 내여야 합니다.");
    }
  }
}
