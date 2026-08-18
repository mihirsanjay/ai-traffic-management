package com.mihir.traffic.ruleservice.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Proves Bucket4j works against the Lettuce version Spring Boot manages.
 *
 * <p>Bucket4j 8.14.0 declares lettuce-core 6.1.8; Spring Boot 4 manages 7.5.2. The parent POM
 * excludes Bucket4j's copy so a single version is on the classpath, which satisfies the Enforcer's
 * dependencyConvergence rule — but that only proves one version <em>loads</em>, not that Bucket4j's
 * calls still exist in it. A method removed between Lettuce majors would surface as a
 * NoSuchMethodError at runtime, which is exactly the failure that rule warns about and cannot
 * itself detect.
 *
 * <p>This test is the evidence for that claim. It is deliberately narrow: it exercises the
 * Bucket4j-to-Lettuce seam and nothing about this service's own rate limiting, so a failure here
 * means the library combination is wrong rather than the filter being wrong.
 */
class Bucket4jLettuceCompatibilityTest {

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

  private static RedisClient client;

  @BeforeAll
  static void startRedis() {
    REDIS.start();
    // The mapped port is random, so this can only reach the container - never
    // a Redis that happens to be running locally on 6379. A test that silently
    // used the developer's own Redis would pass on their machine and fail in
    // CI, which is the failure mode that hid a broken datasource earlier in
    // this phase.
    client =
        RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0");
  }

  @AfterAll
  static void stopRedis() {
    if (client != null) {
      client.shutdown();
    }
    REDIS.stop();
  }

  @Test
  void bucket4jConsumesTokensThroughLettuce() {
    // Constructing the ProxyManager is the actual compatibility check: it is
    // where Bucket4j touches RedisClient and StatefulRedisConnection, the
    // types that would have moved or changed across the Lettuce major bump.
    ProxyManager<byte[]> proxyManager =
        Bucket4jLettuce.casBasedBuilder(client)
            .expirationAfterWrite(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofSeconds(10)))
            .build();

    Supplier<BucketConfiguration> configuration =
        () ->
            BucketConfiguration.builder()
                .addLimit(
                    Bandwidth.builder().capacity(2).refillGreedy(2, Duration.ofMinutes(1)).build())
                .build();

    BucketProxy bucket =
        proxyManager.builder().build("compatibility-probe".getBytes(), configuration);

    // A capacity of two: the first two requests are allowed, the third is not.
    // Asserting the refusal matters as much as the allowance - a limiter that
    // never says no would pass a test that only checked the happy path.
    assertThat(bucket.tryConsume(1)).isTrue();
    assertThat(bucket.tryConsume(1)).isTrue();
    assertThat(bucket.tryConsume(1)).isFalse();
  }
}
