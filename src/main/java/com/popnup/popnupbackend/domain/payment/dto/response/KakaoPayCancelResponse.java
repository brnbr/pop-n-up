package com.popnup.popnupbackend.domain.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class KakaoPayCancelResponse {

  private String aid;

  private String tid;

  private String cid;

  private String status;

  @JsonProperty("canceled_at")
  private String canceledAt;
}
