package com.popnup.popnupbackend.domain.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KakaoPayCancelRequest {

  private String cid;

  private String tid;

  @JsonProperty("cancel_amount")
  private Integer cancelAmount;

  @JsonProperty("cancel_tax_free_amount")
  private Integer cancelTaxFreeAmount;
}
