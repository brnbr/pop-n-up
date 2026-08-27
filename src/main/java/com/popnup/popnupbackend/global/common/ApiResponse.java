package com.popnup.popnupbackend.global.common;

import lombok.Getter;

// API 응답을 표준화하기 위한 클래스
// 성공 여부, 상태 코드, 메시지, 콘텐츠를 포함
// 제네릭 타입 T를 사용하여 다양한 타입의 콘텐츠(반환 타입)를 처리할 수 있도록 함
// 사용 - ResponseEntity<ApiResponse<T>> 형태로 사용

@Getter
public class ApiResponse<T> {

  private final boolean success;
  private final String code;
  private final String message;
  private final T content;

  private static final String SUCCESS_CODE = "200";

  public ApiResponse(boolean success, String code, String message, T content) {
    this.success = success;
    this.code = code;
    this.message = message;
    this.content = content;
  }

  public static <T> ApiResponse<T> success(T content) {
    return new ApiResponse<>(true, SUCCESS_CODE, "Success", content);
  }

  public static <T> ApiResponse<T> success(String message, T content) {
    return new ApiResponse<>(true, SUCCESS_CODE, message, content);
  }

  public static ApiResponse<Void> success() {
    return new ApiResponse<>(true, SUCCESS_CODE, "Success", null);
  }

  public static <T> ApiResponse<T> fail(String code, String message) {
    return new ApiResponse<>(false, code, message, null);
  }
}
