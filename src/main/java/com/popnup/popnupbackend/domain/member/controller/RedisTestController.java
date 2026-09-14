package com.popnup.popnupbackend.domain.member.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/redis")
public class RedisTestController {

  private final RedisTemplate<String, String> redisTemplate;

  @GetMapping("/test")
  public String test() {
    redisTemplate.opsForValue().set("popnup", "hello redis");

    return redisTemplate.opsForValue().get("popnup");
  }
}
