package com.popnup.popnupbackend.domain.payment.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.payment.dto.request.KakaoPayOrderRequest;
import com.popnup.popnupbackend.domain.payment.dto.response.KakaoPayApproveResponse;
import com.popnup.popnupbackend.domain.payment.dto.response.KakaoPayReadyResponse;
import com.popnup.popnupbackend.domain.payment.entity.Payment;
import com.popnup.popnupbackend.domain.payment.enums.PaymentStatus;
import com.popnup.popnupbackend.domain.payment.repository.PaymentRepository;
import com.popnup.popnupbackend.domain.payment.service.PaymentCompensationService;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KakaoPayProviderTest {

  @Mock private RestTemplate restTemplate;

  @Mock private ReservationRepository reservationRepository;

  @Mock private PaymentRepository paymentRepository;

  @Mock private ReservationService reservationService;

  @Mock private PaymentCompensationService paymentCompensationService;

  @Mock private Reservation reservation;

  @Mock private Member member;

  @InjectMocks private KakaoPayProvider kakaoPayProvider;

  private Payment payment;

  @BeforeEach
  void setUp() {
    payment = new Payment(reservation, "R20260914TEST", 10000);
  }

  @Test
  void 결제_승인_성공시_결제상태가_PAID가_되고_예약이_확정된다() {

    when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

    when(reservation.getReservationNumber()).thenReturn("R20260914TEST");

    when(reservation.getMember()).thenReturn(member);

    when(reservation.getId()).thenReturn(1L);

    when(member.getId()).thenReturn(1L);

    KakaoPayApproveResponse response = new KakaoPayApproveResponse();

    when(restTemplate.postForEntity(
            eq("https://open-api.kakaopay.com/online/v1/payment/approve"),
            any(HttpEntity.class),
            eq(KakaoPayApproveResponse.class)))
        .thenReturn(ResponseEntity.ok(response));

    KakaoPayApproveResponse result = kakaoPayProvider.approve(1L, "pg-token");

    assertThat(result).isSameAs(response);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);

    verify(reservationService).confirmReservation(1L, true);
  }

  @Test
  void 이미_결제된_경우_중복_승인할_수_없다() {

    when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

    payment.approve();

    assertThatThrownBy(() -> kakaoPayProvider.approve(1L, "pg-token"))
        .isInstanceOf(RuntimeException.class);

    verify(restTemplate, never())
        .postForEntity(any(String.class), any(HttpEntity.class), eq(KakaoPayApproveResponse.class));

    verify(reservationService, never()).confirmReservation(anyLong(), anyBoolean());
  }

  @Test
  void 존재하지_않는_payment면_예외가_발생한다() {

    when(paymentRepository.findById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> kakaoPayProvider.approve(999L, "pg-token"))
        .isInstanceOf(RuntimeException.class);

    verify(restTemplate, never())
        .postForEntity(any(String.class), any(HttpEntity.class), eq(KakaoPayApproveResponse.class));

    verify(reservationService, never()).confirmReservation(anyLong(), anyBoolean());
  }

  @Test
  void 카카오페이_승인_API_실패시_결제는_PAID가_되지_않는다() {

    when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

    when(reservation.getReservationNumber()).thenReturn("R20260914TEST");

    when(reservation.getMember()).thenReturn(member);

    when(member.getId()).thenReturn(1L);

    when(restTemplate.postForEntity(
            eq("https://open-api.kakaopay.com/online/v1/payment/approve"),
            any(HttpEntity.class),
            eq(KakaoPayApproveResponse.class)))
        .thenThrow(new RuntimeException("카카오페이 API 오류"));

    assertThatThrownBy(() -> kakaoPayProvider.approve(1L, "pg-token"))
        .isInstanceOf(RuntimeException.class);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);

    verify(reservationService, never()).confirmReservation(anyLong(), anyBoolean());
  }

  @Test
  void 이미_결제된_예약은_결제_준비를_할_수_없다() throws Exception {
    // given
    Payment paidPayment = new Payment(reservation, "R20260914TEST", 10000);
    paidPayment.approve();

    // 로그인한 사용자 설정
    Authentication authentication = mock(Authentication.class);
    SecurityContext securityContext = mock(SecurityContext.class);
    AuthUser authUser = mock(AuthUser.class);

    when(securityContext.getAuthentication()).thenReturn(authentication);

    when(authentication.getPrincipal()).thenReturn(authUser);

    when(authUser.getId()).thenReturn(1L);

    SecurityContextHolder.setContext(securityContext);

    // 예약 조회
    when(reservationRepository.findById(1L)).thenReturn(Optional.of(reservation));

    // 예약자 확인
    when(reservation.getMember()).thenReturn(member);

    when(member.getId()).thenReturn(1L);

    // 기존 결제 조회
    when(reservation.getId()).thenReturn(1L);

    when(paymentRepository.findByReservationId(1L)).thenReturn(Optional.of(paidPayment));

    ObjectMapper objectMapper = new ObjectMapper();

    KakaoPayOrderRequest request =
        objectMapper.readValue(
            """
                    {
                      "reservationId": 1,
                      "itemName": "테스트 팝업",
                      "quantity": 1,
                      "totalPrice": 10000
                    }
                    """,
            KakaoPayOrderRequest.class);

    // when & then
    assertThatThrownBy(() -> kakaoPayProvider.ready(request)).isInstanceOf(RuntimeException.class);

    verify(restTemplate, never())
        .postForEntity(any(String.class), any(HttpEntity.class), eq(KakaoPayReadyResponse.class));

    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("카카오 결제 승인 성공 후 예약 확정에 실패하면 보상 처리를 요청하고 예외가 발생한다")
  void approveFailsWhenReservationConfirmFails() {

    // given
    when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

    when(reservation.getReservationNumber()).thenReturn("R20260914TEST");

    when(reservation.getMember()).thenReturn(member);

    when(reservation.getId()).thenReturn(1L);

    when(member.getId()).thenReturn(1L);

    KakaoPayApproveResponse kakaoResponse = new KakaoPayApproveResponse();

    when(restTemplate.postForEntity(
            eq("https://open-api.kakaopay.com/online/v1/payment/approve"),
            any(HttpEntity.class),
            eq(KakaoPayApproveResponse.class)))
        .thenReturn(ResponseEntity.ok(kakaoResponse));

    doThrow(new RuntimeException("예약 확정 실패")).when(reservationService).confirmReservation(1L, true);

    // when & then
    assertThatThrownBy(() -> kakaoPayProvider.approve(1L, "pg-token"))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("예약 확정 실패");

    // 카카오 승인 API는 성공
    verify(restTemplate, times(1))
        .postForEntity(
            eq("https://open-api.kakaopay.com/online/v1/payment/approve"),
            any(HttpEntity.class),
            eq(KakaoPayApproveResponse.class));

    // 예약 확정 실패 후 보상 처리 요청
    verify(paymentCompensationService, times(1)).compensate(1L);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
  }
}
