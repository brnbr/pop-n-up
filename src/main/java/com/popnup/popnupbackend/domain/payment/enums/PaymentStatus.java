package com.popnup.popnupbackend.domain.payment.enums;

public enum PaymentStatus {
  READY, // 결제 준비 완료, 승인전
  PAID, // 카카오 승인 + db 처리 완
  CANCELED, // 승인 . 문제 생겨 카카오 결제 취소까지
  FAILED, // 카카오 승인 자체 실패
  RECONCILIATION_REQUIRED // 카카오 승인 O , DB 처리 실패 카카오 취소도 실패
}
