package com.popnup.popnupbackend.domain.admin.dto.request;

import com.popnup.popnupbackend.domain.member.enums.MemberStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AdminMemberStatusUpdateRequest {

  private MemberStatus status;
}
