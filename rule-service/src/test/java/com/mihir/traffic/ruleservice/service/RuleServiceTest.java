package com.mihir.traffic.ruleservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mihir.traffic.common.event.EventType;
import com.mihir.traffic.ruleservice.domain.Rule;
import com.mihir.traffic.ruleservice.domain.RuleError;
import com.mihir.traffic.ruleservice.domain.RuleOperationException;
import com.mihir.traffic.ruleservice.domain.RuleVersion;
import com.mihir.traffic.ruleservice.domain.RuleVersionId;
import com.mihir.traffic.ruleservice.outbox.OutboxWriter;
import com.mihir.traffic.ruleservice.repository.RuleRepository;
import com.mihir.traffic.ruleservice.repository.RuleVersionRepository;
import com.mihir.traffic.ruleservice.web.dto.CreateRuleRequest;
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
import com.mihir.traffic.ruleservice.web.dto.RuleVersionResponse;
import com.mihir.traffic.ruleservice.web.dto.UpdateRuleRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

/** Unit coverage of rule lifecycle logic, with persistence stubbed. */
@ExtendWith(MockitoExtension.class)
class RuleServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

  @Mock private RuleRepository ruleRepository;
  @Mock private RuleVersionRepository ruleVersionRepository;

  // Mocked rather than real: this class tests version-increment logic, and the
  // outbox has its own tests. What matters here is only that the write is
  // attempted, which the verifications below assert.
  @Mock private OutboxWriter outboxWriter;

  private RuleService ruleService;

  @BeforeEach
  void setUp() {
    // Fixed clock: assertions on timestamps must not depend on when the suite runs.
    // The retrier is real rather than mocked - it is pure logic over the
    // supplied operation, and stubbing it would hide whether update() actually
    // routes through it.
    VersionRetryProperties retryProperties =
        new VersionRetryProperties(
            3, Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofSeconds(1), 0.0);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    // The appender is real too: it is the version-increment logic under test,
    // and the transaction it would normally run in is a Spring concern proven
    // by RuleVersionConcurrencyIntegrationTest, not by a mock.
    ruleService =
        new RuleService(
            ruleRepository,
            ruleVersionRepository,
            new RuleVersionAppender(ruleRepository, ruleVersionRepository, outboxWriter, clock),
            new VersionConflictRetrier(retryProperties),
            outboxWriter,
            clock);
  }

  /** A rule already advanced to the supplied version, as loaded from the database would be. */
  private static Rule ruleAtVersion(int version) {
    Rule rule = Rule.create("orders", "/orders", NOW, "alice");
    for (int v = RuleVersion.FIRST_VERSION; v < version; v++) {
      rule.applyVersion(v + 1);
    }
    return rule;
  }

  @Test
  void createdRuleStartsAtVersionOne() {
    when(ruleRepository.existsLiveByServiceAndEndpoint("orders", "/orders")).thenReturn(false);

    RuleResponse response =
        ruleService.create(new CreateRuleRequest("orders", "/orders", 1000, "1m"), "alice");

    assertThat(response.currentVersion()).isEqualTo(1);
    assertThat(response.limit()).isEqualTo(1000);
    assertThat(response.createdAt()).isEqualTo(NOW);
    assertThat(response.createdBy()).isEqualTo("alice");
  }

  @Test
  void createWritesRuleAndItsFirstVersionTogether() {
    when(ruleRepository.existsLiveByServiceAndEndpoint(anyString(), anyString())).thenReturn(false);

    ruleService.create(new CreateRuleRequest("orders", "/orders", 500, "30s"), "alice");

    ArgumentCaptor<RuleVersion> version = ArgumentCaptor.forClass(RuleVersion.class);
    verify(ruleRepository).saveAndFlush(any(Rule.class));
    verify(ruleVersionRepository).saveAndFlush(version.capture());

    assertThat(version.getValue().getVersion()).isEqualTo(1);
    assertThat(version.getValue().getLimitValue()).isEqualTo(500);
    assertThat(version.getValue().getWindowSpec()).isEqualTo("30s");
  }

  @Test
  void creatingRuleForOccupiedEndpointIsRejectedBeforeAnyWrite() {
    when(ruleRepository.existsLiveByServiceAndEndpoint("orders", "/orders")).thenReturn(true);

    assertThatThrownBy(
            () -> ruleService.create(new CreateRuleRequest("orders", "/orders", 10, "1m"), "alice"))
        .isInstanceOf(RuleOperationException.class)
        .extracting(e -> ((RuleOperationException) e).getError())
        .isInstanceOf(RuleError.DuplicateRule.class);

    verify(ruleRepository, never()).saveAndFlush(any(Rule.class));
    verify(ruleVersionRepository, never()).saveAndFlush(any(RuleVersion.class));
  }

  @Test
  void retrievingUnknownRuleYieldsRuleNotFound() {
    UUID missing = UUID.randomUUID();
    when(ruleRepository.findLiveById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> ruleService.get(missing))
        .isInstanceOf(RuleOperationException.class)
        .extracting(e -> ((RuleOperationException) e).getError())
        .isEqualTo(new RuleError.RuleNotFound(missing));
  }

  @Test
  void deletingUnknownRuleYieldsRuleNotFound() {
    UUID missing = UUID.randomUUID();
    when(ruleRepository.findLiveById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> ruleService.softDelete(missing, "bob"))
        .isInstanceOf(RuleOperationException.class)
        .extracting(e -> ((RuleOperationException) e).getError())
        .isEqualTo(new RuleError.RuleNotFound(missing));
  }

  @Test
  void softDeleteMarksTheRuleRatherThanRemovingIt() {
    Rule rule = Rule.create("orders", "/orders", NOW, "alice");
    when(ruleRepository.findLiveById(rule.getRuleId())).thenReturn(Optional.of(rule));
    // Deletion reads the live version so the RULE_DELETED event can report what
    // the rule was targeting when it died.
    when(ruleVersionRepository.findById(new RuleVersionId(rule.getRuleId(), 1)))
        .thenReturn(Optional.of(RuleVersion.first(rule.getRuleId(), 1000, "1m", NOW, "alice")));

    ruleService.softDelete(rule.getRuleId(), "bob");

    assertThat(rule.isDeleted()).isTrue();
    assertThat(rule.getDeletedAt()).isEqualTo(NOW);
    // The deleter is recorded, not just the instant: RULE_DELETED carries a
    // changedBy, and there would otherwise be nothing truthful to put in it.
    assertThat(rule.getDeletedBy()).isEqualTo("bob");
    verify(ruleRepository).save(rule);
    verify(ruleRepository, never()).delete(any(Rule.class));
    // The deletion is announced in the same transaction as the tombstone.
    verify(outboxWriter).writeRuleEvent(eq(EventType.RULE_DELETED), any(), any(), any());
  }

  @Test
  void listWithoutSizeUsesTheDefaultPageSize() {
    when(ruleRepository.findFirstLivePage(any())).thenReturn(java.util.List.of());

    ruleService.list(null, null);

    ArgumentCaptor<org.springframework.data.domain.Limit> limit =
        ArgumentCaptor.forClass(org.springframework.data.domain.Limit.class);
    verify(ruleRepository).findFirstLivePage(limit.capture());

    // One more than the page size, so "is there another page" needs no count query.
    assertThat(limit.getValue().max()).isEqualTo(RuleService.DEFAULT_PAGE_SIZE + 1);
  }

  @Test
  void listClampsOversizedPageRequestToTheMaximum() {
    when(ruleRepository.findFirstLivePage(any())).thenReturn(java.util.List.of());

    ruleService.list(null, 10_000);

    ArgumentCaptor<org.springframework.data.domain.Limit> limit =
        ArgumentCaptor.forClass(org.springframework.data.domain.Limit.class);
    verify(ruleRepository).findFirstLivePage(limit.capture());

    assertThat(limit.getValue().max()).isEqualTo(RuleService.MAX_PAGE_SIZE + 1);
  }

  @Test
  void updateAppendsTheNextVersionAndMovesThePointer() {
    Rule rule = ruleAtVersion(1);
    RuleVersion current = RuleVersion.first(rule.getRuleId(), 1000, "1m", NOW, "alice");
    when(ruleRepository.findLiveById(rule.getRuleId())).thenReturn(Optional.of(rule));
    when(ruleVersionRepository.findById(new RuleVersionId(rule.getRuleId(), 1)))
        .thenReturn(Optional.of(current));

    RuleResponse response =
        ruleService.update(rule.getRuleId(), new UpdateRuleRequest(500, "30s"), "bob");

    assertThat(response.currentVersion()).isEqualTo(2);
    assertThat(response.limit()).isEqualTo(500);
    assertThat(response.window()).isEqualTo("30s");
    assertThat(rule.getCurrentVersion()).isEqualTo(2);
  }

  @Test
  void updateWritesANewVersionRowRatherThanMutatingTheCurrentOne() {
    Rule rule = ruleAtVersion(1);
    RuleVersion current = RuleVersion.first(rule.getRuleId(), 1000, "1m", NOW, "alice");
    when(ruleRepository.findLiveById(rule.getRuleId())).thenReturn(Optional.of(rule));
    when(ruleVersionRepository.findById(new RuleVersionId(rule.getRuleId(), 1)))
        .thenReturn(Optional.of(current));

    ruleService.update(rule.getRuleId(), new UpdateRuleRequest(500, "30s"), "bob");

    ArgumentCaptor<RuleVersion> saved = ArgumentCaptor.forClass(RuleVersion.class);
    verify(ruleVersionRepository).saveAndFlush(saved.capture());

    // The appended row is version 2 - and, crucially, the version 1 object is
    // untouched. ADR 0007 exists so a deployment referencing version 1 keeps
    // referencing the same configuration forever.
    assertThat(saved.getValue().getVersion()).isEqualTo(2);
    assertThat(saved.getValue().getLimitValue()).isEqualTo(500);
    assertThat(saved.getValue().getCreatedBy()).isEqualTo("bob");
    assertThat(current.getLimitValue()).isEqualTo(1000);
    assertThat(current.getWindowSpec()).isEqualTo("1m");
  }

  @Test
  void updateRecordsTheAuthorOfThatVersionWithoutRewritingTheRuleAuthor() {
    Rule rule = ruleAtVersion(1);
    RuleVersion current = RuleVersion.first(rule.getRuleId(), 1000, "1m", NOW, "alice");
    when(ruleRepository.findLiveById(rule.getRuleId())).thenReturn(Optional.of(rule));
    when(ruleVersionRepository.findById(new RuleVersionId(rule.getRuleId(), 1)))
        .thenReturn(Optional.of(current));

    RuleResponse response =
        ruleService.update(rule.getRuleId(), new UpdateRuleRequest(500, "30s"), "bob");

    // "Who created this rule" and "who made this change" are different
    // questions; the audit service in Phase 2 needs both to stay answerable.
    assertThat(response.createdBy()).isEqualTo("alice");
    assertThat(rule.getCreatedBy()).isEqualTo("alice");
  }

  @Test
  void updatingUnknownRuleYieldsRuleNotFound() {
    UUID missing = UUID.randomUUID();
    when(ruleRepository.findLiveById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> ruleService.update(missing, new UpdateRuleRequest(5, "1m"), "bob"))
        .isInstanceOf(RuleOperationException.class)
        .extracting(e -> ((RuleOperationException) e).getError())
        .isEqualTo(new RuleError.RuleNotFound(missing));

    verify(ruleVersionRepository, never()).saveAndFlush(any(RuleVersion.class));
  }

  @Test
  void updateThatKeepsLosingTheRaceYieldsVersionConflictNotAnInternalError() {
    Rule rule = ruleAtVersion(1);
    RuleVersion current = RuleVersion.first(rule.getRuleId(), 1000, "1m", NOW, "alice");
    when(ruleRepository.findLiveById(rule.getRuleId())).thenReturn(Optional.of(rule));
    when(ruleVersionRepository.findById(new RuleVersionId(rule.getRuleId(), 1)))
        .thenReturn(Optional.of(current));
    // Every attempt loses, so the retry budget runs out.
    when(ruleVersionRepository.saveAndFlush(any(RuleVersion.class)))
        .thenThrow(new OptimisticLockingFailureException("lost the race"));

    assertThatThrownBy(
            () -> ruleService.update(rule.getRuleId(), new UpdateRuleRequest(500, "30s"), "bob"))
        .isInstanceOf(RuleOperationException.class)
        .extracting(e -> ((RuleOperationException) e).getError())
        .isEqualTo(new RuleError.VersionConflict(rule.getRuleId(), 3));
  }

  @Test
  void updateRetriesAfterLosingOnceAndThenSucceeds() {
    Rule rule = ruleAtVersion(1);
    RuleVersion current = RuleVersion.first(rule.getRuleId(), 1000, "1m", NOW, "alice");
    when(ruleRepository.findLiveById(rule.getRuleId())).thenReturn(Optional.of(rule));
    when(ruleVersionRepository.findById(new RuleVersionId(rule.getRuleId(), 1)))
        .thenReturn(Optional.of(current));
    when(ruleVersionRepository.saveAndFlush(any(RuleVersion.class)))
        .thenThrow(new OptimisticLockingFailureException("lost the race"))
        .thenAnswer(invocation -> invocation.getArgument(0));

    RuleResponse response =
        ruleService.update(rule.getRuleId(), new UpdateRuleRequest(500, "30s"), "bob");

    // Contention is observed as latency, never as an error, while budget remains.
    assertThat(response.currentVersion()).isEqualTo(2);
    verify(ruleVersionRepository, times(2)).saveAndFlush(any(RuleVersion.class));
  }

  @Test
  void versionPointerMayOnlyEverAdvanceByOne() {
    Rule rule = ruleAtVersion(1);

    // A stale read computing the wrong next number must fail loudly rather
    // than leaving the pointer aimed at a version that was never written.
    assertThatThrownBy(() -> rule.applyVersion(5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rule.applyVersion(1)).isInstanceOf(IllegalArgumentException.class);
    assertThat(rule.getCurrentVersion()).isEqualTo(1);
  }

  @Test
  void versionHistoryIsReturnedNewestFirst() {
    Rule rule = ruleAtVersion(2);
    RuleVersion v1 = RuleVersion.first(rule.getRuleId(), 1000, "1m", NOW, "alice");
    RuleVersion v2 = v1.next(500, "30s", NOW, "bob");
    when(ruleRepository.existsById(rule.getRuleId())).thenReturn(true);
    when(ruleVersionRepository.findHistory(rule.getRuleId())).thenReturn(List.of(v2, v1));

    List<RuleVersionResponse> history = ruleService.listVersions(rule.getRuleId());

    assertThat(history).extracting(RuleVersionResponse::version).containsExactly(2, 1);
    assertThat(history).extracting(RuleVersionResponse::limit).containsExactly(500, 1000);
  }

  @Test
  void versionHistoryOfUnknownRuleYieldsRuleNotFoundRatherThanAnEmptyList() {
    UUID missing = UUID.randomUUID();
    when(ruleRepository.existsById(missing)).thenReturn(false);

    assertThatThrownBy(() -> ruleService.listVersions(missing))
        .isInstanceOf(RuleOperationException.class)
        .extracting(e -> ((RuleOperationException) e).getError())
        .isEqualTo(new RuleError.RuleNotFound(missing));
  }

  @Test
  void requestingAVersionThatWasNeverWrittenYieldsRuleNotFound() {
    UUID ruleId = UUID.randomUUID();
    when(ruleRepository.existsById(ruleId)).thenReturn(true);
    when(ruleVersionRepository.findById(new RuleVersionId(ruleId, 99)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> ruleService.getVersion(ruleId, 99))
        .isInstanceOf(RuleOperationException.class)
        .extracting(e -> ((RuleOperationException) e).getError())
        .isEqualTo(new RuleError.RuleNotFound(ruleId));
  }
}
