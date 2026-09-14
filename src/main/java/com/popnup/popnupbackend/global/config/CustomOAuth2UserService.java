package com.popnup.popnupbackend.global.config;

import com.popnup.popnupbackend.domain.member.entity.Member;
import com.popnup.popnupbackend.domain.member.repository.MemberRepository;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

  private final MemberRepository memberRepository;

  @Override
  @Transactional
  public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {

    // 카카오 사용자 정보 가져오기
    OAuth2User oAuth2User = super.loadUser(request);

    Map<String, Object> attributes = oAuth2User.getAttributes();

    String providerId = String.valueOf(attributes.get("id"));

    // 카카오가 제공하는 계정 관련 사용자 정보 묶음
    Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");

    String email = (String) kakaoAccount.get("email");

    Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");

    String nickname = (String) profile.get("nickname");

    Optional<Member> optionalMember = memberRepository.findByEmail(email);
    Member member;
    if (optionalMember.isPresent()) {
      // 기존 회원이면 Kakao 회원으로 전환
      member = optionalMember.get();
      member.validateActive();
      member.updateOAuth2(providerId);

    } else {
      // 기존 회원이 없으면 Kakao 회원으로 가입
      member = Member.createOAuth2(email, nickname, providerId);
      memberRepository.save(member);
    }

    return new CustomOAuth2User(member, attributes);
  }
}
