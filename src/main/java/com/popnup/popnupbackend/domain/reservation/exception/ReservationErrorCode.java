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
  INVALID_RESERVATION_STATUS(HttpStatus.CONFLICT, "유효하지 않은 예약 상태입니다."),

  ALREADY_PROCESSED_RESERVATION(HttpStatus.CONFLICT, "이미 처리 완료된 예약입니다."),
  DUPLICATE_USER_RESERVATION(HttpStatus.CONFLICT, "해당 타임 슬롯이 이미 진행 중인 예약이 있습니다"),

  CANCEL_NOT_ALLOWED_AFTER_START(HttpStatus.CONFLICT, "이미 시작된 회차의 결제 완료 예약은 취소할 수 없습니다."),
  CHECK_IN_NOT_AVAILABLE_TIME(
      HttpStatus.CONFLICT, "체크인 가능한 시간이 아닙니다. 예약한 회차 당일, 회차 시작 20분 후까지만 체크인할 수 있습니다."),
  INVALID_PERSON_COUNT(HttpStatus.BAD_REQUEST, "예약 인원은 1명 이상 10명 이하여야 합니다."),
  QR_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "QR 코드 생성에 실패 했습니다.");

  private final HttpStatus httpStatus;
  private final String message;

  public ServiceException toException() {
    return new ServiceException(this);
  }
}
