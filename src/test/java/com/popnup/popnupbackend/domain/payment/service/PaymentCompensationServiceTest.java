package com.popnup.popnupbackend.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.payment.dto.response.KakaoPayCancelResponse;
import com.popnup.popnupbackend.domain.payment.entity.Payment;
import com.popnup.popnupbackend.domain.payment.enums.PaymentStatus;
import com.popnup.popnupbackend.domain.payment.repository.PaymentRepository;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentCompensationServiceTest {

  @Mock private RestTemplate restTemplate;

  @Mock private PaymentRepository paymentRepository;

  @Mock private Reservation reservation;

  @InjectMocks private PaymentCompensationService paymentCompensationService;

  private Payment payment;

  @BeforeEach
  void setUp() {
    payment = new Payment(reservation, "R20260915TEST", 10000);

    // 보상 취소는 이미 카카오 승인이 성공한 이후 실행되므로 PAID 상태
    payment.setTid("T123456789");
    payment.approve();

    // @Value는 Mockito 단위 테스트에서 자동 주입되지 않으므로 직접 설정
    ReflectionTestUtils.setField(paymentCompensationService, "secretKey", "test-secret-key");

    ReflectionTestUtils.setField(paymentCompensationService, "cid", "TC0ONETIME");
  }

  @Test
  @DisplayName("카카오페이 보상 취소 성공 시 결제 상태가 CANCELED가 된다")
  void compensateSuccess() {

    // given
    when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

    KakaoPayCancelResponse response = new KakaoPayCancelResponse();

    when(restTemplate.postForEntity(
            eq("https://open-api.kakaopay.com/online/v1/payment/cancel"),
            any(HttpEntity.class),
            eq(KakaoPayCancelResponse.class)))
        .thenReturn(ResponseEntity.ok(response));

    // when
    paymentCompensationService.compensate(1L);

    // then
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);

    verify(restTemplate, times(1))
        .postForEntity(
            eq("https://open-api.kakaopay.com/online/v1/payment/cancel"),
            any(HttpEntity.class),
            eq(KakaoPayCancelResponse.class));
  }

  @Test
  @DisplayName("카카오페이 보상 취소 실패 시 결제 상태가 RECONCILIATION_REQUIRED가 된다")
  void compensateFailure() {

    // given
    when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

    when(restTemplate.postForEntity(
            eq("https://open-api.kakaopay.com/online/v1/payment/cancel"),
            any(HttpEntity.class),
            eq(KakaoPayCancelResponse.class)))
        .thenThrow(new RuntimeException("카카오페이 취소 API 오류"));

    // when
    paymentCompensationService.compensate(1L);

    // then
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);

    verify(restTemplate, times(1))
        .postForEntity(
            eq("https://open-api.kakaopay.com/online/v1/payment/cancel"),
            any(HttpEntity.class),
            eq(KakaoPayCancelResponse.class));
  }
}
