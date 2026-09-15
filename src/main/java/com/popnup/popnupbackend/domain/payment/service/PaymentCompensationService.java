package com.popnup.popnupbackend.domain.payment.service;

import com.popnup.popnupbackend.domain.payment.dto.request.KakaoPayCancelRequest;
import com.popnup.popnupbackend.domain.payment.dto.response.KakaoPayCancelResponse;
import com.popnup.popnupbackend.domain.payment.entity.Payment;
import com.popnup.popnupbackend.domain.payment.exception.PayErrorCode;
import com.popnup.popnupbackend.domain.payment.repository.PaymentRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCompensationService {

  private final RestTemplate restTemplate;
  private final PaymentRepository paymentRepository;

  @Value("${kakaopay.secretKey}")
  private String secretKey;

  @Value("${kakaopay.cid}")
  private String cid;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void compensate(Long paymentId) {

    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(PayErrorCode.PAYMENT_NOT_FOUND::toException);

    try {
      cancelKakaoPayment(payment);

      payment.cancel();

      log.info("카카오페이 보상 취소 성공 paymentId={}", paymentId);

    } catch (Exception e) {

      payment.requireReconciliation();

      log.error("카카오페이 보상 취소 실패. 수동 복구 필요 paymentId={}", paymentId, e);
    }
  }

  private KakaoPayCancelResponse cancelKakaoPayment(Payment payment) {

    KakaoPayCancelRequest request =
        KakaoPayCancelRequest.builder()
            .cid(cid)
            .tid(payment.getTid())
            .cancelAmount(payment.getAmount())
            .cancelTaxFreeAmount(0)
            .build();

    HttpEntity<KakaoPayCancelRequest> entity = new HttpEntity<>(request, getHeaders());

    ResponseEntity<KakaoPayCancelResponse> response =
        restTemplate.postForEntity(
            "https://open-api.kakaopay.com/online/v1/payment/cancel",
            entity,
            KakaoPayCancelResponse.class);

    return Objects.requireNonNull(response.getBody());
  }

  private HttpHeaders getHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Authorization", "SECRET_KEY " + secretKey);
    headers.add("Content-type", "application/json");
    return headers;
  }
}
