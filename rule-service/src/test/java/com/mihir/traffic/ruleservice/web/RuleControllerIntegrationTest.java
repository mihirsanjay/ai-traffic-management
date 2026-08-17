package com.mihir.traffic.ruleservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mihir.traffic.ruleservice.AbstractIntegrationTest;
import com.mihir.traffic.ruleservice.domain.RuleVersion;
import com.mihir.traffic.ruleservice.repository.RuleRepository;
import com.mihir.traffic.ruleservice.repository.RuleVersionRepository;
import com.mihir.traffic.ruleservice.web.dto.CreateRuleRequest;
import com.mihir.traffic.ruleservice.web.dto.RulePage;
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** End-to-end coverage of every rule endpoint against a real PostgreSQL. */
class RuleControllerIntegrationTest extends AbstractIntegrationTest {

  private static final String RULES = "/api/v1/rules";

  @Autowired private RuleRepository ruleRepository;
  @Autowired private RuleVersionRepository ruleVersionRepository;

  @BeforeEach
  void clearRules() {
    // The container is shared across classes, so each test starts from a known
    // empty state rather than inheriting rows from an earlier test.
    ruleVersionRepository.deleteAll();
    ruleRepository.deleteAll();
  }

  @Test
  void createdRuleIsRetrievableAtVersionOne() {
    RuleResponse created = createRule("orders", "/orders", 1000, "1m");

    assertThat(created.id()).isNotNull();
    assertThat(created.currentVersion()).isEqualTo(1);
    assertThat(created.limit()).isEqualTo(1000);
    assertThat(created.window()).isEqualTo("1m");

    ResponseEntity<RuleResponse> fetched =
        restTemplate().getForEntity(url(RULES + "/" + created.id()), RuleResponse.class);

    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(fetched.getBody()).isNotNull();
    assertThat(fetched.getBody().id()).isEqualTo(created.id());
    assertThat(fetched.getBody().service()).isEqualTo("orders");
  }

  @Test
  void createReturnsLocationHeaderPointingAtTheNewRule() {
    ResponseEntity<RuleResponse> response =
        restTemplate()
            .postForEntity(
                url(RULES),
                new CreateRuleRequest("payments", "/payments", 50, "30s"),
                RuleResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getHeaders().getLocation())
        .hasToString(RULES + "/" + response.getBody().id());
  }

  @Test
  void createStoresAnImmutableFirstVersionRow() {
    RuleResponse created = createRule("inventory", "/inventory", 25, "1s");

    List<RuleVersion> history = ruleVersionRepository.findHistory(created.id());

    assertThat(history).hasSize(1);
    assertThat(history.get(0).getVersion()).isEqualTo(1);
    assertThat(history.get(0).getLimitValue()).isEqualTo(25);
    assertThat(history.get(0).getWindowSpec()).isEqualTo("1s");
  }

  @Test
  void creatingRuleWithDuplicateEndpointIsRejected() {
    createRule("orders", "/orders", 1000, "1m");

    ResponseEntity<ProblemDetail> response =
        restTemplate()
            .postForEntity(
                url(RULES),
                new CreateRuleRequest("orders", "/orders", 2000, "1m"),
                ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getTitle()).isEqualTo("Rule already exists");
    assertThat(response.getBody().getType())
        .hasToString("https://errors.mihir.traffic/rule-service/duplicate-rule");
  }

  @Test
  void retrievingUnknownRuleReturnsProblemDetail() {
    ResponseEntity<ProblemDetail> response =
        restTemplate().getForEntity(url(RULES + "/" + UUID.randomUUID()), ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getTitle()).isEqualTo("Rule not found");
    assertThat(response.getBody().getProperties()).containsKey("correlationId");
  }

  @Test
  void invalidPayloadIsRejectedWithoutCreatingARule() {
    // Negative limit, empty service, and an endpoint that is not a path.
    ResponseEntity<ProblemDetail> response =
        restTemplate()
            .postForEntity(
                url(RULES), new CreateRuleRequest("", "orders", -5, "1m"), ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(ruleRepository.count()).isZero();
  }

  @Test
  void malformedWindowIsRejected() {
    ResponseEntity<ProblemDetail> response =
        restTemplate()
            .postForEntity(
                url(RULES),
                new CreateRuleRequest("orders", "/orders", 10, "fortnight"),
                ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(ruleRepository.count()).isZero();
  }

  @Test
  void nonUuidRuleIdIsRejectedAsBadRequestRatherThanServerError() {
    ResponseEntity<ProblemDetail> response =
        restTemplate().getForEntity(url(RULES + "/not-a-uuid"), ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void listReturnsRulesAndCapsPageSize() {
    for (int i = 0; i < 5; i++) {
      createRule("orders", "/orders/" + i, 100 + i, "1m");
    }

    ResponseEntity<RulePage> firstPage =
        restTemplate().getForEntity(url(RULES + "?size=2"), RulePage.class);

    assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(firstPage.getBody()).isNotNull();
    assertThat(firstPage.getBody().items()).hasSize(2);
    assertThat(firstPage.getBody().nextCursor()).isNotNull();
  }

  @Test
  void listPagesThroughEveryRuleExactlyOnce() {
    for (int i = 0; i < 5; i++) {
      createRule("orders", "/orders/" + i, 100 + i, "1m");
    }

    List<UUID> seen = new java.util.ArrayList<>();
    String cursor = null;
    do {
      String path = cursor == null ? RULES + "?size=2" : RULES + "?size=2&cursor=" + cursor;
      RulePage page = restTemplate().getForObject(url(path), RulePage.class);
      assertThat(page).isNotNull();
      page.items().forEach(rule -> seen.add(rule.id()));
      cursor = page.nextCursor();
    } while (cursor != null);

    assertThat(seen).hasSize(5).doesNotHaveDuplicates();
  }

  @Test
  void listRequestWithMalformedCursorIsRejected() {
    ResponseEntity<ProblemDetail> response =
        restTemplate().getForEntity(url(RULES + "?cursor=not-a-real-cursor"), ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getTitle()).isEqualTo("Malformed pagination cursor");
  }

  @Test
  void deletedRuleDisappearsFromReadsButItsRowSurvives() {
    RuleResponse created = createRule("orders", "/orders", 1000, "1m");

    ResponseEntity<Void> deleted =
        restTemplate()
            .exchange(
                url(RULES + "/" + created.id()), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    ResponseEntity<ProblemDetail> fetched =
        restTemplate().getForEntity(url(RULES + "/" + created.id()), ProblemDetail.class);
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    // The tombstone is the point: ADR 0007 requires the history to survive
    // deletion, so rollback and audit still have something to read.
    assertThat(ruleRepository.findById(created.id())).isPresent();
    assertThat(ruleRepository.findById(created.id()).orElseThrow().isDeleted()).isTrue();
    assertThat(ruleVersionRepository.findHistory(created.id())).hasSize(1);
  }

  @Test
  void deletingAnAlreadyDeletedRuleReturnsNotFound() {
    RuleResponse created = createRule("orders", "/orders", 1000, "1m");
    restTemplate()
        .exchange(url(RULES + "/" + created.id()), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);

    ResponseEntity<ProblemDetail> second =
        restTemplate()
            .exchange(
                url(RULES + "/" + created.id()),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                ProblemDetail.class);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void endpointFreedBySoftDeleteCanBeReused() {
    RuleResponse first = createRule("orders", "/orders", 1000, "1m");
    restTemplate()
        .exchange(url(RULES + "/" + first.id()), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);

    // The unique index is partial on deleted_at IS NULL, so the endpoint is
    // free again while the old rule's history remains intact.
    ResponseEntity<RuleResponse> recreated =
        restTemplate()
            .postForEntity(
                url(RULES),
                new CreateRuleRequest("orders", "/orders", 2000, "1m"),
                RuleResponse.class);

    assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(recreated.getBody()).isNotNull();
    assertThat(recreated.getBody().id()).isNotEqualTo(first.id());
  }

  @Test
  void malformedJsonBodyIsRejectedAsBadRequest() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<ProblemDetail> response =
        restTemplate()
            .postForEntity(url(RULES), new HttpEntity<>("{not json", headers), ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private RuleResponse createRule(String service, String endpoint, int limit, String window) {
    ResponseEntity<RuleResponse> response =
        restTemplate()
            .postForEntity(
                url(RULES),
                new CreateRuleRequest(service, endpoint, limit, window),
                RuleResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody();
  }
}
