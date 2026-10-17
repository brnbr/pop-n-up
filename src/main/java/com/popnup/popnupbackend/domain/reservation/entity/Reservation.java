package com.popnup.popnupbackend.domain.reservation.entity;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.reservation.exception.ReservationErrorCode;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "reservations",
    uniqueConstraints = {
      @UniqueConstraint(
          name = Reservation.UK_ACTIVE_MEMBER,
          columnNames = {"schedule_id", "active_member_id"})
    },
    indexes = {
      @Index(name = "idx_reservation_status_created_at", columnList = "status, created_at"),
      @Index(name = "idx_reservation_schedule", columnList = "schedule_id")
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseEntity {

  public static final String UK_ACTIVE_MEMBER = "uk_reservation_active_member";

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

  @Column(name = "active_member_id")
  private Long activeMemberId;

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
    this.activeMemberId = member.getId();
  }

  public static Reservation createReservation(
      String reservationNumber, Member member, Schedule schedule, Integer personCount) {
    return new Reservation(
        reservationNumber, member, schedule, personCount, ReservationStatus.PENDING);
  }

  public void confirm() {
    if (!this.status.canConfirm()) {
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }

    this.status = ReservationStatus.CONFIRMED;
  }

  public void validatePayable() {
    if (!this.status.canConfirm()) {
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }
  }

  public void checkIn(LocalDateTime currentDateTime) {
    if (this.status == ReservationStatus.USED) {
      throw ReservationErrorCode.ALREADY_PROCESSED_RESERVATION.toException();
    }
    if (!this.status.canCheckIn()) {
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }
    if (!this.schedule.isCheckInAvailable(currentDateTime)) {
      throw ReservationErrorCode.CHECK_IN_NOT_AVAILABLE_TIME.toException();
    }

    this.status = ReservationStatus.USED;
  }

  public void validateOwnedBy(Long memberId) {
    if (memberId == null || !memberId.equals(this.member.getId())) {
      throw ReservationErrorCode.UNAUTHORIZED_RESERVATION_ACCESS.toException();
    }
  }

  public void validateCancelable(LocalDateTime currentDateTime) {
    if (this.status == ReservationStatus.CONFIRMED
        && this.schedule.isAlreadyStarted(currentDateTime)) {
      throw ReservationErrorCode.CANCEL_NOT_ALLOWED_AFTER_START.toException();
    }
  }

  public void validateQrIssuable() {
    if (this.status != ReservationStatus.CONFIRMED) {
      throw ReservationErrorCode.INVALID_RESERVATION_STATUS.toException();
    }
  }
}
