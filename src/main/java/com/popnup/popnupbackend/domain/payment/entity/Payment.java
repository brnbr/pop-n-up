package com.popnup.popnupbackend.domain.payment.entity;

import com.popnup.popnupbackend.domain.payment.enums.PaymentStatus;
import com.popnup.popnupbackend.domain.reservation.entity.Reservation;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reservation_id", nullable = false, unique = true)
  private Reservation reservation;

  @Column(nullable = false)
  private String orderId;

  private String tid;

  @Column(nullable = false)
  private Integer amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentStatus status;

  public Payment(Reservation reservation, String orderId, Integer amount) {
    this.reservation = reservation;
    this.orderId = orderId;
    this.amount = amount;
    this.status = PaymentStatus.READY;
  }

  public void setTid(String tid) {
    this.tid = tid;
  }

  // 결제 상태를 하나의 상태 머신처럼 보기 .
  public void approve() {
    if (this.status != PaymentStatus.READY) {
      throw new IllegalStateException("결제 승인 가능한 상태가 아닙니다.");
    }

    this.status = PaymentStatus.PAID;
  }

  public void fail() {
    if (this.status != PaymentStatus.READY) {
      throw new IllegalStateException("결제 실패 처리 가능한 상태가 아닙니다.");
    }

    this.status = PaymentStatus.FAILED;
  }

  public void cancel() {
    if (this.status != PaymentStatus.PAID) {
      throw new IllegalStateException("결제 취소 가능한 상태가 아닙니다.");
    }

    this.status = PaymentStatus.CANCELED;
  }

  public void requireReconciliation() {
    this.status = PaymentStatus.RECONCILIATION_REQUIRED;
  }
}
