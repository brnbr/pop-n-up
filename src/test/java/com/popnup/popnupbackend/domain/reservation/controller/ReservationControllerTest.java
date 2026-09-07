package com.popnup.popnupbackend.domain.reservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.enums.Role;
import com.popnup.popnupbackend.domain.qrcode.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.qrcode.dto.response.CheckInResponse;
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.AdminReservationResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationCreateResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationResponse;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

  private MockMvc mockMvc;

  @Mock private ReservationService reservationService;

  private final Long memberId = 1L;

  @BeforeEach
  void setUp() {
    HandlerMethodArgumentResolver authUserArgumentResolver =
        new HandlerMethodArgumentResolver() {
          @Override
          public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                && parameter.getParameterType().equals(AuthUser.class);
          }

          @Override
          public Object resolveArgument(
              MethodParameter parameter,
              ModelAndViewContainer mavContainer,
              NativeWebRequest webRequest,
              WebDataBinderFactory binderFactory) {
            return new AuthUser(memberId, "test@test.com", "홍길동", Role.ROLE_USER);
          }
        };

    this.mockMvc =
        MockMvcBuilders.standaloneSetup(new ReservationController(reservationService))
            .setCustomArgumentResolvers(authUserArgumentResolver)
            .build();
  }

  @Nested
  @DisplayName("예약 생성 [POST /reservations]")
  class CreateReservation {

    @Test
    @DisplayName("유효한 요청이 들어오면 예약 생성 후 200 OK와 생성 정보를 반환한다")
    void createReservation_success() throws Exception {
      String jsonRequest =
          """
                  {
                    "scheduleId": 10,
                    "personCount": 2
                  }
                  """;

      ReservationCreateResponse response =
          ReservationCreateResponse.from(100L, "R20260907A1B2C3D4");

      given(reservationService.book(eq(memberId), any(ReservationCreateRequest.class)))
          .willReturn(response);

      mockMvc
          .perform(
              post("/reservations").contentType(MediaType.APPLICATION_JSON).content(jsonRequest))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.code").value("200"))
          .andExpect(jsonPath("$.content.reservationId").value(100L))
          .andExpect(jsonPath("$.content.reservationNumber").value("R20260907A1B2C3D4"));
    }
  }

  @Nested
  @DisplayName("QR 코드 스트리밍 조회 [GET /reservations/{reservationId}/qr]")
  class GetReservationQr {

    @Test
    @DisplayName("예약 ID로 QR을 요청하면 image/png 타입의 바이너리를 200 OK로 반환한다")
    void getReservationQr_success() throws Exception {
      Long reservationId = 100L;
      byte[] mockImageBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG 시그니처 바이트

      given(reservationService.getReservationQrCode(memberId, reservationId))
          .willReturn(mockImageBytes);

      mockMvc
          .perform(get("/reservations/{reservationId}/qr", reservationId))
          .andDo(print())
          .andExpect(status().isOk())
          .andExpect(content().contentType(MediaType.IMAGE_PNG))
          .andExpect(content().bytes(mockImageBytes));
    }
  }

  @Nested
  @DisplayName("QR 체크인 [POST /reservations/check-in]")
  class CheckIn {

    @Test
    @DisplayName("예약 번호로 체크인을 요청하면 체크인 처리 후 200 OK와 예약자 정보를 반환한다")
    void checkIn_success() throws Exception {
      String jsonRequest =
          """
                  {
                    "reservationNumber": "R20260907TEST1234"
                  }
                  """;

      CheckInResponse response = new CheckInResponse(100L, "R20260907TEST1234", "김철수", 2);

      given(reservationService.checkIn(any(CheckInRequest.class))).willReturn(response);

      mockMvc
          .perform(
              post("/reservations/check-in")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(jsonRequest))
          .andDo(print())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.content.reservationId").value(100L))
          .andExpect(jsonPath("$.content.reservationNumber").value("R20260907TEST1234"))
          .andExpect(jsonPath("$.content.memberName").value("김철수"))
          .andExpect(jsonPath("$.content.personCount").value(2));
    }
  }

  @Nested
  @DisplayName("예약 취소 [DELETE /reservations/{reservationId}]")
  class DeleteReservation {

    @Test
    @DisplayName("예약 ID를 넘기면 정상적으로 예약을 취소하고 200 OK를 반환한다")
    void deleteReservation_success() throws Exception {
      Long reservationId = 100L;
      willDoNothing().given(reservationService).cancel(memberId, reservationId);

      mockMvc
          .perform(delete("/reservations/{reservationId}", reservationId))
          .andDo(print())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.code").value("200"));
    }
  }

  @Nested
  @DisplayName("전체 예약 목록 조회 [GET /reservations]")
  class GetAllReservations {

    @Test
    @DisplayName("로그인한 회원의 전체 예약 목록을 200 OK로 반환한다")
    void getAll_success() throws Exception {
      ReservationResponse item1 =
          new ReservationResponse(
              1L, "R20260907-1111", ReservationStatus.CONFIRMED, LocalDateTime.now(), 2);
      ReservationResponse item2 =
          new ReservationResponse(
              2L, "R20260907-2222", ReservationStatus.CANCELED, LocalDateTime.now(), 1);

      given(reservationService.allReservations(memberId)).willReturn(List.of(item1, item2));

      mockMvc
          .perform(get("/reservations"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.content").isArray())
          .andExpect(jsonPath("$.content.length()").value(2))
          .andExpect(jsonPath("$.content[0].reservationId").value(1L))
          .andExpect(jsonPath("$.content[1].reservationId").value(2L));
    }
  }

  @Nested
  @DisplayName("예약 단건 상세 조회 [GET /reservations/{reservationId}]")
  class GetOneReservation {

    @Test
    @DisplayName("특정 예약 ID를 조회하면 상세 정보를 200 OK로 반환한다")
    void getOne_success() throws Exception {
      Long reservationId = 100L;
      ReservationResponse response =
          new ReservationResponse(
              reservationId, "R20260907-1111", ReservationStatus.CONFIRMED, LocalDateTime.now(), 2);

      given(reservationService.oneReservation(memberId, reservationId)).willReturn(response);

      mockMvc
          .perform(get("/reservations/{reservationId}", reservationId))
          .andDo(print())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.content.reservationId").value(reservationId))
          .andExpect(jsonPath("$.content.reservationNumber").value("R20260907-1111"));
    }
  }

  @Nested
  @DisplayName("관리자 전체 예약 목록 조회 [GET /admin/reservations]")
  class GetAllAdmin {

    @Test
    @DisplayName("파라미터를 전달받아 관리자 명단을 200 OK로 반환한다")
    void getAllAdmin_success() throws Exception {
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 7);
      ReservationStatus status = ReservationStatus.CONFIRMED;

      AdminReservationResponse item =
          new AdminReservationResponse(
              popupId,
              "성수 아트 팝업",
              100L,
              "R20260907TEST01",
              2,
              ReservationStatus.CONFIRMED,
              10L,
              "김철수",
              scheduleDate,
              LocalTime.of(13, 0),
              LocalTime.of(14, 0));

      given(reservationService.getAdminReservations(popupId, scheduleDate, status))
          .willReturn(List.of(item));

      mockMvc
          .perform(
              get("/admin/reservations")
                  .param("popupId", String.valueOf(popupId))
                  .param("scheduleDate", "2026-09-07")
                  .param("status", "CONFIRMED"))
          .andDo(print())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.content.length()").value(1))
          .andExpect(jsonPath("$.content[0].popupId").value(popupId))
          .andExpect(jsonPath("$.content[0].reservationNumber").value("R20260907TEST01"));
    }
  }
}
