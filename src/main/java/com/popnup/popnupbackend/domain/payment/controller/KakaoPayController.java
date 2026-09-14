package com.popnup.popnupbackend.domain.payment.controller;

import com.popnup.popnupbackend.domain.payment.dto.request.KakaoPayOrderRequest;
import com.popnup.popnupbackend.domain.payment.dto.response.KakaoPayApproveResponse;
import com.popnup.popnupbackend.domain.payment.dto.response.KakaoPayReadyResponse;
import com.popnup.popnupbackend.domain.payment.provider.KakaoPayProvider;
import com.popnup.popnupbackend.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/kakao-pay")
public class KakaoPayController {

  private final KakaoPayProvider kakaoPayProvider;

  @PostMapping("/ready")
  public ResponseEntity<ApiResponse<KakaoPayReadyResponse>> ready(
      @RequestBody KakaoPayOrderRequest reqeust) {
    return ResponseEntity.ok(ApiResponse.success(kakaoPayProvider.ready(reqeust)));
  }

  @GetMapping("/approve")
  public ResponseEntity<ApiResponse<KakaoPayApproveResponse>> approve(
      @RequestParam("paymentId") Long paymentId, @RequestParam("pg_token") String pgToken) {
    return ResponseEntity.ok(ApiResponse.success(kakaoPayProvider.approve(paymentId, pgToken)));
  }
}
