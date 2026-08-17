package com.mihir.traffic.ruleservice.service;

import com.mihir.traffic.ruleservice.domain.Rule;
import com.mihir.traffic.ruleservice.domain.RuleError;
import com.mihir.traffic.ruleservice.domain.RuleOperationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque pagination cursor encoding the {@code (createdAt, ruleId)} position of the last row on a
 * page.
 *
 * <p>Both components are needed: creation timestamps are not unique, and ordering by a non-unique
 * key is exactly how cursor pagination silently skips or repeats rows.
 *
 * <p>The Base64 encoding signals "opaque, do not construct by hand" to callers. It is not a
 * security boundary - the contents are ordinary rule metadata, and nothing is authorized based on
 * them.
 *
 * @param createdAt creation timestamp of the last row returned
 * @param ruleId identity of the last row returned
 */
public record PageCursor(Instant createdAt, UUID ruleId) {

  private static final String SEPARATOR = "|";

  /**
   * Encodes the position just after the given rule.
   *
   * @param rule the last rule on the page
   * @return the cursor to fetch the following page
   */
  public static String encode(Rule rule) {
    String raw = rule.getCreatedAt().toString() + SEPARATOR + rule.getRuleId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decodes a cursor supplied by a caller.
   *
   * @param cursor the opaque cursor string
   * @return the decoded position
   * @throws RuleOperationException carrying {@link RuleError.MalformedCursor} if the cursor is not
   *     one this service issued
   */
  public static PageCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int separator = raw.lastIndexOf(SEPARATOR);
      if (separator < 0) {
        throw new RuleOperationException(new RuleError.MalformedCursor(cursor));
      }
      return new PageCursor(
          Instant.parse(raw.substring(0, separator)),
          UUID.fromString(raw.substring(separator + 1)));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      // A client-supplied cursor being unparseable is a 400, not a server fault;
      // the cause is deliberately not surfaced to the caller.
      throw new RuleOperationException(new RuleError.MalformedCursor(cursor));
    }
  }
}
