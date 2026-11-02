package com.popnup.popnupbackend.global.common;

import lombok.Getter;

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
