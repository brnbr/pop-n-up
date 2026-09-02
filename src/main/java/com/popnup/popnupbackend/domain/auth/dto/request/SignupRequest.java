package com.popnup.popnupbackend.domain.auth.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;

@Getter
public class SignupRequest {

  @Email(message = "이메일 형식이 올바르지 않습니다")
  private String email;

  @NotBlank
  @Size(min = 8, message = "8자이상 입력하시오")
  private String password;

  @NotBlank
  @Size(max = 20, message = "20자 이상은 불가합니다")
  private String name;
}
