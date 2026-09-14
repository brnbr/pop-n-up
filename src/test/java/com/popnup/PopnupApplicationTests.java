package com.popnup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "jwt.secret=as6rrc0S88l74sg6xAaJkG+QbcFUdqm+cI7B9E8hWI8=")
class PopnupApplicationTests {

  @Test
  void contextLoads() {}
}
