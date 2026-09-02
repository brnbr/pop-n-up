package com.popnup.popnupbackend.domain.member.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class MemberDeleteRequest {

  @NotBlank private String password;
}
