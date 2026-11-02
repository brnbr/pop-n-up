package com.popnup.popnupbackend.global.security;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class JwtBlacklistService {

  private final RedisTemplate<String, String> redisTemplate;

  private final LettuceConnectionFactory lookupConnectionFactory;
  private final StringRedisTemplate lookupTemplate;

  private final Timer lookupTimer;

  private static final String BLACKLIST_PREFIX = "blacklist:";

  @Autowired
  public JwtBlacklistService(
      RedisTemplate<String, String> redisTemplate,
      ObjectProvider<ClientResources> clientResources,
      MeterRegistry meterRegistry,
      @Value("${spring.data.redis.host:localhost}") String host,
      @Value("${spring.data.redis.port:6379}") int port,
      @Value("${spring.data.redis.username:}") String username,
      @Value("${spring.data.redis.password:}") String password,
      @Value("${spring.data.redis.database:0}") int database,
      @Value("${spring.data.redis.ssl.enabled:false}") boolean ssl,
      @Value("${jwt.blacklist.timeout-ms:400}") long timeoutMs) {
    this(
        redisTemplate,
        clientResources.getIfAvailable(),
        meterRegistry,
        host,
        port,
        username,
        password,
        database,
        ssl,
        timeoutMs);
  }

  JwtBlacklistService(
      RedisTemplate<String, String> redisTemplate,
      ClientResources clientResources,
      MeterRegistry meterRegistry,
      String host,
      int port,
      String username,
      String password,
      int database,
      boolean ssl,
      long timeoutMs) {
    this.redisTemplate = redisTemplate;

    RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
    server.setDatabase(database);
    if (!username.isBlank()) {
      server.setUsername(username);
    }
    if (!password.isBlank()) {
      server.setPassword(RedisPassword.of(password));
    }
    Duration timeout = Duration.ofMillis(Math.max(1, timeoutMs));
    LettuceClientConfiguration.LettuceClientConfigurationBuilder client =
        LettuceClientConfiguration.builder()
            .commandTimeout(timeout)
            .clientOptions(
                ClientOptions.builder()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                    .build());
    if (clientResources != null) {
      client.clientResources(clientResources);
    }
    if (ssl) {
      client.useSsl();
    }
    this.lookupConnectionFactory = new LettuceConnectionFactory(server, client.build());
    this.lookupConnectionFactory.afterPropertiesSet();
    this.lookupConnectionFactory.start();
    this.lookupTemplate = new StringRedisTemplate(lookupConnectionFactory);
    this.lookupTimer =
        Timer.builder("jwt.blacklist.lookup")
            .description("블랙리스트 조회 시간 (실패 포함)")
            .serviceLevelObjectives(
                Duration.ofMillis(10),
                Duration.ofMillis(25),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(150),
                Duration.ofMillis(200),
                Duration.ofMillis(300),
                Duration.ofMillis(500),
                Duration.ofMillis(1000),
                Duration.ofMillis(2000))
            .register(meterRegistry);
  }

  public void blacklist(String token, long expirationMillis) {
    String key = BLACKLIST_PREFIX + token;

    redisTemplate.opsForValue().set(key, "logout", Duration.ofMillis(expirationMillis));
  }

  public boolean isBlacklisted(String token) {
    String key = BLACKLIST_PREFIX + token;

    return Boolean.TRUE.equals(lookupTimer.record(() -> lookupTemplate.hasKey(key)));
  }

  @PreDestroy
  void close() {
    lookupConnectionFactory.destroy();
  }
}
