package com.popnup.popnupbackend.domain.schedule.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnExpression("${outbox.enabled:true} and '${outbox.mode:polling}' == 'cdc'")
public class OutboxCdcConfig {

  @Bean
  public CommonErrorHandler seatReleaseErrorHandler() {
    DefaultErrorHandler handler =
        new DefaultErrorHandler(new FixedBackOff(500L, FixedBackOff.UNLIMITED_ATTEMPTS));
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
    return handler;
  }
}
