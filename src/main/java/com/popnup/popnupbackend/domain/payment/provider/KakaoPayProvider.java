package com.popnup.popnupbackend.domain.payment.provider;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.payment.dto.request.KakaoPayApproveRequest;
import com.popnup.popnupbackend.domain.payment.dto.request.KakaoPayOrderRequest;
import com.popnup.popnupbackend.domain.payment.dto.request.KakaoPayReadyRequest;
import com.popnup.popnupbackend.domain.payment.dto.response.KakaoPayApproveResponse;
import com.popnup.popnupbackend.domain.payment.dto.response.KakaoPayReadyResponse;
import com.popnup.popnupbackend.domain.payment.entity.Payment;
import com.popnup.popnupbackend.domain.payment.enums.PaymentStatus;
import com.popnup.popnupbackend.domain.payment.exception.PayErrorCode;
import com.popnup.popnupbackend.domain.payment.repository.PaymentRepository;
import com.popnup.popnupbackend.domain.payment.service.PaymentCompensationService;
import com.popnup.popnupbackend.domain.popup.entity.Popup;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.reservation.repository.ReservationRepository;
import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@Transactional
@RequiredArgsConstructor
public class KakaoPayProvider {
  // 카카오페이 서버에 결제 요청해주는 담당자

  private final RestTemplate restTemplate;
  private final ReservationRepository reservationRepository;
  private final PaymentRepository paymentRepository;
  private final ReservationService reservationService;
  private final PaymentCompensationService paymentCompensationService;

  // restTempalte == 다른 서버에 http 요청을 보내는 도구, Rest 방식으로 Api를 호출할 수 있는 spring 내장 클래스

  @Value("${kakaopay.secretKey}")
  private String secretKey;

  @Value("${kakaopay.cid}")
  private String cid;

  // 카카오페이에 결제 준비 요청을 보내고 카카오페이가 보내준 결과 반환
  public KakaoPayReadyResponse ready(KakaoPayOrderRequest request) {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    AuthUser authUser = (AuthUser) authentication.getPrincipal();

    Long memberId = authUser.getId();

    // 1. 예약 조회
    Reservation reservation =
        reservationRepository
            .findById(request.getReservationId())
            .orElseThrow(ReservationErrorCode.RESERVATION_NOT_FOUND::toException);

    // 2. 예약자 본인인지 확인
    if (!reservation.getMember().getId().equals(memberId)) {
      throw PayErrorCode.RESERVATION_NOT_MATCH.toException();
    }

    // 3. 기존 Payment 조회
    Optional<Payment> existingPayment = paymentRepository.findByReservationId(reservation.getId());

    Payment payment;

    if (existingPayment.isPresent()) {
      payment = existingPayment.get();

      // 이미 결제된 경우 중복 결제 방지
      if (payment.getStatus() == PaymentStatus.PAID) {
        throw PayErrorCode.ALREADY_PAID.toException();
      }
    } else {
      // 기존 Payment가 없으면 팝업 정보를 이용해 새 결제 생성

      Popup popup = reservation.getSchedule().getPopup();

      Integer totalPrice = popup.getPrice() * reservation.getPersonCount();

      payment = new Payment(reservation, reservation.getReservationNumber(), totalPrice);

      payment = paymentRepository.save(payment);
    }

    // 4. 팝업 정보
    Popup popup = reservation.getSchedule().getPopup();

    String itemName = popup.getTitle();
    Integer quantity = reservation.getPersonCount();
    Integer totalPrice = popup.getPrice() * reservation.getPersonCount();

    // 5. 카카오페이에 보낼 결제 준비 정보
    KakaoPayReadyRequest kakaoPayReadyRequest =
        KakaoPayReadyRequest.builder()
            .cid(cid)
            .partnerOrderId(reservation.getReservationNumber())
            .partnerUserId(String.valueOf(reservation.getMember().getId()))
            .itemName(itemName)
            .quantity(quantity)
            .totalAmount(totalPrice)
            .taxFreeAmount(0)
            .approvalUrl(
                "http://localhost:8080/api/v1/kakao-pay/approve?paymentId=" + payment.getId())
            .cancelUrl("http://localhost:8080/api/v1/kakao-pay/cancel")
            .failUrl("http://localhost:8080/kakao-pay/fail")
            .build();

    HttpEntity<KakaoPayReadyRequest> entity = new HttpEntity<>(kakaoPayReadyRequest, getHeaders());

    // 6. 카카오페이 API 호출
    ResponseEntity<KakaoPayReadyResponse> response =
        restTemplate.postForEntity(
            "https://open-api.kakaopay.com/online/v1/payment/ready",
            entity,
            KakaoPayReadyResponse.class);

    // 7. tid 저장
    KakaoPayReadyResponse body = Objects.requireNonNull(response.getBody());

    payment.setTid(body.getTid());

    return body;
  }

  // apporove API : 결제 성공시 자동으로 호출되는 결제 승인 api
  public KakaoPayApproveResponse approve(Long paymentId, String pgToken) {

    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(PayErrorCode.PAYMENT_NOT_FOUND::toException);

    if (payment.getStatus() == PaymentStatus.PAID) {
      throw PayErrorCode.ALREADY_PAID.toException();
    }

    Reservation reservation = payment.getReservation();

    KakaoPayApproveRequest request =
        KakaoPayApproveRequest.builder()
            .cid(cid)
            .tid(payment.getTid())
            .partnerOrderId(reservation.getReservationNumber())
            .partnerUserId(String.valueOf(reservation.getMember().getId()))
            .pgToken(pgToken)
            .build();

    HttpEntity<KakaoPayApproveRequest> entity = new HttpEntity<>(request, getHeaders());

    ResponseEntity<KakaoPayApproveResponse> response =
        restTemplate.postForEntity(
            "https://open-api.kakaopay.com/online/v1/payment/approve",
            entity,
            KakaoPayApproveResponse.class);

    KakaoPayApproveResponse body = Objects.requireNonNull(response.getBody());

    // 여기까지 오면 카카오에서는 이미 결제 승인 완료
    payment.approve();

    try {

      reservationService.confirmReservation(reservation.getId(), true);

    } catch (Exception e) {
      paymentCompensationService.compensate(paymentId);
      throw e;
    }

    return body;
  }

  // 카카오페이 api를 호출할 때 필요한 인증정보와 데이터 형식 header에 넣음
  private HttpHeaders getHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Authorization", "SECRET_KEY " + secretKey);
    headers.add("Content-type", "application/json");
    return headers;
  }
}
