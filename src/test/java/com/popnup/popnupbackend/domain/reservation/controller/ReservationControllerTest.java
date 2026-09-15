package com.popnup.popnupbackend.domain.reservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;

  @InjectMocks private ReservationController reservationController;
  @Mock private ReservationService reservationService;

  private final AuthUser mockAuthUser = new AuthUser(1L, "test@popnup.com", "테스터", Role.ROLE_USER);

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();

    // @AuthenticationPrincipal AuthUser 객체 주입을 위한 ArgumentResolver 등록
    HandlerMethodArgumentResolver authUserResolver =
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
            return mockAuthUser;
          }
        };

    mockMvc =
        MockMvcBuilders.standaloneSetup(reservationController)
            .setCustomArgumentResolvers(authUserResolver)
            .build();
  }

  @Nested
  @DisplayName("예약 생성 [POST /reservations]")
  class CreateReservationTest {

    @Test
    @DisplayName("성공: 유효한 요청 시 예약 생성 후 200 OK와 예약 정보를 반환한다")
    void createReservation_success() throws Exception {
      // given
      ReservationCreateRequest request = new ReservationCreateRequest();
      ReflectionTestUtils.setField(request, "scheduleId", 10L);
      ReflectionTestUtils.setField(request, "personCount", 2);

      ReservationCreateResponse response = ReservationCreateResponse.from(100L, "R20260915TEST");

      given(
              reservationService.bookWithConditionalUpdate(
                  eq(1L), any(ReservationCreateRequest.class)))
          .willReturn(response);

      // when & then
      mockMvc
          .perform(
              post("/reservations")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.content.reservationId").value(100L))
          .andExpect(jsonPath("$.content.reservationNumber").value("R20260915TEST"));

      verify(reservationService)
          .bookWithConditionalUpdate(eq(1L), any(ReservationCreateRequest.class));
    }
  }

  @Nested
  @DisplayName("예약 QR 코드 조회 [GET /reservations/{reservationId}/qr]")
  class GetReservationQrTest {

    @Test
    @DisplayName("성공: 이미지 바이트 배열과 함께 no-store 캐시 헤더 및 PNG 콘텐츠 타입을 반환한다")
    void getReservationQr_success() throws Exception {
      // given
      Long reservationId = 100L;
      byte[] qrImageBytes = new byte[] {0x12, 0x34, 0x56};

      given(reservationService.getReservationQrCode(1L, reservationId)).willReturn(qrImageBytes);

      // when & then
      mockMvc
          .perform(get("/reservations/{reservationId}/qr", reservationId))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store, must-revalidate"))
          .andExpect(content().contentType(MediaType.IMAGE_PNG))
          .andExpect(content().bytes(qrImageBytes));

      verify(reservationService).getReservationQrCode(1L, reservationId);
    }
  }

  @Nested
  @DisplayName("체크인 [POST /admin/reservations/check-in]")
  class CheckInTest {

    @Test
    @DisplayName("성공: 유효한 예약 번호로 체크인 시 200 OK와 체크인 결과를 반환한다")
    void checkIn_success() throws Exception {
      // given
      CheckInRequest request = new CheckInRequest("R20260915TEST");

      CheckInResponse response = new CheckInResponse(100L, "R20260915TEST", "김철수", 2);

      given(reservationService.checkIn(any(CheckInRequest.class))).willReturn(response);

      // when & then
      mockMvc
          .perform(
              post("/admin/reservations/check-in")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.content.reservationId").value(100L))
          .andExpect(jsonPath("$.content.reservationNumber").value("R20260915TEST"))
          .andExpect(jsonPath("$.content.memberName").value("김철수"))
          .andExpect(jsonPath("$.content.personCount").value(2));

      verify(reservationService).checkIn(any(CheckInRequest.class));
    }
  }

  @Nested
  @DisplayName("예약 취소 [DELETE /reservations/{reservationId}]")
  class DeleteReservationTest {

    @Test
    @DisplayName("성공: 본인 예약 취소 요청 시 200 OK를 반환한다")
    void deleteReservation_success() throws Exception {
      // given
      Long reservationId = 100L;
      doNothing().when(reservationService).cancel(1L, reservationId);

      // when & then
      mockMvc
          .perform(delete("/reservations/{reservationId}", reservationId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true));

      verify(reservationService).cancel(1L, reservationId);
    }
  }

  @Nested
  @DisplayName("예약 목록 조회 [GET /reservations]")
  class GetAllTest {

    @Test
    @DisplayName("성공: 로그인 회원의 전체 예약 목록을 반환한다")
    void getAll_success() throws Exception {
      // given
      ReservationResponse response =
          new ReservationResponse(
              100L,
              "R20260915TEST",
              ReservationStatus.CONFIRMED,
              LocalDateTime.of(2026, 9, 15, 14, 0),
              2);

      given(reservationService.allReservations(1L)).willReturn(List.of(response));

      // when & then
      mockMvc
          .perform(get("/reservations"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.content[0].reservationId").value(100L))
          .andExpect(jsonPath("$.content[0].reservationNumber").value("R20260915TEST"))
          .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));

      verify(reservationService).allReservations(1L);
    }
  }

  @Nested
  @DisplayName("예약 단건 상세 조회 [GET /reservations/{reservationId}]")
  class GetOneTest {

    @Test
    @DisplayName("성공: 예약 ID로 단건 조회 시 200 OK와 상세 정보를 반환한다")
    void getOne_success() throws Exception {
      // given
      Long reservationId = 100L;
      ReservationResponse response =
          new ReservationResponse(
              reservationId,
              "R20260915TEST",
              ReservationStatus.CONFIRMED,
              LocalDateTime.of(2026, 9, 15, 14, 0),
              2);

      given(reservationService.oneReservation(1L, reservationId)).willReturn(response);

      // when & then
      mockMvc
          .perform(get("/reservations/{reservationId}", reservationId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.content.reservationId").value(reservationId))
          .andExpect(jsonPath("$.content.reservationNumber").value("R20260915TEST"));

      verify(reservationService).oneReservation(1L, reservationId);
    }
  }

  @Nested
  @DisplayName("관리자 전체 예약 목록 조회 [GET /admin/reservations]")
  class GetAllAdminTest {

    @Test
    @DisplayName("성공: 필터 파라미터 전달 시 관리자용 예약 목록 리스트를 반환한다")
    void getAllAdmin_success() throws Exception {
      // given
      Long popupId = 1L;
      LocalDate scheduleDate = LocalDate.of(2026, 9, 15);
      ReservationStatus status = ReservationStatus.CONFIRMED;

      AdminReservationResponse response =
          new AdminReservationResponse(
              popupId,
              "팝앤업 팝업스토어",
              100L,
              "R20260915ADMIN",
              2,
              status,
              10L,
              "관리자확인",
              scheduleDate,
              LocalTime.of(13, 0),
              LocalTime.of(14, 0));

      given(reservationService.getAdminReservations(popupId, scheduleDate, status))
          .willReturn(List.of(response));

      // when & then
      mockMvc
          .perform(
              get("/admin/reservations")
                  .param("popupId", popupId.toString())
                  .param("scheduleDate", scheduleDate.toString())
                  .param("status", status.name()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.content[0].popupId").value(popupId))
          .andExpect(jsonPath("$.content[0].reservationId").value(100L))
          .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));

      verify(reservationService).getAdminReservations(popupId, scheduleDate, status);
    }
  }
}
