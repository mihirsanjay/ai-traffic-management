package com.mihir.traffic.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PlatformConstants}.
 *
 * <p>These assert on the contract other services depend on. The header names in particular are load
 * bearing: changing {@code Idempotency-Key} silently would break retry safety across every caller,
 * and the failure would surface as duplicated deployments rather than as a compile error.
 */
class PlatformConstantsTest {

  @Test
  @DisplayName("idempotency header uses the conventional name callers expect")
  void idempotencyHeaderHasConventionalName() {
    assertThat(PlatformConstants.IDEMPOTENCY_KEY_HEADER).isEqualTo("Idempotency-Key");
  }

  @Test
  @DisplayName("trace header name is stable across service boundaries")
  void traceHeaderHasStableName() {
    assertThat(PlatformConstants.TRACE_ID_HEADER).isEqualTo("X-Trace-Id");
  }

  @Test
  @DisplayName("API prefix is versioned from the start")
  void apiPrefixIsVersioned() {
    assertThat(PlatformConstants.API_V1).isEqualTo("/api/v1").startsWith("/").doesNotEndWith("/");
  }

  @Test
  @DisplayName("constant holder cannot be instantiated")
  void cannotBeInstantiated() throws NoSuchMethodException {
    Constructor<PlatformConstants> constructor = PlatformConstants.class.getDeclaredConstructor();
    assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();

    constructor.setAccessible(true);
    assertThatThrownBy(constructor::newInstance)
        .isInstanceOf(InvocationTargetException.class)
        .hasRootCauseInstanceOf(AssertionError.class);
  }
}
