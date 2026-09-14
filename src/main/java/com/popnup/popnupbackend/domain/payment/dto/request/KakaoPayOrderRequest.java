package com.popnup.popnupbackend.domain.payment.dto.request;

import lombok.Getter;

@Getter
public class KakaoPayOrderRequest {

  private Long reservationId;
  private String itemName;
  private Integer quantity;
  private Integer totalPrice;
}
