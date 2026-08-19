package com.mihir.traffic.ruleservice.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds and validates the optimistic-locking retry settings (ADR 0008) at startup, so an unbounded
 * or nonsensical retry budget fails the application rather than surfacing as a stall under
 * contention.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VersionRetryProperties.class)
public class VersionRetryConfig {}
