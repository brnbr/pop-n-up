package com.popnup.popnupbackend.domain.reservation.exception;

import com.popnup.popnupbackend.global.error.ErrorCode;
import com.popnup.popnupbackend.global.error.ServiceException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {

    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 예약입니다."),

    UNAUTHORIZED_RESERVATION_ACCESS(HttpStatus.FORBIDDEN, "해당 예약에 접근 권한이 없습니다."),

    INVALID_RESERVATION_STATUS(HttpStatus.BAD_REQUEST, "유효하지 않은 예약 상태입니다."),
    INVALID_QR_CODE(HttpStatus.BAD_REQUEST, "유효하지 않은 QR 코드입니다."),

    ALREADY_CANCELED_RESERVATION(HttpStatus.CONFLICT, "이미 취소된 예약입니다."),
    ALREADY_PROCESSED_RESERVATION(HttpStatus.CONFLICT, "이미 처리 완료된 예약입니다."),
    DUPLICATE_USER_RESERVATION(HttpStatus.CONFLICT, "해당 타임 슬롯이 이미 진행 중인 예약이 있습니다");

    private final HttpStatus httpStatus;
    private final String message;

    public ServiceException toException() {
        return new ServiceException(this);
    }
}
