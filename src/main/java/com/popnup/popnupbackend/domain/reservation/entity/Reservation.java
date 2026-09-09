package com.popnup.popnupbackend.domain.reservation.entity;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 50)
  private String reservationNumber;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "member_id", nullable = false)
  private Member member;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "schedule_id", nullable = false)
  private Schedule schedule;

  @Column(nullable = false)
  private Integer personCount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ReservationStatus status;

  private Reservation(
      String reservationNumber,
      Member member,
      Schedule schedule,
      Integer personCount,
      ReservationStatus status) {
    this.reservationNumber = reservationNumber;
    this.member = member;
    this.schedule = schedule;
    this.personCount = personCount;
    this.status = status;
  }

  // 예약 생성 - 결제하면 confirme되게 추가해야 함
  public static Reservation createReservation(
      String reservationNumber, Member member, Schedule schedule, Integer personCount) {
    return new Reservation(
        reservationNumber, member, schedule, personCount, ReservationStatus.PENDING);
  }

  /* todo
      결제 후 예약 최종 확정
      결제 도메인 도입 후 보완 필요
      결제 결과 받아서 상태/유효성 확인 후 confirm 처리
  */
  public void confirm() {
    if (this.status != ReservationStatus.PENDING) {
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }

    this.status = ReservationStatus.CONFIRMED;
  }

  // qr 체크인
  public void checkIn() {
    if (this.status == ReservationStatus.USED) {
      throw ReservationErrorCode.ALREADY_PROCESSED_RESERVATION.toException();
    }
    if (this.status != ReservationStatus.CONFIRMED) {
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }

    this.status = ReservationStatus.USED;
  }

  // 예약 취소
  public void cancel() {
    if (this.status != ReservationStatus.CONFIRMED && this.status != ReservationStatus.PENDING) {
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }

    if (this.status == ReservationStatus.USED) {
      throw ReservationErrorCode.ALREADY_PROCESSED_RESERVATION.toException();
    }

    this.status = ReservationStatus.CANCELED;
  }

  // 소유자 확인
  public boolean isOwnedBy(Long memberId) {
    if (memberId == null || this.member == null) {
      return false;
    }

    return memberId.equals(this.member.getId());
  }
}
