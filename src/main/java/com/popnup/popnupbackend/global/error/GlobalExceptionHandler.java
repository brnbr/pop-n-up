package com.popnup.popnupbackend.global.error;

import com.meetmate.meetmatebackend.global.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ServiceException.class)
  public ResponseEntity<ApiResponse<Void>> handleServiceException(ServiceException ex) {

    return ResponseEntity.status(ex.getStatus())
        .body(ApiResponse.fail(String.valueOf(ex.getStatus().value()), ex.getMessage()));
  }
}
