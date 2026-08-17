package com.mihir.traffic.ruleservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mihir.traffic.ruleservice.domain.Rule;
import com.mihir.traffic.ruleservice.domain.RuleError;
import com.mihir.traffic.ruleservice.domain.RuleOperationException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Round-trip and rejection behaviour of the pagination cursor. */
class PageCursorTest {

  private static final Instant CREATED_AT = Instant.parse("2026-08-16T12:00:00Z");

  @Test
  void encodedCursorRoundTripsToTheSamePosition() {
    Rule rule = Rule.create("orders", "/orders", CREATED_AT, "alice");

    PageCursor decoded = PageCursor.decode(PageCursor.encode(rule));

    assertThat(decoded.createdAt()).isEqualTo(CREATED_AT);
    assertThat(decoded.ruleId()).isEqualTo(rule.getRuleId());
  }

  @Test
  void encodedCursorIsOpaqueRatherThanReadablePlaintext() {
    Rule rule = Rule.create("orders", "/orders", CREATED_AT, "alice");

    // Not a security property - it signals "do not hand-construct this".
    assertThat(PageCursor.encode(rule)).doesNotContain(rule.getRuleId().toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-cursor", "", "!!!!", "YWJj", "MjAyNi0wOC0xNlQxMjowMDowMFo="})
  void undecodableCursorIsRejectedAsMalformed(String cursor) {
    assertThatThrownBy(() -> PageCursor.decode(cursor))
        .isInstanceOf(RuleOperationException.class)
        .extracting(e -> ((RuleOperationException) e).getError())
        .isInstanceOf(RuleError.MalformedCursor.class);
  }
}
