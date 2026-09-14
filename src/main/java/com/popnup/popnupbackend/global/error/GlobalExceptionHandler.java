package com.popnup.popnupbackend.global.error;

import com.popnup.popnupbackend.global.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ServiceException.class)
  public ResponseEntity<ApiResponse<Void>> handleServiceException(ServiceException ex) {
    ErrorCode errorCode = ex.getErrorCode();

    return ResponseEntity.status(errorCode.getHttpStatus())
        .body(ApiResponse.fail(errorCode.name(), errorCode.getMessage()));
  }

  @ExceptionHandler(PessimisticLockingFailureException.class)
  public ResponseEntity<ApiResponse<Void>> handlePessimisticLockingFailureException(
      PessimisticLockingFailureException ex) {
    log.warn("[GlobalExceptionHandler] 비관적 락 타임아웃 발생", ex);

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiResponse.fail("LOCK_TIMEOUT", "요청이 많아 처리에 실패했습니다. 잠시 후 다시 시도해주세요."));
  }
}
