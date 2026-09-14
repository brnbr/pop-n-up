package com.popnup.popnupbackend.global.crawler;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CrawledPopupDto {
  private String title;
  private String location;
  private String description;
  private LocalDate startDate;
  private LocalDate endDate;
  private String imageUrl;
}
