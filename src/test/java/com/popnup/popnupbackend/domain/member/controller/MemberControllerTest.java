package com.popnup.popnupbackend.domain.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.popnup.popnupbackend.domain.auth.dto.request.AuthUser;
import com.popnup.popnupbackend.domain.member.dto.request.MemberDeleteRequest;
import com.popnup.popnupbackend.domain.member.dto.request.MemberUpdatePasswordRequest;
import com.popnup.popnupbackend.domain.member.dto.response.MemberGetResponse;
import com.popnup.popnupbackend.domain.member.enums.Role;
import com.popnup.popnupbackend.domain.member.service.MemberService;
import com.popnup.popnupbackend.global.security.JwtBlacklistService;
import com.popnup.popnupbackend.global.security.JwtUtil;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MemberController.class)
@AutoConfigureMockMvc(addFilters = false)
class MemberControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private MemberService memberService;

  @MockitoBean private JwtUtil jwtUtil;

  @MockitoBean private JwtBlacklistService jwtBlacklistService;

  private AuthUser authUser;

  @BeforeEach
  void setUp() {
    authUser = new AuthUser(1L, "test@test.com", "테스트회원", Role.ROLE_USER);

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(authUser, null, Collections.emptyList());

    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("내 정보 조회 성공")
  void getMe_성공() throws Exception {

    // given
    MemberGetResponse response =
        new MemberGetResponse(1L, "test@test.com", "테스트회원", Role.ROLE_USER);

    when(memberService.getMe(1L)).thenReturn(response);

    // when & then
    mockMvc.perform(get("/members/me")).andExpect(status().isOk());

    verify(memberService).getMe(1L);
  }

  @Test
  @DisplayName("비밀번호 변경 성공")
  void updatePassword_성공() throws Exception {

    // given
    MemberUpdatePasswordRequest request = new MemberUpdatePasswordRequest();

    ReflectionTestUtils.setField(request, "oldPassword", "oldPassword");

    ReflectionTestUtils.setField(request, "newPassword", "newPassword123");

    // when & then
    mockMvc
        .perform(
            put("/members/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "oldPassword": "oldPassword",
                                      "newPassword": "newPassword123"
                                    }
                                    """))
        .andExpect(status().isOk());

    verify(memberService)
        .updatePassword(any(AuthUser.class), any(MemberUpdatePasswordRequest.class));
  }

  @Test
  @DisplayName("회원 탈퇴 성공")
  void deleteMe_성공() throws Exception {

    // given
    MemberDeleteRequest request = new MemberDeleteRequest();

    ReflectionTestUtils.setField(request, "password", "password");

    // when & then
    mockMvc
        .perform(
            delete("/members/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "password": "password"
                                    }
                                    """))
        .andExpect(status().isOk());

    verify(memberService).deleteMe(any(AuthUser.class), any(MemberDeleteRequest.class));
  }

  @Test
  @DisplayName("비밀번호 변경 요청에서 기존 비밀번호가 없으면 400")
  void updatePassword_기존비밀번호_없음_실패() throws Exception {

    mockMvc
        .perform(
            put("/members/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "oldPassword": "",
                                      "newPassword": "newPassword123"
                                    }
                                    """))
        .andExpect(status().isBadRequest());

    verify(memberService, never())
        .updatePassword(any(AuthUser.class), any(MemberUpdatePasswordRequest.class));
  }

  @Test
  @DisplayName("비밀번호 변경 요청에서 새 비밀번호가 8자 미만이면 400")
  void updatePassword_새비밀번호_길이_실패() throws Exception {

    mockMvc
        .perform(
            put("/members/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "oldPassword": "oldPassword",
                                      "newPassword": "1234"
                                    }
                                    """))
        .andExpect(status().isBadRequest());

    verify(memberService, never())
        .updatePassword(any(AuthUser.class), any(MemberUpdatePasswordRequest.class));
  }

  @Test
  @DisplayName("회원 탈퇴 요청에서 비밀번호가 없으면 400")
  void deleteMe_비밀번호_없음_실패() throws Exception {

    mockMvc
        .perform(
            delete("/members/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                    {
                                      "password": ""
                                    }
                                    """))
        .andExpect(status().isBadRequest());

    verify(memberService, never()).deleteMe(any(AuthUser.class), any(MemberDeleteRequest.class));
  }
}
