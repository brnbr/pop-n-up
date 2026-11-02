package com.popnup.popnupbackend.global.config;

import io.lettuce.core.resource.Delay;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LettuceReconnectConfig {

  @Bean
  ClientResourcesBuilderCustomizer reconnectDelayCap(
      @Value("${redis.reconnect-delay-max-ms:1000}") long maxDelayMs) {
    return builder ->
        builder.reconnectDelay(
            Delay.exponential(
                Duration.ZERO,
                Duration.ofMillis(Math.max(1, maxDelayMs)),
                2,
                TimeUnit.MILLISECONDS));
  }
}
