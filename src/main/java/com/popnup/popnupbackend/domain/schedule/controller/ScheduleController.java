package com.popnup.popnupbackend.domain.schedule.controller;

import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleCreateRequest;
import com.popnup.popnupbackend.domain.schedule.dto.response.ScheduleResponse;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleService;
import com.popnup.popnupbackend.global.common.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ScheduleController {

  private final ScheduleService scheduleService;

  // 사용자 - 특정 팝업의 날짜별 스케줄 목록 조회
  @GetMapping("/popups/{popupId}/schedules")
  public ResponseEntity<ApiResponse<List<ScheduleResponse>>> getSchedules(
          @PathVariable Long popupId,
          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    List<ScheduleResponse> responses = scheduleService.getScheduleByDate(popupId, date);
    return ResponseEntity.ok(ApiResponse.success("스케줄 목록 조회 성공", responses));
  }

  // 스케줄 단건 등록
  @PostMapping("/admin/schedules")
  public ResponseEntity<ApiResponse<Long>> createSchedule(
          @Valid @RequestBody ScheduleCreateRequest request) {
    Long scheduleId = scheduleService.createSchedule(request);
    return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success("스케줄이 등록되었습니다.", scheduleId));
  }

  // 타임 슬롯 활성화/비활성화 상태 변경
  @PatchMapping("/admin/schedules/{scheduleId}/status")
  public ResponseEntity<ApiResponse<Void>> updateScheduleStatus(
          @PathVariable Long scheduleId, @RequestParam boolean isActive) {
    scheduleService.updateScheduleStatus(scheduleId, isActive);
    return ResponseEntity.ok(ApiResponse.success("스케줄 상태가 변경되었습니다.", null));
  }

  // 스케줄 삭제
  @DeleteMapping("/admin/schedules/{scheduleId}")
  public ResponseEntity<ApiResponse<Void>> deleteSchedule(@PathVariable Long scheduleId) {
    scheduleService.deleteSchedule(scheduleId);
    return ResponseEntity.ok(ApiResponse.success("스케줄이 삭제되었습니다.", null));
  }
}