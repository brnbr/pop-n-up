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

  @OneToOne(fetch = FetchType.LAZY) // 결제 1건에 예약 1건이라 가정
  @JoinColumn(name = "reservation_id", nullable = false)
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

  public void approve() {
    this.status = PaymentStatus.PAID;
  }

  public void fail() {
    this.status = PaymentStatus.FAILED;
  }
}
