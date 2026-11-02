package com.popnup.popnupbackend.global.config;

import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.EqualJitterDelay;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class RedissonConfig {

  @Lazy
  @Bean(destroyMethod = "shutdown")
  public RedissonClient redissonClient(
      @Value("${spring.data.redis.host:localhost}") String host,
      @Value("${spring.data.redis.port:6379}") int port,
      @Value("${spring.data.redis.ssl.enabled:false}") boolean ssl,
      @Value("${redisson.timeout-ms:500}") int timeoutMs,
      @Value("${redisson.connect-timeout-ms:1000}") int connectTimeoutMs,
      @Value("${redisson.retry-attempts:1}") int retryAttempts,
      @Value("${redisson.retry-delay-ms:100}") int retryDelayMs,
      @Value("${redisson.connection-pool-size:32}") int poolSize,
      @Value("${redisson.connection-minimum-idle-size:8}") int minIdle,
      @Value("${redis.reconnect-delay-max-ms:1000}") long reconnectDelayMaxMs) {
    Config config = new Config();
    config.setCodec(StringCodec.INSTANCE);

    config
        .useSingleServer()
        .setAddress((ssl ? "rediss://" : "redis://") + host + ":" + port)
        .setTimeout(timeoutMs)
        .setConnectTimeout(connectTimeoutMs)
        .setRetryAttempts(retryAttempts)
        .setRetryInterval(retryDelayMs)
        .setConnectionPoolSize(poolSize)
        .setConnectionMinimumIdleSize(minIdle)
        .setReconnectionDelay(
            new EqualJitterDelay(
                Duration.ofMillis(Math.min(100, reconnectDelayMaxMs)),
                Duration.ofMillis(Math.max(1, reconnectDelayMaxMs))));
    return Redisson.create(config);
  }
}
