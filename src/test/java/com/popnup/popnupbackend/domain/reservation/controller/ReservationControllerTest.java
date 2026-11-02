package com.popnup.popnupbackend.domain.reservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.enums.Role;
import com.popnup.popnupbackend.domain.reservation.dto.response.CursorPage;
import com.popnup.popnupbackend.domain.reservation.exception.BookingOverloadException;
import com.popnup.popnupbackend.domain.reservation.service.ReservationBookingService;
import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

  private MockMvc mockMvc;

  @InjectMocks private ReservationController reservationController;
  @Mock private ReservationBookingService reservationBookingService;
  @Mock private ReservationService reservationService;

  private final AuthUser mockAuthUser = new AuthUser(1L, "test@popnup.com", "테스터", Role.ROLE_USER);

  @BeforeEach
  void setUp() {
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
            .setControllerAdvice(new ReservationExceptionHandler())
            .build();
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
  @DisplayName("목록 페이지 방식 [GET /reservations]")
  class ListPagingTest {

    @Test
    @DisplayName("cursor 파라미터가 있으면 키셋 목록, 없으면 page/size 목록으로 간다")
    void routesByCursorParam() throws Exception {
      given(reservationService.getMemberReservationsByCursor(1L, "", 20))
          .willReturn(new CursorPage<>(List.of(), null, false));
      given(reservationService.getMemberReservations(1L, 0, 20)).willReturn(List.of());

      mockMvc.perform(get("/reservations").param("cursor", "")).andExpect(status().isOk());
      mockMvc.perform(get("/reservations")).andExpect(status().isOk());

      verify(reservationService).getMemberReservationsByCursor(1L, "", 20);
      verify(reservationService).getMemberReservations(1L, 0, 20);
    }
  }

  @Nested
  @DisplayName("과부하 거절 응답 [POST /reservations]")
  class OverloadResponseTest {

    @Test
    @DisplayName("과부하로 거절되면 503과 Retry-After 헤더를 내린다")
    void overload_503WithRetryAfter() throws Exception {
      given(reservationBookingService.book(eq(1L), any()))
          .willThrow(new BookingOverloadException(2));

      mockMvc
          .perform(
              post("/reservations")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"scheduleId\":10,\"personCount\":1}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string("Retry-After", "2"));
    }
  }
}
