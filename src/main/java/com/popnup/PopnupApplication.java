package com.popnup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PopnupApplication {

  public static void main(String[] args) {
    SpringApplication.run(PopnupApplication.class, args);
  }
}
