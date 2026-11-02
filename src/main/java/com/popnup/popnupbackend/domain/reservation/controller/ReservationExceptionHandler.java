package com.popnup.popnupbackend.domain.reservation.controller;

import com.popnup.popnupbackend.domain.reservation.exception.BookingOverloadException;
import com.popnup.popnupbackend.global.common.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ReservationController.class)
public class ReservationExceptionHandler {

  @ExceptionHandler(BookingOverloadException.class)
  public ResponseEntity<ApiResponse<Void>> handleOverload(BookingOverloadException ex) {
    return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
        .body(ApiResponse.fail(ex.getErrorCode().name(), ex.getErrorCode().getMessage()));
  }
}
