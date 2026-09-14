package com.popnup.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

@Tag("concurrency")
@SpringBootTest
@ActiveProfiles("test")
public abstract class ConcurrencyTestSupport {

  @Container @ServiceConnection
  static final MySQLContainer<?> MYSQL_CONTAINER =
      new MySQLContainer<>("mysql:8.0")
          .withDatabaseName("popnup_test")
          .withUsername("test")
          .withPassword("test");

  static {
    MYSQL_CONTAINER.start();
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
    registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    registry.add("spring.jpa.show-sql", () -> "false");
    registry.add("jwt.secret", () -> "as6rrc0S88l74sg6xAaJkG+QbcFUdqm+cI7B9E8hWI8=");
  }
}
