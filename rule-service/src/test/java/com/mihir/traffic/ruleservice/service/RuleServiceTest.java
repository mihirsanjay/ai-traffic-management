package com.mihir.traffic.ruleservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mihir.traffic.ruleservice.domain.Rule;
import com.mihir.traffic.ruleservice.domain.RuleError;
import com.mihir.traffic.ruleservice.domain.RuleOperationException;
import com.mihir.traffic.ruleservice.domain.RuleVersion;
import com.mihir.traffic.ruleservice.repository.RuleRepository;
import com.mihir.traffic.ruleservice.repository.RuleVersionRepository;
import com.mihir.traffic.ruleservice.web.dto.CreateRuleRequest;
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit coverage of rule lifecycle logic, with persistence stubbed. */
@ExtendWith(MockitoExtension.class)
class RuleServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

  @Mock private RuleRepository ruleRepository;
  @Mock private RuleVersionRepository ruleVersionRepository;

  private RuleService ruleService;

  @BeforeEach
  void setUp() {
    // Fixed clock: assertions on timestamps must not depend on when the suite runs.
    ruleService =
        new RuleService(ruleRepository, ruleVersionRepository, Clock.fixed(NOW, ZoneOffset.UTC));
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

    assertThatThrownBy(() -> ruleService.softDelete(missing))
        .isInstanceOf(RuleOperationException.class)
        .extracting(e -> ((RuleOperationException) e).getError())
        .isEqualTo(new RuleError.RuleNotFound(missing));
  }

  @Test
  void softDeleteMarksTheRuleRatherThanRemovingIt() {
    Rule rule = Rule.create("orders", "/orders", NOW, "alice");
    when(ruleRepository.findLiveById(rule.getRuleId())).thenReturn(Optional.of(rule));

    ruleService.softDelete(rule.getRuleId());

    assertThat(rule.isDeleted()).isTrue();
    assertThat(rule.getDeletedAt()).isEqualTo(NOW);
    verify(ruleRepository).save(rule);
    verify(ruleRepository, never()).delete(any(Rule.class));
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
}
