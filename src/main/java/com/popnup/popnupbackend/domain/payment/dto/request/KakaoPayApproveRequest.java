package com.popnup.popnupbackend.domain.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KakaoPayApproveRequest {

  private String cid;

  private String tid;

  @JsonProperty("partner_order_id")
  private String partnerOrderId;

  @JsonProperty("partner_user_id")
  private String partnerUserId;

  @JsonProperty("pg_token")
  private String pgToken;
}
