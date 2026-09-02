package com.popnup.popnupbackend.global.error;

import com.popnup.popnupbackend.global.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ServiceException.class)
  public ResponseEntity<ApiResponse<Void>> handleServiceException(ServiceException ex) {
    ErrorCode errorCode = ex.getErrorCode();

    return ResponseEntity.status(errorCode.getHttpStatus())
        .body(ApiResponse.fail(errorCode.name(), errorCode.getMessage()));
  }
}
