package com.popnup.popnupbackend.domain.reservation.queue;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class QueueJoinRequest {

  @Min(1)
  @Max(10)
  private Integer personCount = 1;

  public QueueJoinRequest(Integer personCount) {
    this.personCount = personCount;
  }
}
