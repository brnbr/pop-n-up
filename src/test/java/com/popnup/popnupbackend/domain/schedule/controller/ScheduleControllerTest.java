package com.popnup.popnupbackend.domain.schedule.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.popnup.popnupbackend.domain.schedule.dto.response.ScheduleResponse;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ScheduleControllerTest {

  private MockMvc mockMvc;

  @Mock private ScheduleService scheduleService;

  @InjectMocks private ScheduleController scheduleController;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    this.mockMvc =
        MockMvcBuilders.standaloneSetup(scheduleController).setValidator(validator).build();
  }

  @Nested
  @DisplayName("스케줄 목록 조회 [GET /popups/{popupId}/schedules]")
  class GetSchedules {

    @Test
    @DisplayName("성공: 특정 팝업과 날짜의 스케줄 목록을 200 OK와 함께 반환한다")
    void getSchedules_success() throws Exception {
      Long popupId = 1L;
      LocalDate date = LocalDate.of(2026, 9, 15);

      ScheduleResponse res =
          new ScheduleResponse(
              100L,
              "성수 팝업스토어",
              date,
              LocalTime.of(10, 0),
              LocalTime.of(11, 0),
              20,
              5,
              15,
              true,
              true,
              false);

      given(scheduleService.getScheduleByDate(popupId, date)).willReturn(List.of(res));

      mockMvc
          .perform(get("/popups/{popupId}/schedules", popupId).param("date", "2026-09-15"))
          .andDo(print())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.code").value("200"))
          .andExpect(jsonPath("$.message").value("스케줄 목록 조회 성공"))
          .andExpect(jsonPath("$.content.length()").value(1))
          .andExpect(jsonPath("$.content[0].scheduleId").value(100L))
          .andExpect(jsonPath("$.content[0].remainingCapacity").value(15));
    }
  }

  @Nested
  @DisplayName("스케줄 단건 등록 [POST /admin/schedules]")
  class CreateSchedule {

    @Test
    @DisplayName("성공: 유효한 요청 데이터인 경우 201 CREATED 상태와 생성된 ID를 반환한다")
    void createSchedule_success() throws Exception {
      String validJson =
          """
                    {
                      "popupId": 1,
                      "scheduleDate": "2026-10-01",
                      "startTime": "10:00:00",
                      "endTime": "11:00:00",
                      "maxCapacity": 20
                    }
                    """;

      given(scheduleService.createSchedule(any())).willReturn(50L);

      mockMvc
          .perform(
              post("/admin/schedules").contentType(MediaType.APPLICATION_JSON).content(validJson))
          .andDo(print())
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.code").value("200"))
          .andExpect(jsonPath("$.message").value("스케줄이 등록되었습니다."))
          .andExpect(jsonPath("$.content").value(50L));
    }

    @Test
    @DisplayName("실패: 정원(maxCapacity)이 0 이하이면 400 Bad Request를 반환한다")
    void createSchedule_invalidCapacity() throws Exception {
      String invalidJson =
          """
                    {
                      "popupId": 1,
                      "scheduleDate": "2026-10-01",
                      "startTime": "10:00:00",
                      "endTime": "11:00:00",
                      "maxCapacity": 0
                    }
                    """;

      mockMvc
          .perform(
              post("/admin/schedules").contentType(MediaType.APPLICATION_JSON).content(invalidJson))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("실패: 날짜가 과거 날짜인 경우 400 Bad Request를 반환한다")
    void createSchedule_pastDate() throws Exception {
      String pastDateJson =
          """
                    {
                      "popupId": 1,
                      "scheduleDate": "2020-01-01",
                      "startTime": "10:00:00",
                      "endTime": "11:00:00",
                      "maxCapacity": 10
                    }
                    """;

      mockMvc
          .perform(
              post("/admin/schedules")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(pastDateJson))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("스케줄 상태 변경 [PATCH /admin/schedules/{scheduleId}/status]")
  class UpdateScheduleStatus {

    @Test
    @DisplayName("성공: 슬롯 활성화 상태를 변경하고 200 OK를 반환한다")
    void updateScheduleStatus_success() throws Exception {
      Long scheduleId = 10L;
      willDoNothing().given(scheduleService).updateScheduleStatus(scheduleId, false);

      mockMvc
          .perform(
              patch("/admin/schedules/{scheduleId}/status", scheduleId).param("isActive", "false"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.message").value("스케줄 상태가 변경되었습니다."));
    }
  }

  @Nested
  @DisplayName("스케줄 삭제 [DELETE /admin/schedules/{scheduleId}]")
  class DeleteSchedule {

    @Test
    @DisplayName("성공: 스케줄을 삭제하고 200 OK를 반환한다")
    void deleteSchedule_success() throws Exception {
      Long scheduleId = 10L;
      willDoNothing().given(scheduleService).deleteSchedule(scheduleId);

      mockMvc
          .perform(delete("/admin/schedules/{scheduleId}", scheduleId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.message").value("스케줄이 삭제되었습니다."));
    }
  }

  @Nested
  @DisplayName("타임 슬롯 일괄 등록 [POST /admin/schedules/batch]")
  class CreateBatchSchedules {

    @Test
    @DisplayName("성공: 일괄 슬롯 생성이 완료되면 201 CREATED 상태와 생성된 개수를 반환한다")
    void createBatchSchedules_success() throws Exception {
      String validBatchJson =
          """
                    {
                      "popupId": 1,
                      "scheduleDate": "2026-10-01",
                      "openTime": "10:00:00",
                      "closeTime": "12:00:00",
                      "intervalMinutes": 30,
                      "maxCapacity": 10
                    }
                    """;

      given(scheduleService.createBatchSchedules(any())).willReturn(4);

      mockMvc
          .perform(
              post("/admin/schedules/batch")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(validBatchJson))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.code").value("200"))
          .andExpect(jsonPath("$.message").value("총 4개의 타임 슬롯이 등록되었습니다."))
          .andExpect(jsonPath("$.content").value(4));
    }

    @Test
    @DisplayName("실패: 회차 간격이 30분 미만인 경우 400 Bad Request를 반환한다")
    void createBatchSchedules_invalidInterval() throws Exception {
      String invalidIntervalJson =
          """
                    {
                      "popupId": 1,
                      "scheduleDate": "2026-10-01",
                      "openTime": "10:00:00",
                      "closeTime": "12:00:00",
                      "intervalMinutes": 20,
                      "maxCapacity": 10
                    }
                    """;

      mockMvc
          .perform(
              post("/admin/schedules/batch")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalidIntervalJson))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("실패: 시작 시각이 종료 시각 이후인 경우 @AssertTrue에 의해 400 Bad Request를 반환한다")
    void createBatchSchedules_invalidTimeRange() throws Exception {
      String invalidTimeJson =
          """
                    {
                      "popupId": 1,
                      "scheduleDate": "2026-10-01",
                      "openTime": "18:00:00",
                      "closeTime": "10:00:00",
                      "intervalMinutes": 30,
                      "maxCapacity": 10
                    }
                    """;

      mockMvc
          .perform(
              post("/admin/schedules/batch")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalidTimeJson))
          .andExpect(status().isBadRequest());
    }
  }
}
