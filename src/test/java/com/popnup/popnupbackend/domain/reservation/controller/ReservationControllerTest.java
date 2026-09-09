package com.popnup.popnupbackend.domain.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.enums.Role;
import com.popnup.popnupbackend.domain.qrcode.dto.request.CheckInRequest;
import com.popnup.popnupbackend.domain.qrcode.dto.response.CheckInResponse;
import com.popnup.popnupbackend.domain.reservation.dto.request.ReservationCreateRequest;
import com.popnup.popnupbackend.domain.reservation.dto.response.AdminReservationResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationCreateResponse;
import com.popnup.popnupbackend.domain.reservation.dto.response.ReservationResponse;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

  private MockMvc mockMvc;

  @Mock private ReservationService reservationService;

  private final Long memberId = 1L;

  private void printLog(String testName, Object input, Object expected, Object actual) {
    log.info(
        "\n================ [CONTROLLER TEST] ================"
            + "\n📌 테스트명    : {}"
            + "\n📥 입력값      : {}"
            + "\n🎯 예상 결과   : {}"
            + "\n🔍 실제 결과   : {}"
            + "\n====================================================",
        testName,
        input,
        expected,
        actual);
  }

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

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    this.mockMvc =
        MockMvcBuilders.standaloneSetup(new ReservationController(reservationService))
            .setCustomArgumentResolvers(authUserArgumentResolver)
            .setValidator(validator)
            .build();
  }

  @Nested
  @DisplayName("예약 생성 [POST /reservations]")
  class CreateReservation {

    @Test
    @DisplayName("유효한 요청이 들어오면 200 OK와 생성 정보를 반환한다")
    void createReservation_success() throws Exception {
      String jsonRequest =
          """
              {
                "scheduleId": 10,
                "personCount": 2
              }
              """;

      ReservationCreateResponse response =
          ReservationCreateResponse.from(100L, "R20260908A1B2C3D4");

      given(reservationService.book(eq(memberId), any(ReservationCreateRequest.class)))
          .willReturn(response);

      MvcResult result =
          mockMvc
              .perform(
                  post("/reservations")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(jsonRequest))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.code").value("200"))
              .andExpect(jsonPath("$.content.reservationId").value(100L))
              .andExpect(jsonPath("$.content.reservationNumber").value("R20260908A1B2C3D4"))
              .andReturn();

      printLog(
          "POST /reservations - 성공",
          jsonRequest.trim(),
          "Status 200, reservationId=100",
          "Status "
              + result.getResponse().getStatus()
              + ", Body="
              + result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("예약 인원수가 0명 이하이면 400 Bad Request를 반환한다")
    void createReservation_validationFail() throws Exception {
      String invalidJsonRequest =
          """
              {
                "scheduleId": 10,
                "personCount": 0
              }
              """;

      MvcResult result =
          mockMvc
              .perform(
                  post("/reservations")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(invalidJsonRequest))
              .andExpect(status().isBadRequest())
              .andReturn();

      printLog(
          "POST /reservations - 인원 유효성 실패",
          invalidJsonRequest.trim(),
          "Status 400",
          "Status "
              + result.getResponse().getStatus()
              + ", Body="
              + result.getResponse().getContentAsString());
    }
  }

  @Nested
  @DisplayName("동적 QR 이미지 조회 [GET /reservations/{reservationId}/qr]")
  class GetReservationQr {

    @Test
    @DisplayName("QR 조회 시 Cache-Control(no-store, must-revalidate) 헤더와 PNG 이미지를 반환한다")
    void getReservationQr_success() throws Exception {
      Long reservationId = 100L;
      byte[] mockImageBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};

      given(reservationService.getReservationQrCode(memberId, reservationId))
          .willReturn(mockImageBytes);

      MvcResult result =
          mockMvc
              .perform(get("/reservations/{reservationId}/qr", reservationId))
              .andDo(print())
              .andExpect(status().isOk())
              .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, must-revalidate"))
              .andExpect(content().contentType(MediaType.IMAGE_PNG))
              .andReturn();

      printLog(
          "GET /reservations/" + reservationId + "/qr",
          "reservationId=" + reservationId,
          "Content-Type=image/png, Cache-Control=no-store, must-revalidate",
          "Content-Type="
              + result.getResponse().getContentType()
              + ", Cache-Control="
              + result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)
              + ", Body Byte Length="
              + result.getResponse().getContentAsByteArray().length);

      assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(mockImageBytes);
    }
  }

  @Nested
  @DisplayName("체크인 [POST /admin/reservations/check-in]")
  class CheckIn {

    @Test
    @DisplayName("체크인 번호가 유효하면 200 OK와 체크인 완료 정보를 반환한다")
    void checkIn_success() throws Exception {
      String jsonRequest =
          """
              {
                "reservationNumber": "R20260908TEST1234"
              }
              """;

      Member member = Member.createLocal("test@test.com", "pw", "김철수");
      Reservation reservation = Reservation.createReservation("R20260908TEST1234", member, null, 2);
      reservation.confirm();
      reservation.checkIn();

      CheckInResponse response = CheckInResponse.from(reservation);

      given(reservationService.checkIn(any(CheckInRequest.class))).willReturn(response);

      MvcResult result =
          mockMvc
              .perform(
                  post("/admin/reservations/check-in")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(jsonRequest))
              .andDo(print())
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.content.reservationNumber").value("R20260908TEST1234"))
              .andReturn();

      printLog(
          "POST /admin/reservations/check-in",
          jsonRequest.trim(),
          "Status 200, reservationNumber=R20260908TEST1234",
          "Status "
              + result.getResponse().getStatus()
              + ", Body="
              + result.getResponse().getContentAsString());
    }
  }

  @Nested
  @DisplayName("예약 취소 [DELETE /reservations/{reservationId}]")
  class DeleteReservation {

    @Test
    @DisplayName("예약 ID를 넘겨 취소하면 200 OK를 반환한다")
    void deleteReservation_success() throws Exception {
      Long reservationId = 100L;
      willDoNothing().given(reservationService).cancel(memberId, reservationId);

      MvcResult result =
          mockMvc
              .perform(delete("/reservations/{reservationId}", reservationId))
              .andDo(print())
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.code").value("200"))
              .andReturn();

      printLog(
          "DELETE /reservations/" + reservationId,
          "reservationId=" + reservationId,
          "Status 200",
          "Status "
              + result.getResponse().getStatus()
              + ", Body="
              + result.getResponse().getContentAsString());
    }
  }

  @Nested
  @DisplayName("전체 예약 목록 조회 [GET /reservations]")
  class GetAllReservations {

    @Test
    @DisplayName("회원의 전체 예약 목록을 200 OK로 반환한다")
    void getAll_success() throws Exception {
      ReservationResponse item1 =
          new ReservationResponse(
              1L, "R20260908-1111", ReservationStatus.CONFIRMED, LocalDateTime.now(), 2);

      given(reservationService.allReservations(memberId)).willReturn(List.of(item1));

      MvcResult result =
          mockMvc
              .perform(get("/reservations"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.content.length()").value(1))
              .andExpect(jsonPath("$.content[0].reservationId").value(1L))
              .andReturn();

      printLog(
          "GET /reservations",
          "memberId=" + memberId,
          "Status 200, array length=1",
          "Status "
              + result.getResponse().getStatus()
              + ", Body="
              + result.getResponse().getContentAsString());
    }
  }

  @Nested
  @DisplayName("단건 상세 조회 [GET /reservations/{reservationId}]")
  class GetOneReservation {

    @Test
    @DisplayName("상세 정보를 200 OK로 반환한다")
    void getOne_success() throws Exception {
      Long reservationId = 100L;
      ReservationResponse response =
          new ReservationResponse(
              reservationId, "R20260908-1111", ReservationStatus.CONFIRMED, LocalDateTime.now(), 2);

      given(reservationService.oneReservation(memberId, reservationId)).willReturn(response);

      MvcResult result =
          mockMvc
              .perform(get("/reservations/{reservationId}", reservationId))
              .andDo(print())
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.content.reservationId").value(reservationId))
              .andReturn();

      printLog(
          "GET /reservations/" + reservationId,
          "reservationId=" + reservationId,
          "Status 200, reservationId=100",
          "Status "
              + result.getResponse().getStatus()
              + ", Body="
              + result.getResponse().getContentAsString());
    }
  }

  @Nested
  @DisplayName("관리자 전체 예약 목록 조회 [GET /admin/reservations]")
  class GetAllAdmin {

    @Test
    @DisplayName("조건에 맞는 관리자 명단을 200 OK로 반환한다")
    void getAllAdmin_success() throws Exception {
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 8);
      ReservationStatus status = ReservationStatus.CONFIRMED;

      AdminReservationResponse item =
          new AdminReservationResponse(
              popupId,
              "성수 팝업",
              100L,
              "R20260908TEST01",
              2,
              ReservationStatus.CONFIRMED,
              10L,
              "김철수",
              scheduleDate,
              LocalTime.of(13, 0),
              LocalTime.of(14, 0));

      given(reservationService.getAdminReservations(popupId, scheduleDate, status))
          .willReturn(List.of(item));

      MvcResult result =
          mockMvc
              .perform(
                  get("/admin/reservations")
                      .param("popupId", String.valueOf(popupId))
                      .param("scheduleDate", "2026-09-08")
                      .param("status", "CONFIRMED"))
              .andDo(print())
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.content.length()").value(1))
              .andExpect(jsonPath("$.content[0].popupTitle").value("성수 팝업"))
              .andReturn();

      printLog(
          "GET /admin/reservations",
          "popupId=1, scheduleDate=2026-09-08, status=CONFIRMED",
          "Status 200, array length=1",
          "Status "
              + result.getResponse().getStatus()
              + ", Body="
              + result.getResponse().getContentAsString());
    }
  }
}
