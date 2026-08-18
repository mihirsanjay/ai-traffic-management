package com.mihir.traffic.ruleservice.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import java.util.function.Supplier;
// Spring Boot 4 moved Redis auto-configuration into its own spring-boot-data-redis
// artifact and renamed RedisProperties to DataRedisProperties; the Boot 3
// coordinates no longer resolve.
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Bucket4j to Redis so rate-limit counters are shared across instances.
 *
 * <p>The counters deliberately live in Redis rather than in memory. An in-memory bucket per
 * instance would let a caller spend the full limit against each one, so N instances would serve N
 * times the configured limit - a limiter that looks correct on a single instance and silently is
 * not in production.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

  /**
   * The Lettuce client pointed at the configured Redis.
   *
   * <p>Built here rather than reusing Spring Data Redis's connection factory because Bucket4j
   * speaks to Lettuce's own API directly.
   *
   * @param redisProperties Spring Boot's Redis connection settings
   * @param rateLimitProperties this service's rate-limit settings, for the command timeout
   * @return the client, shut down with the context
   */
  @Bean(destroyMethod = "shutdown")
  public RedisClient rateLimitRedisClient(
      DataRedisProperties redisProperties, RateLimitProperties rateLimitProperties) {
    RedisURI.Builder uri =
        RedisURI.builder()
            .withHost(redisProperties.getHost())
            .withPort(redisProperties.getPort())
            // An explicit timeout is mandatory here: without one Lettuce waits
            // indefinitely, and a stalled Redis would hang every request to the
            // API this limiter is supposed to protect.
            .withTimeout(rateLimitProperties.redisTimeout());
    // Local development and tests run an unauthenticated Redis; a deployed one
    // will not, so the password is applied when configured rather than assumed
    // absent.
    if (redisProperties.getPassword() != null) {
      uri.withPassword((CharSequence) redisProperties.getPassword());
    }
    return RedisClient.create(uri.build());
  }

  /**
   * The Bucket4j proxy manager backing every caller's bucket.
   *
   * @param client the Redis client
   * @param properties rate-limit settings, for the expiry window
   * @return a proxy manager keyed by caller identity
   */
  @Bean
  public ProxyManager<byte[]> rateLimitProxyManager(
      RedisClient client, RateLimitProperties properties) {
    return Bucket4jLettuce.casBasedBuilder(client)
        // Idle buckets expire rather than accumulating one Redis key per caller
        // forever. The window is tied to the refill period, so a key only
        // outlives the bucket's own recovery to full.
        .expirationAfterWrite(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                properties.refillPeriod()))
        .build();
  }

  /**
   * The bucket shape applied to each caller.
   *
   * <p>A supplier rather than a value: Bucket4j resolves it only when a caller's bucket is first
   * created in Redis.
   *
   * @param properties rate-limit settings
   * @return supplier of the per-caller bucket configuration
   */
  @Bean
  public Supplier<BucketConfiguration> bucketConfiguration(RateLimitProperties properties) {
    Duration refillPeriod = properties.refillPeriod();
    int capacity = properties.capacity();
    return () ->
        BucketConfiguration.builder()
            // Greedy refill spreads tokens smoothly across the window instead
            // of releasing the whole capacity at once, which would let a caller
            // stampede the instant each window rolled over.
            .addLimit(
                io.github.bucket4j.Bandwidth.builder()
                    .capacity(capacity)
                    .refillGreedy(capacity, refillPeriod)
                    .build())
            .build();
  }
}
