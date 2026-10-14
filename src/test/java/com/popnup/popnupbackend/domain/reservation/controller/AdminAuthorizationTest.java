package com.popnup.popnupbackend.domain.reservation.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import com.popnup.popnupbackend.domain.schedule.service.ScheduleService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * R2: 관리자 API는 URL 규칙(SecurityConfig)과 별개로 컨트롤러 메서드에서도 ADMIN 권한을 확인해야 한다.
 *
 * <p>SecurityConfig의 {@code /admin/**} 규칙은 GET에만 걸려 있어서, 메서드 수준 권한이 없으면 POST · PATCH · DELETE는 로그인만
 * 하면 호출된다. 실제 Spring Security 설정을 올려서 검증한다 (독립 MockMvc는 보안 필터가 없어 이 검증을 할 수 없다).
 */
@SpringBootTest
class AdminAuthorizationTest {

  @Autowired private WebApplicationContext context;
  @MockitoBean private ScheduleService scheduleService;
  @MockitoBean private ReservationService reservationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private String scheduleJson() {
    return "{\"popupId\":1,\"scheduleDate\":\""
        + LocalDate.now().plusDays(30)
        + "\",\"startTime\":\"10:00:00\",\"endTime\":\"10:50:00\",\"maxCapacity\":20}";
  }

  private String batchJson() {
    return "{\"popupId\":1,\"scheduleDate\":\""
        + LocalDate.now().plusDays(30)
        + "\",\"openTime\":\"10:00:00\",\"closeTime\":\"12:00:00\","
        + "\"intervalMinutes\":60,\"maxCapacity\":20}";
  }

  @Test
  @WithMockUser(roles = "USER")
  @DisplayName("일반 회원은 관리자 API(회차 등록·일괄 등록·상태 변경·삭제·체크인)를 호출할 수 없다")
  void normalUser_forbiddenOnAdminApis() throws Exception {
    mockMvc
        .perform(
            post("/admin/schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(scheduleJson()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/admin/schedules/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchJson()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(patch("/admin/schedules/1/status").param("isActive", "false"))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete("/admin/schedules/1")).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/admin/reservations/check-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reservationNumber\":\"R1\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/admin/reservations").param("popupId", "1"))
        .andExpect(status().isForbidden());

    // 거절된 요청은 서비스까지 도달하지 않아야 한다
    verifyNoInteractions(scheduleService, reservationService);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("관리자는 관리자 API를 호출할 수 있다 (대조)")
  void admin_allowedOnAdminApis() throws Exception {
    mockMvc
        .perform(
            post("/admin/schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(scheduleJson()))
        .andExpect(status().isCreated());
    mockMvc.perform(delete("/admin/schedules/1")).andExpect(status().isOk());
  }
}
