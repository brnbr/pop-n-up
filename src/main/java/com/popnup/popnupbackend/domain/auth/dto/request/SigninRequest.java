package com.popnup.popnupbackend.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class SigninRequest {

  @Email(message = "이메일 형식이 올바르지 않습니다")
  private String email;

  @NotBlank
  @Size(min = 8, message = "8자이상 입력하시오")
  private String password;
}
