package com.popnup.popnupbackend.domain.schedule.service;

import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.popup.repository.PopupRepository;
import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleCreateRequest;
import com.popnup.popnupbackend.domain.schedule.dto.response.ScheduleResponse;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.domain.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final PopupRepository popupRepository;

    // 사용자 - 특정 팝업의 날짜별 스케쥴 목록 조회
    @Transactional(readOnly = true)
    public List<ScheduleResponse> getScheduleByDate(Long popupId, LocalDate date) {
        List<Schedule> schedules = scheduleRepository.findActiveSchedulesByDate(popupId, date);

        return schedules.stream().map(ScheduleResponse::from).toList();
    }

    // 스케쥴 단 건 등록
    @Transactional
    public Long createSchedule(ScheduleCreateRequest request) {
        Popup popup = popupRepository.findById(request.getPopupId()).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 팝업입니다."));

        Schedule schedule = Schedule.createSchedule
                (
                    popup,
                    request.getScheduleDate(),
                    request.getStartTime(),
                    request.getEndTime(),
                    request.getMaxCapacity()
                );

        Schedule savedSchedule = scheduleRepository.save(schedule);
        return savedSchedule.getId();
    }

    // 타임 슬롯 활성화/비활성화
    @Transactional
    public void updateScheduleStatus(Long scheduleId, boolean isActive) {
        Schedule schedule = scheduleRepository.findById(scheduleId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 스케줄입니다."));
        schedule.updateActiveStatus(isActive);
    }

    @Transactional
    public void deleteSchedule(Long scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 스케쥴입니다."));

        if (schedule.getNowCapacity() > 0) {
            throw new IllegalStateException("이미 예약자가 존재하는 스케줄은 삭제할 수 없습니다.");
        }

        scheduleRepository.delete(schedule);
    }
}