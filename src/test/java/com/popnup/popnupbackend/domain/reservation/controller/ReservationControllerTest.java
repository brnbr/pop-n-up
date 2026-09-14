package com.popnup.popnupbackend.domain.reservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.enums.Role;
import com.popnup.popnupbackend.domain.qrcode.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.qrcode.dto.response.CheckInResponse;
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationCreateResponse;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

  @Mock private ReservationService reservationService;

  private MockMvc mockMvc;
  private final AuthUser authUser = new AuthUser(1L, "test@test.com", "테스터", Role.ROLE_USER);

  @BeforeEach
  void setUp() {
    ReservationController controller = new ReservationController(reservationService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.popnup.popnupbackend.global.error.GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(authUser, null));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Nested
  @DisplayName("POST /reservations")
  class CreateReservation {

    @Test
    @DisplayName("정상 요청이면 200과 함께 생성된 예약 정보를 반환한다")
    void success() throws Exception {
      ReservationCreateResponse response = ReservationCreateResponse.from(10L, "R1");
      given(reservationService.book(org.mockito.Mockito.eq(1L), any())).willReturn(response);

      // ReservationCreateRequest 필드: scheduleId(Long), personCount(Integer)
      String requestBody = "{\"scheduleId\":100,\"personCount\":2}";

      log.info("[createReservation.success] input(memberId=1, requestBody={})", requestBody);

      mockMvc
          .perform(post("/reservations").contentType("application/json").content(requestBody))
          .andExpect(status().isOk());

      log.info("[createReservation.success] expectedStatus=200 verified");

      verify(reservationService, times(1))
          .book(org.mockito.Mockito.eq(1L), any(ReservationCreateRequest.class));
    }

    @Test
    @DisplayName("서비스에서 DUPLICATE_USER_RESERVATION이 터지면 409를 반환한다")
    void duplicateReservation() throws Exception {
      given(reservationService.book(org.mockito.Mockito.eq(1L), any()))
          .willThrow(ReservationErrorCode.DUPLICATE_USER_RESERVATION.toException());

      String requestBody = "{\"scheduleId\":100,\"personCount\":2}";

      log.info("[createReservation.duplicateReservation] input(requestBody={})", requestBody);

      mockMvc
          .perform(post("/reservations").contentType("application/json").content(requestBody))
          .andExpect(status().isConflict());

      log.info("[createReservation.duplicateReservation] expectedStatus=409 verified");
    }
  }

  @Nested
  @DisplayName("GET /reservations/{id}/qr")
  class GetReservationQr {

    @Test
    @DisplayName("정상 요청이면 200과 함께 PNG 이미지를 반환한다")
    void success() throws Exception {
      byte[] fakeImage = new byte[] {1, 2, 3};
      given(reservationService.getReservationQrCode(1L, 10L)).willReturn(fakeImage);

      log.info("[getReservationQr.success] input(memberId=1, reservationId=10)");

      mockMvc.perform(get("/reservations/10/qr")).andExpect(status().isOk());

      log.info("[getReservationQr.success] expectedStatus=200 verified");
    }

    @Test
    @DisplayName("본인 예약이 아니면 403을 반환한다")
    void unauthorized() throws Exception {
      given(reservationService.getReservationQrCode(1L, 10L))
          .willThrow(ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException());

      log.info("[getReservationQr.unauthorized] input(memberId=1, reservationId=10)");

      mockMvc.perform(get("/reservations/10/qr")).andExpect(status().isForbidden());

      log.info("[getReservationQr.unauthorized] expectedStatus=403 verified");
    }

    @Test
    @DisplayName("예약이 존재하지 않으면 404를 반환한다")
    void notFound() throws Exception {
      given(reservationService.getReservationQrCode(1L, 999L))
          .willThrow(ReservationErrorCode.RESERVATION_NOT_FOUND.toException());

      log.info("[getReservationQr.notFound] input(memberId=1, reservationId=999)");

      mockMvc.perform(get("/reservations/999/qr")).andExpect(status().isNotFound());

      log.info("[getReservationQr.notFound] expectedStatus=404 verified");
    }
  }

  @Nested
  @DisplayName("POST /admin/reservations/check-in")
  class CheckIn {

    @Test
    @DisplayName("정상 요청이면 200과 함께 체크인 결과를 반환한다")
    void success() throws Exception {
      CheckInResponse response = new CheckInResponse(10L, "R1", "테스터", 2);
      given(reservationService.checkIn(any(CheckInRequest.class))).willReturn(response);

      // CheckInRequest는 테스트용 생성자가 있지만, MockMvc는 JSON 바디를 그대로 역직렬화하므로
      // 여기서는 실제 JSON 문자열을 보냄 (필드: reservationNumber)
      String requestBody = "{\"reservationNumber\":\"R1\"}";

      log.info("[checkIn.success] input(requestBody={})", requestBody);

      mockMvc
          .perform(
              post("/admin/reservations/check-in")
                  .contentType("application/json")
                  .content(requestBody))
          .andExpect(status().isOk());

      log.info("[checkIn.success] expectedStatus=200 verified");
    }

    @Test
    @DisplayName("이미 처리된 예약이면 409를 반환한다")
    void alreadyProcessed() throws Exception {
      given(reservationService.checkIn(any(CheckInRequest.class)))
          .willThrow(ReservationErrorCode.ALREADY_PROCESSED_RESERVATION.toException());

      String requestBody = "{\"reservationNumber\":\"R1\"}";

      log.info("[checkIn.alreadyProcessed] input(requestBody={})", requestBody);

      mockMvc
          .perform(
              post("/admin/reservations/check-in")
                  .contentType("application/json")
                  .content(requestBody))
          .andExpect(status().isConflict());

      log.info("[checkIn.alreadyProcessed] expectedStatus=409 verified");
    }
  }

  @Nested
  @DisplayName("DELETE /reservations/{id}")
  class DeleteReservation {

    @Test
    @DisplayName("정상 요청이면 200을 반환하고 서비스에 취소를 위임한다")
    void success() throws Exception {
      log.info("[deleteReservation.success] input(memberId=1, reservationId=10)");

      mockMvc.perform(delete("/reservations/10")).andExpect(status().isOk());

      log.info("[deleteReservation.success] verify reservationService.cancel(1L, 10L) called");

      verify(reservationService, times(1)).cancel(1L, 10L);
    }

    @Test
    @DisplayName("본인 예약이 아니면 403을 반환한다")
    void unauthorized() throws Exception {
      org.mockito.Mockito.doThrow(
              ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException())
          .when(reservationService)
          .cancel(1L, 10L);

      log.info("[deleteReservation.unauthorized] input(memberId=1, reservationId=10)");

      mockMvc.perform(delete("/reservations/10")).andExpect(status().isForbidden());

      log.info("[deleteReservation.unauthorized] expectedStatus=403 verified");
    }
  }

  @Nested
  @DisplayName("GET /reservations")
  class GetAll {

    @Test
    @DisplayName("정상 요청이면 200과 함께 본인 예약 목록을 반환한다")
    void success() throws Exception {
      given(reservationService.allReservations(1L)).willReturn(List.of());

      log.info("[getAll.success] input(memberId=1)");

      mockMvc.perform(get("/reservations")).andExpect(status().isOk());

      log.info("[getAll.success] expectedStatus=200 verified");
    }
  }

  @Nested
  @DisplayName("GET /reservations/{id}")
  class GetOne {

    @Test
    @DisplayName("정상 요청이면 200을 반환한다")
    void success() throws Exception {
      given(reservationService.oneReservation(anyLong(), anyLong())).willReturn(null);

      log.info("[getOne.success] input(memberId=1, reservationId=10)");

      mockMvc.perform(get("/reservations/10")).andExpect(status().isOk());

      log.info("[getOne.success] expectedStatus=200 verified");
    }
  }

  @Nested
  @DisplayName("GET /admin/reservations")
  class GetAllAdmin {

    @Test
    @DisplayName("정상 요청이면 200과 함께 관리자용 예약 목록을 반환한다")
    void success() throws Exception {
      given(reservationService.getAdminReservations(any(), any(), any())).willReturn(List.of());

      log.info("[getAllAdmin.success] input(popupId=1)");

      mockMvc.perform(get("/admin/reservations").param("popupId", "1")).andExpect(status().isOk());

      log.info("[getAllAdmin.success] expectedStatus=200 verified");
    }
  }
}
