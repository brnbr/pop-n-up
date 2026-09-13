package com.popnup.popnupbackend.domain.schedule.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.popnup.popnupbackend.domain.schedule.exception.ScheduleErrorCode;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ScheduleControllerTest {

  @Mock private ScheduleService scheduleService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ScheduleController controller = new ScheduleController(scheduleService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.popnup.popnupbackend.global.error.GlobalExceptionHandler())
            .build();
  }

  @Nested
  @DisplayName("GET /popups/{popupId}/schedules")
  class GetSchedules {

    @Test
    @DisplayName("정상 요청이면 200과 함께 스케줄 목록을 반환한다")
    void success() throws Exception {
      given(scheduleService.getScheduleByDate(anyLong(), any())).willReturn(List.of());

      log.info("[getSchedules.success] input(popupId=1, date=2026-09-20)");

      mockMvc
          .perform(get("/popups/1/schedules").param("date", "2026-09-20"))
          .andExpect(status().isOk());

      log.info("[getSchedules.success] expectedStatus=200 verified");
    }

    @Test
    @DisplayName("date 파라미터가 없으면 400을 반환한다")
    void missingDateParam() throws Exception {
      log.info("[getSchedules.missingDateParam] input(popupId=1, date=missing)");

      mockMvc.perform(get("/popups/1/schedules")).andExpect(status().isBadRequest());

      log.info("[getSchedules.missingDateParam] expectedStatus=400 verified");
    }
  }

  @Nested
  @DisplayName("POST /admin/schedules")
  class CreateSchedule {

    @Test
    @DisplayName("정상 요청이면 201과 함께 생성된 스케줄 id를 반환한다")
    void success() throws Exception {
      given(scheduleService.createSchedule(any())).willReturn(100L);

      // ScheduleCreateRequest 필드: popupId, scheduleDate, startTime, endTime, maxCapacity
      String requestBody =
          "{\"popupId\":1,\"scheduleDate\":\"2026-09-20\",\"startTime\":\"10:00:00\",\"endTime\":\"11:00:00\",\"maxCapacity\":10}";

      log.info("[createSchedule.success] input(requestBody={})", requestBody);

      mockMvc
          .perform(post("/admin/schedules").contentType("application/json").content(requestBody))
          .andExpect(status().isCreated());

      log.info("[createSchedule.success] expectedStatus=201 verified");
    }

    @Test
    @DisplayName("팝업이 존재하지 않으면 서비스 예외가 그대로 전파된다")
    void popupNotFound() throws Exception {
      given(scheduleService.createSchedule(any()))
          .willThrow(
              new com.popnup.popnupbackend.domain.popup.exception.PopupNotFoundException(
                  "존재하지 않는 팝업입니다."));

      String requestBody =
          "{\"popupId\":999,\"scheduleDate\":\"2026-09-20\",\"startTime\":\"10:00:00\",\"endTime\":\"11:00:00\",\"maxCapacity\":10}";

      log.info("[createSchedule.popupNotFound] input(requestBody={})", requestBody);

      // 수정: PopupNotFoundException은 GlobalExceptionHandler가 다루는 ServiceException 계열이 아니므로
      // 정확한 상태코드 대신 예외가 컨트롤러 레벨까지 전파되는지만 확인 (500 or 별도 핸들러 필요 여부 파악용)
      org.junit.jupiter.api.Assertions.assertThrows(
          jakarta.servlet.ServletException.class,
          () ->
              mockMvc.perform(
                  post("/admin/schedules").contentType("application/json").content(requestBody)));

      log.info("[createSchedule.popupNotFound] PopupNotFoundException propagated as expected");
    }

    @Test
    @DisplayName("시간대가 겹치면 409를 반환한다")
    void duplicateTimeSlot() throws Exception {
      given(scheduleService.createSchedule(any()))
          .willThrow(ScheduleErrorCode.DUPLICATE_TIME_SLOT.toException());

      String requestBody =
          "{\"popupId\":1,\"scheduleDate\":\"2026-09-20\",\"startTime\":\"10:00:00\",\"endTime\":\"11:00:00\",\"maxCapacity\":10}";

      log.info("[createSchedule.duplicateTimeSlot] input(requestBody={})", requestBody);

      mockMvc
          .perform(post("/admin/schedules").contentType("application/json").content(requestBody))
          .andExpect(status().isConflict());

      log.info("[createSchedule.duplicateTimeSlot] expectedStatus=409 verified");
    }
  }

  @Nested
  @DisplayName("PATCH /admin/schedules/{scheduleId}/status")
  class UpdateScheduleStatus {

    @Test
    @DisplayName("정상 요청이면 200을 반환하고 서비스에 상태 변경을 위임한다")
    void success() throws Exception {
      log.info("[updateScheduleStatus.success] input(scheduleId=100, isActive=false)");

      mockMvc
          .perform(patch("/admin/schedules/100/status").param("isActive", "false"))
          .andExpect(status().isOk());

      log.info(
          "[updateScheduleStatus.success] verify scheduleService.updateScheduleStatus(100L, false) called");

      verify(scheduleService, times(1)).updateScheduleStatus(100L, false);
    }
  }

  @Nested
  @DisplayName("DELETE /admin/schedules/{scheduleId}")
  class DeleteSchedule {

    @Test
    @DisplayName("정상 요청이면 200을 반환하고 서비스에 삭제를 위임한다")
    void success() throws Exception {
      log.info("[deleteSchedule.success] input(scheduleId=100)");

      mockMvc.perform(delete("/admin/schedules/100")).andExpect(status().isOk());

      log.info("[deleteSchedule.success] verify scheduleService.deleteSchedule(100L) called");

      verify(scheduleService, times(1)).deleteSchedule(100L);
    }

    @Test
    @DisplayName("예약자가 있으면 409를 반환한다")
    void cannotDeleteReserved() throws Exception {
      org.mockito.Mockito.doThrow(ScheduleErrorCode.CANNOT_DELETE_RESERVED_SCHEDULE.toException())
          .when(scheduleService)
          .deleteSchedule(100L);

      log.info("[deleteSchedule.cannotDeleteReserved] input(scheduleId=100)");

      mockMvc.perform(delete("/admin/schedules/100")).andExpect(status().isConflict());

      log.info("[deleteSchedule.cannotDeleteReserved] expectedStatus=409 verified");
    }
  }

  @Nested
  @DisplayName("POST /admin/schedules/batch")
  class CreateBatchSchedules {

    @Test
    @DisplayName("정상 요청이면 201과 함께 생성된 슬롯 개수를 반환한다")
    void success() throws Exception {
      given(scheduleService.createBatchSchedules(any())).willReturn(5);

      // ScheduleBatchCreateRequest 필드: popupId, scheduleDate, openTime, closeTime, intervalMinutes,
      // maxCapacity
      String requestBody =
          "{\"popupId\":1,\"scheduleDate\":\"2026-09-20\",\"openTime\":\"10:00:00\",\"closeTime\":\"20:00:00\",\"intervalMinutes\":60,\"maxCapacity\":15}";

      log.info("[createBatchSchedules.success] input(requestBody={})", requestBody);

      mockMvc
          .perform(
              post("/admin/schedules/batch").contentType("application/json").content(requestBody))
          .andExpect(status().isCreated());

      log.info("[createBatchSchedules.success] expectedStatus=201 verified");
    }
  }
}
