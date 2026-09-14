package com.popnup.popnupbackend.domain.payment.dto.response;

import lombok.Getter;

@Getter
public class KakaoPayReadyResponse {
  // 카카오페이 서버로부터 결제 고유번호와 카카오톡 졀제 요청 메시지를 보내기 위한 사용자 정보 입력 화면 redirect URL
  // jackson이 카카오페이의 json을 받아서 객체를 만들 때 값을 넣어줘야하기 때문에 private final X
  private String tid;
  private String next_redirect_pc_url;
}
