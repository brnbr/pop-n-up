package com.popnup.popnupbackend.domain.reservation.entity;

import com.popnup.popnupbackend.domain.reservation.enums.ReservationStatus;
import com.popnup.popnupbackend.domain.schedule.entity.Schedule;
import com.popnup.popnupbackend.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.catalina.User;

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
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @Column(nullable = false)
    private Integer personCount;

    @Column(length = 500)
    private String qrCodeUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationStatus status;

    private Reservation(String reservationNumber, User user, Schedule schedule, Integer personCount, ReservationStatus status) {
        this.reservationNumber = reservationNumber;
        this.user = user;
        this.schedule = schedule;
        this.personCount = personCount;
        this.status = status;
    }

    //예약 생성 - 결제하면 confirmed
    public static Reservation createReservation(String reservationNumber, User user, Schedule schedule, Integer personCount) {
        return new Reservation(reservationNumber, user, schedule, personCount, ReservationStatus.PENDING);
    }

    //결제 후 예약 최종 확정
    public void confirm() {
        if (this.status != ReservationStatus.PENDING) {
            throw
        }
        this.status = ReservationStatus.CONFIRMED;
    }

    //qr 발급
    public void registerQrCode(String qrCodeUrl) {
        this.qrCodeUrl = qrCodeUrl;
    }

    //예약 취소
    public void cancel() {
        if (this.status == ReservationStatus.CANCELED) {
            throw
        }

        this.status = ReservationStatus.CANCELED;
    }
}
