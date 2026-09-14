package com.popnup.popnupbackend.domain.popup.dto.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverGeocodeResponse {

  @JsonProperty("status")
  private String status;

  @JsonProperty("addresses")
  private List<AddressItem> addresses;

  @Getter
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class AddressItem {
    @JsonProperty("roadAddress")
    private String roadAddress;

    @JsonProperty("jibunAddress")
    private String jibunAddress;

    @JsonProperty("x") // 경도
    private BigDecimal longitude;

    @JsonProperty("y") // 위도
    private BigDecimal latitude;
  }
}
