package com.popnup.popnupbackend.domain.schedule.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleBatchCreateRequest;
import com.popnup.popnupbackend.domain.schedule.dto.request.ScheduleCreateRequest;
import com.popnup.popnupbackend.domain.schedule.dto.response.ScheduleResponse;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest(
    properties = {
      "jwt.secret=784dK3Hk+sSIkd37at/v1xMeOYLDaZviwulL2vHU0KvM5PK2cRAjbNMCodkD88gw7O6ueENnWQ94AG0WztDLCA=="
    })
@ExtendWith(MockitoExtension.class)
class ScheduleControllerTest {

  private MockMvc mockMvc;

  @InjectMocks private ScheduleController scheduleController;
  @Mock private ScheduleService scheduleService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(scheduleController).build();
  }

  @Nested
  @DisplayName("스케줄 목록 조회 [GET /popups/{popupId}/schedules]")
  class GetSchedulesTest {

    @Test
    @DisplayName("성공: 팝업 ID와 날짜로 조회 시 200 OK와 스케줄 목록을 반환한다")
    void getSchedules_success() throws Exception {
      // given
      Long popupId = 1L;
      LocalDate date = LocalDate.of(2026, 9, 20);

      ScheduleResponse response1 = mock(ScheduleResponse.class);
      ScheduleResponse response2 = mock(ScheduleResponse.class);

      given(response1.getScheduleId()).willReturn(10L);
      given(response1.getMaxCapacity()).willReturn(20);

      given(scheduleService.getScheduleByDate(popupId, date))
          .willReturn(List.of(response1, response2));

      // when & then
      mockMvc
          .perform(get("/popups/{popupId}/schedules", popupId).param("date", date.toString()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.message").value("스케줄 목록 조회 성공"))
          .andExpect(jsonPath("$.content").isArray())
          .andExpect(jsonPath("$.content.length()").value(2))
          .andExpect(jsonPath("$.content[0].scheduleId").value(10L))
          .andExpect(jsonPath("$.content[0].maxCapacity").value(20));

      verify(scheduleService).getScheduleByDate(popupId, date);
    }
  }

  @Nested
  @DisplayName("스케줄 단건 등록 [POST /admin/schedules]")
  class CreateScheduleTest {

    @Test
    @DisplayName("성공: 유효한 요청 데이터 전달 시 201 Created와 생성된 스케줄 ID를 반환한다")
    void createSchedule_success() throws Exception {
      // given
      String jsonRequest =
          """
              {
                "popupId": 1,
                "scheduleDate": "2026-09-20",
                "startTime": "13:00:00",
                "endTime": "14:00:00",
                "maxCapacity": 30
              }
              """;

      given(scheduleService.createSchedule(any(ScheduleCreateRequest.class))).willReturn(100L);

      // when & then
      mockMvc
          .perform(
              post("/admin/schedules").contentType(MediaType.APPLICATION_JSON).content(jsonRequest))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.message").value("스케줄이 등록되었습니다."))
          .andExpect(jsonPath("$.content").value(100L));

      verify(scheduleService).createSchedule(any(ScheduleCreateRequest.class));
    }
  }

  @Nested
  @DisplayName("타임 슬롯 활성화 상태 변경 [PATCH /admin/schedules/{scheduleId}/status]")
  class UpdateScheduleStatusTest {

    @Test
    @DisplayName("성공: 상태 변경 파라미터 전달 시 200 OK를 반환한다")
    void updateScheduleStatus_success() throws Exception {
      // given
      Long scheduleId = 10L;
      boolean isActive = false;
      doNothing().when(scheduleService).updateScheduleStatus(scheduleId, isActive);

      // when & then
      mockMvc
          .perform(
              patch("/admin/schedules/{scheduleId}/status", scheduleId)
                  .param("isActive", String.valueOf(isActive)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.message").value("스케줄 상태가 변경되었습니다."));

      verify(scheduleService).updateScheduleStatus(scheduleId, isActive);
    }
  }

  @Nested
  @DisplayName("스케줄 삭제 [DELETE /admin/schedules/{scheduleId}]")
  class DeleteScheduleTest {

    @Test
    @DisplayName("성공: 스케줄 ID 전달 시 정상 삭제 처리 후 200 OK를 반환한다")
    void deleteSchedule_success() throws Exception {
      // given
      Long scheduleId = 10L;
      doNothing().when(scheduleService).deleteSchedule(scheduleId);

      // when & then
      mockMvc
          .perform(delete("/admin/schedules/{scheduleId}", scheduleId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.message").value("스케줄이 삭제되었습니다."));

      verify(scheduleService).deleteSchedule(scheduleId);
    }
  }

  @Nested
  @DisplayName("타임 슬롯 일괄 등록 [POST /admin/schedules/batch]")
  class CreateBatchSchedulesTest {

    @Test
    @DisplayName("성공: 유효한 일괄 등록 요청 시 201 Created와 등록된 총 슬롯 개수를 반환한다")
    void createBatchSchedules_success() throws Exception {
      // given
      String jsonRequest =
          """
              {
                "popupId": 1,
                "scheduleDate": "2026-09-20",
                "openTime": "10:00:00",
                "closeTime": "18:00:00",
                "intervalMinutes": 60,
                "maxCapacity": 15
              }
              """;

      given(scheduleService.createBatchSchedules(any(ScheduleBatchCreateRequest.class)))
          .willReturn(48);

      // when & then
      mockMvc
          .perform(
              post("/admin/schedules/batch")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(jsonRequest))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.message").value("총 48개의 타임 슬롯이 등록되었습니다."))
          .andExpect(jsonPath("$.content").value(48));

      verify(scheduleService).createBatchSchedules(any(ScheduleBatchCreateRequest.class));
    }
  }
}
