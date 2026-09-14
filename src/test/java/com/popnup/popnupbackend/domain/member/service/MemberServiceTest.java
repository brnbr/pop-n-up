package com.popnup.popnupbackend.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.dto.request.MemberDeleteRequest;
import com.popnup.popnupbackend.domain.member.dto.request.MemberUpdatePasswordRequest;
import com.popnup.popnupbackend.domain.member.dto.response.MemberGetResponse;
import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.enums.MemberStatus;
import com.popnup.popnupbackend.domain.member.exception.MemberErrorCode;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import com.popnup.popnupbackend.global.error.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

  @Mock private MemberRepository memberRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @Mock private AuthUser authUser;

  @InjectMocks private MemberService memberService;

  private Member member;

  @BeforeEach
  void setUp() {
    member = Member.createLocal("test@test.com", "encodedPassword", "테스트회원");

    ReflectionTestUtils.setField(member, "id", 1L);
  }

  // =========================
  // getMe()
  // =========================

  @Test
  void 내정보_조회_성공() {
    // given
    when(memberRepository.findByIdAndStatusNot(1L, MemberStatus.DELETED))
        .thenReturn(Optional.of(member));

    // when
    MemberGetResponse response = memberService.getMe(1L);

    // then
    assertThat(response.getId()).isEqualTo(1L);
    assertThat(response.getEmail()).isEqualTo("test@test.com");
    assertThat(response.getName()).isEqualTo("테스트회원");
    assertThat(response.getRole()).isEqualTo("ROLE_USER");

    verify(memberRepository).findByIdAndStatusNot(1L, MemberStatus.DELETED);
  }

  @Test
  void 내정보_조회_회원이_없으면_예외() {
    // given
    when(memberRepository.findByIdAndStatusNot(1L, MemberStatus.DELETED))
        .thenReturn(Optional.empty());

    // when
    ServiceException exception =
        catchThrowableOfType(() -> memberService.getMe(1L), ServiceException.class);

    // then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

    verify(memberRepository).findByIdAndStatusNot(1L, MemberStatus.DELETED);
  }

  // =========================
  // updatePassword()
  // =========================

  @Test
  void 비밀번호_변경_성공() {
    // given
    when(authUser.getId()).thenReturn(1L);

    when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

    when(passwordEncoder.matches("oldPassword", "encodedPassword")).thenReturn(true);

    when(passwordEncoder.encode("newPassword123")).thenReturn("newEncodedPassword");

    MemberUpdatePasswordRequest request = new MemberUpdatePasswordRequest();

    ReflectionTestUtils.setField(request, "oldPassword", "oldPassword");
    ReflectionTestUtils.setField(request, "newPassword", "newPassword123");

    // when
    memberService.updatePassword(authUser, request);

    // then
    assertThat(member.getPassword()).isEqualTo("newEncodedPassword");

    verify(memberRepository).findById(1L);
    verify(passwordEncoder).matches("oldPassword", "encodedPassword");
    verify(passwordEncoder).encode("newPassword123");
  }

  @Test
  void 비밀번호_변경_회원이_없으면_예외() {
    // given
    when(authUser.getId()).thenReturn(1L);

    when(memberRepository.findById(1L)).thenReturn(Optional.empty());

    MemberUpdatePasswordRequest request = new MemberUpdatePasswordRequest();

    ReflectionTestUtils.setField(request, "oldPassword", "oldPassword");

    ReflectionTestUtils.setField(request, "newPassword", "newPassword123");

    // when
    ServiceException exception =
        catchThrowableOfType(
            () -> memberService.updatePassword(authUser, request), ServiceException.class);

    // then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

    verify(memberRepository).findById(1L);
    verify(passwordEncoder, never()).matches(any(), any());
  }

  @Test
  void 비활성_회원은_비밀번호를_변경할_수_없다() {
    // given
    member.suspend();

    when(authUser.getId()).thenReturn(1L);

    when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

    MemberUpdatePasswordRequest request = new MemberUpdatePasswordRequest();

    ReflectionTestUtils.setField(request, "oldPassword", "oldPassword");

    ReflectionTestUtils.setField(request, "newPassword", "newPassword123");

    // when
    ServiceException exception =
        catchThrowableOfType(
            () -> memberService.updatePassword(authUser, request), ServiceException.class);

    // then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_VALIDATE_ACTIVE);

    verify(passwordEncoder, never()).matches(any(), any());
  }

  @Test
  void OAuth_회원은_비밀번호를_변경할_수_없다() {
    // given
    Member oauthMember = Member.createOAuth2("kakao@test.com", "카카오회원", "kakao-123");

    ReflectionTestUtils.setField(oauthMember, "id", 1L);

    when(authUser.getId()).thenReturn(1L);

    when(memberRepository.findById(1L)).thenReturn(Optional.of(oauthMember));

    MemberUpdatePasswordRequest request = new MemberUpdatePasswordRequest();

    ReflectionTestUtils.setField(request, "oldPassword", "oldPassword");

    ReflectionTestUtils.setField(request, "newPassword", "newPassword123");

    // when
    ServiceException exception =
        catchThrowableOfType(
            () -> memberService.updatePassword(authUser, request), ServiceException.class);

    // then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_PROVIDER_LOCAL);

    verify(passwordEncoder, never()).matches(any(), any());
  }

  @Test
  void 기존_비밀번호가_틀리면_비밀번호_변경_실패() {
    // given
    when(authUser.getId()).thenReturn(1L);

    when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

    when(passwordEncoder.matches("wrongPassword", "encodedPassword")).thenReturn(false);

    MemberUpdatePasswordRequest request = new MemberUpdatePasswordRequest();

    ReflectionTestUtils.setField(request, "oldPassword", "wrongPassword");

    ReflectionTestUtils.setField(request, "newPassword", "newPassword123");

    // when
    ServiceException exception =
        catchThrowableOfType(
            () -> memberService.updatePassword(authUser, request), ServiceException.class);

    // then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.PASSWORD_NOT_MATCH);

    assertThat(member.getPassword()).isEqualTo("encodedPassword");

    verify(passwordEncoder).matches("wrongPassword", "encodedPassword");
  }

  // =========================
  // deleteMe()
  // =========================

  @Test
  void 회원탈퇴_성공() {
    // given
    when(authUser.getId()).thenReturn(1L);

    when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

    when(passwordEncoder.matches("password", "encodedPassword")).thenReturn(true);

    MemberDeleteRequest request = new MemberDeleteRequest();

    ReflectionTestUtils.setField(request, "password", "password");

    // when
    memberService.deleteMe(authUser, request);

    // then
    assertThat(member.getStatus()).isEqualTo(MemberStatus.DELETED);

    verify(memberRepository).findById(1L);

    verify(passwordEncoder).matches("password", "encodedPassword");
  }

  @Test
  void 회원탈퇴_회원이_없으면_예외() {
    // given
    when(authUser.getId()).thenReturn(1L);

    when(memberRepository.findById(1L)).thenReturn(Optional.empty());

    MemberDeleteRequest request = new MemberDeleteRequest();

    ReflectionTestUtils.setField(request, "password", "password");

    // when
    ServiceException exception =
        catchThrowableOfType(
            () -> memberService.deleteMe(authUser, request), ServiceException.class);

    // then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

    verify(memberRepository).findById(1L);

    verify(passwordEncoder, never()).matches(any(), any());
  }

  @Test
  void 비활성_회원은_회원탈퇴할_수_없다() {
    // given
    member.suspend();

    when(authUser.getId()).thenReturn(1L);

    when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

    MemberDeleteRequest request = new MemberDeleteRequest();

    ReflectionTestUtils.setField(request, "password", "password");

    // when
    ServiceException exception =
        catchThrowableOfType(
            () -> memberService.deleteMe(authUser, request), ServiceException.class);

    // then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_VALIDATE_ACTIVE);

    verify(passwordEncoder, never()).matches(any(), any());

    assertThat(member.getStatus()).isEqualTo(MemberStatus.SUSPENDED);
  }

  @Test
  void 비밀번호가_틀리면_회원탈퇴_실패() {
    // given
    when(authUser.getId()).thenReturn(1L);

    when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

    when(passwordEncoder.matches("wrongPassword", "encodedPassword")).thenReturn(false);

    MemberDeleteRequest request = new MemberDeleteRequest();

    ReflectionTestUtils.setField(request, "password", "wrongPassword");

    // when
    ServiceException exception =
        catchThrowableOfType(
            () -> memberService.deleteMe(authUser, request), ServiceException.class);

    // then
    assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.PASSWORD_NOT_MATCH);

    assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);

    verify(passwordEncoder).matches("wrongPassword", "encodedPassword");
  }
}
