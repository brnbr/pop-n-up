package com.popnup.popnupbackend.domain.admin.sevice;

import com.popnup.popnupbackend.domain.admin.dto.request.AdminMemberResponse;
import com.popnup.popnupbackend.domain.admin.dto.request.AdminMemberStatusUpdateRequest;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminService {

  private final MemberRepository memberRepository;

  @Transactional(readOnly = true)
  public List<AdminMemberResponse> getMembers() {
    List<Member> members = memberRepository.findAll();

    return members.stream()
        .map(
            member ->
                new AdminMemberResponse(
                    member.getId(),
                    member.getEmail(),
                    member.getName(),
                    member.getRole(),
                    member.getProvider(),
                    member.getStatus()))
        .toList();
  }

  @Transactional(readOnly = true)
  public AdminMemberResponse getMember(Long memberId) {
    Member member =
        memberRepository
            .findById(memberId)
            .orElseThrow(MemberErrorCode.MEMBER_NOT_FOUND::toException);

    return new AdminMemberResponse(
        member.getId(),
        member.getEmail(),
        member.getName(),
        member.getRole(),
        member.getProvider(),
        member.getStatus());
  }

  @Transactional
  public void updateMemberStatus(Long memberId, AdminMemberStatusUpdateRequest request) {
    Member member =
        memberRepository
            .findById(memberId)
            .orElseThrow(MemberErrorCode.MEMBER_NOT_FOUND::toException);

    member.updateStatus(request.getStatus());
  }
}
