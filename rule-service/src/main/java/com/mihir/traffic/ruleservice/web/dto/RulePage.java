package com.mihir.traffic.ruleservice.web.dto;

import java.util.List;

/**
 * One page of rules, cursor-paginated.
 *
 * <p>Cursor rather than offset: offset pagination skips or repeats rows when the underlying data
 * changes between pages, and degrades as the offset grows. {@code nextCursor} is null on the last
 * page.
 *
 * @param items the rules in this page
 * @param nextCursor opaque cursor to fetch the following page, or null if this is the last page
 */
public record RulePage(List<RuleResponse> items, String nextCursor) {

  /** Canonical constructor defending the list against later mutation by the caller. */
  public RulePage {
    items = List.copyOf(items);
  }
}
