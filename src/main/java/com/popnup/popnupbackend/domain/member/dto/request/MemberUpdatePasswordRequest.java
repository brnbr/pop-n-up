package com.popnup.popnupbackend.domain.member.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class MemberUpdatePasswordRequest {

  @NotBlank private String oldPassword;

  @NotBlank
  @Size(min = 8, message = "8자 이상 입력하세요")
  private String newPassword;
}
