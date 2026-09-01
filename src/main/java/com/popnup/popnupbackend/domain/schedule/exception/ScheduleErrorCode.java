package com.popnup.popnupbackend.domain.schedule.exception;

import com.popnup.popnupbackend.global.error.ErrorCode;
import com.popnup.popnupbackend.global.error.ServiceException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ScheduleErrorCode implements ErrorCode {

    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 스케줄입니다."),

    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "종료 시간은 시작 시간 이후여야 합니다."),
    DUPLICATE_TIME_SLOT(HttpStatus.CONFLICT, "해당 시간대에 이미 등록된 스케줄이 존재합니다."),

    INVALID_CAPACITY(HttpStatus.BAD_REQUEST, "인원수는 1명 이상이어야 합니다."),
    SCHEDULE_CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "정원을 초과했습니다."),
    INVALID_CANCEL_COUNT(HttpStatus.BAD_REQUEST, "취소하려는 인원이 현재 예약된 인원보다 많습니다."),

    SCHEDULE_INACTIVE(HttpStatus.CONFLICT, "해당 타임 슬롯은 현재 예약이 불가능합니다."),
    CANNOT_DELETE_RESERVED_SCHEDULE(HttpStatus.CONFLICT, "이미 예약자가 존재하는 스케줄은 삭제할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    public ServiceException toException() {
        return new ServiceException(this);
    }


}
