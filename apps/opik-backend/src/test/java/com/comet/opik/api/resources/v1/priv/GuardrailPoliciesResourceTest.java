package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.GuardrailExecutionScope;
import com.comet.opik.api.GuardrailPolicy;
import com.comet.opik.api.GuardrailPolicy.GuardrailPolicyPage;
import com.comet.opik.api.GuardrailPolicy.GuardrailPolicyRetrieve;
import com.comet.opik.api.GuardrailPolicy.GuardrailPolicyRetrieveResponse;
import com.comet.opik.api.GuardrailPolicyRule;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Guardrail Policies Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class GuardrailPoliciesResourceTest {

    private static final String RESOURCE_PATH = "%s/v1/private/guardrails/policies";
    private static final String USER = randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);
        APP = newTestDropwizardAppExtension(TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .customConfigs(List.of(
                        new TestDropwizardAppExtensionUtils.CustomConfig("serviceToggles.guardrailsValidationsEnabled",
                                "true")))
                .build());
    }

    private ClientSupport client;
    private String baseURI;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private record Workspace(String apiKey, String name) {
    }

    private Workspace newWorkspace() {
        var workspace = new Workspace(randomUUID().toString(), randomUUID().toString());
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), workspace.apiKey(), workspace.name(),
                randomUUID().toString(), USER);
        return workspace;
    }

    private static GuardrailPolicyRule rule(GuardrailType type, String config) {
        return GuardrailPolicyRule.builder().type(type).config(JsonUtils.getJsonNodeFromString(config)).build();
    }

    private static GuardrailPolicy policy(String name, GuardrailPolicyRule... rules) {
        return GuardrailPolicy.builder().name(name).rules(List.of(rules)).build();
    }

    private static GuardrailPolicy piiPolicy(String name) {
        return policy(name, rule(GuardrailType.PII, "{\"entities\":[\"EMAIL_ADDRESS\"]}"));
    }

    private Invocation.Builder request(Workspace workspace, String path) {
        return client.target(RESOURCE_PATH.formatted(baseURI) + path)
                .request()
                .header(HttpHeaders.AUTHORIZATION, workspace.apiKey())
                .header(WORKSPACE_HEADER, workspace.name());
    }

    private GuardrailPolicy create(Workspace workspace, GuardrailPolicy policy) {
        UUID id;
        try (var response = request(workspace, "").post(Entity.json(policy))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            assertThat(response.hasEntity()).isFalse();
            id = TestUtils.getIdFromLocation(response.getLocation());
        }
        return get(workspace, id);
    }

    private GuardrailPolicy get(Workspace workspace, UUID id) {
        try (var response = request(workspace, "/" + id).get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(GuardrailPolicy.class);
        }
    }

    private int getStatus(Workspace workspace, UUID id) {
        try (var response = request(workspace, "/" + id).get()) {
            return response.getStatus();
        }
    }

    private GuardrailPolicyPage find(Workspace workspace, String name, List<SortingField> sorting, int page,
            int size) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size);
        if (name != null) {
            target = target.queryParam("name", name);
        }
        if (sorting != null) {
            target = target.queryParam("sorting", TestUtils.toURLEncodedQueryParam(sorting));
        }
        try (var response = target.request()
                .header(HttpHeaders.AUTHORIZATION, workspace.apiKey())
                .header(WORKSPACE_HEADER, workspace.name())
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(GuardrailPolicyPage.class);
        }
    }

    private List<GuardrailPolicy> retrieve(Workspace workspace, Set<String> names) {
        try (var response = request(workspace, "/retrieve").post(Entity.json(new GuardrailPolicyRetrieve(names)))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(GuardrailPolicyRetrieveResponse.class).policies();
        }
    }

    @Test
    @DisplayName("create stores only the user's settings, and get returns the policy")
    void createAndGet() {
        var workspace = newWorkspace();
        var request = GuardrailPolicy.builder()
                .name("support-bot")
                .description("Checks for the support agent")
                .rules(List.of(
                        rule(GuardrailType.TOPIC,
                                "{\"allowed_topics\":[\" billing \",\"shipping\"],\"restricted_topics\":[]}"),
                        rule(GuardrailType.PII, "{\"entities\":[\"EMAIL_ADDRESS\"],\"language\":\"en\"}"),
                        rule(GuardrailType.PROMPT_INJECTION, "{}")))
                .build();

        var created = create(workspace, request);

        assertThat(created.name()).isEqualTo("support-bot");
        assertThat(created.description()).isEqualTo("Checks for the support agent");
        assertThat(created.executionScope()).isEqualTo(GuardrailExecutionScope.ON_REQUEST);
        assertThat(created.createdBy()).isEqualTo(USER);
        assertThat(created.lastUpdatedBy()).isEqualTo(USER);
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.rules()).containsExactly(
                rule(GuardrailType.TOPIC, "{\"allowed_topics\":[\"billing\",\"shipping\"]}"),
                rule(GuardrailType.PII, "{\"entities\":[\"EMAIL_ADDRESS\"]}"),
                rule(GuardrailType.PROMPT_INJECTION, "{}"));
    }

    @Test
    @DisplayName("policy names are unique in a workspace, ignoring case, and only there")
    void nameIsUniquePerWorkspace() {
        var workspace = newWorkspace();
        create(workspace, piiPolicy("Support-Bot"));

        try (var response = request(workspace, "").post(Entity.json(piiPolicy("support-bot")))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
        }
        create(newWorkspace(), piiPolicy("support-bot"));
    }

    static Stream<Arguments> invalidRules() {
        var tooManyTopics = IntStream.rangeClosed(1, 255).mapToObj(i -> "\"topic " + i + "\"").toList();
        return Stream.of(
                Arguments.of(List.of(rule(GuardrailType.PII, "{\"entities\":[\"PERSON\"]}"),
                        rule(GuardrailType.PII, "{\"entities\":[\"URL\"]}")),
                        "rules[1].type 'PII' is repeated: a policy holds at most one rule per type"),
                Arguments.of(List.of(rule(GuardrailType.LLM_JUDGE, "{}")),
                        "rules[0].type 'LLM_JUDGE' is not supported"),
                Arguments.of(List.of(rule(GuardrailType.PII, "[\"PERSON\"]")),
                        "rules[0].config must be an object"),
                Arguments.of(List.of(rule(GuardrailType.PROMPT_INJECTION, "{\"threshold\":0.5}")),
                        "rules[0].config.threshold is not accepted: thresholds are set by the server"),
                Arguments.of(List.of(rule(GuardrailType.PII, "{\"entities\":[]}")),
                        "rules[0].config.entities must be a non-empty list of strings"),
                Arguments.of(List.of(rule(GuardrailType.PII, "{\"entities\":[\"PERSON\",\" \"]}")),
                        "rules[0].config.entities must contain only non-blank strings"),
                Arguments.of(List.of(rule(GuardrailType.PII, "{\"entities\":[\"PERSON\",\"PERSON \"]}")),
                        "rules[0].config.entities contains a value more than once"),
                Arguments.of(List.of(rule(GuardrailType.TOPIC, "{\"allowed_topics\":[]}")),
                        "rules[0].config needs allowed_topics or restricted_topics"),
                Arguments.of(List.of(rule(GuardrailType.TOPIC,
                        "{\"allowed_topics\":[\"Billing\"],\"restricted_topics\":[\"politics\",\"billing\"]}")),
                        "rules[0].config has 'Billing' in both allowed_topics and restricted_topics"),
                Arguments.of(List.of(rule(GuardrailType.TOPIC,
                        "{\"restricted_topics\":[\"" + "a".repeat(257) + "\"]}")),
                        "rules[0].config.restricted_topics has a topic longer than 256 characters"),
                Arguments.of(List.of(rule(GuardrailType.TOPIC, "{\"allowed_topics\":" + tooManyTopics + "}")),
                        "rules[0].config.allowed_topics has more than 254 topics"));
    }

    @ParameterizedTest
    @MethodSource("invalidRules")
    @DisplayName("invalid rules are rejected with the reason")
    void invalidRulesAreRejected(List<GuardrailPolicyRule> rules, String message) {
        var workspace = newWorkspace();
        var request = GuardrailPolicy.builder().name("policy").rules(rules).build();

        try (var response = request(workspace, "").post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(ErrorMessage.class).getMessage()).isEqualTo(message);
        }
        assertThat(find(workspace, null, null, 1, 10).total()).isZero();
    }

    @Test
    @DisplayName("a policy without rules is rejected")
    void policyWithoutRulesIsRejected() {
        try (var response = request(newWorkspace(), "").post(Entity.json(policy("policy")))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("update replaces the policy and its rules")
    void update() {
        var workspace = newWorkspace();
        var created = create(workspace, policy("policy",
                rule(GuardrailType.PII, "{\"entities\":[\"PERSON\"]}"),
                rule(GuardrailType.PROMPT_INJECTION, "{}")));
        var replacement = GuardrailPolicy.builder()
                .name("renamed")
                .executionScope(GuardrailExecutionScope.GLOBAL)
                .rules(List.of(rule(GuardrailType.TOPIC,
                        "{\"allowed_topics\":[\"billing\"],\"restricted_topics\":[\"politics\"]}")))
                .build();

        try (var response = request(workspace, "/" + created.id()).put(Entity.json(replacement))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }

        var updated = get(workspace, created.id());
        assertThat(updated.name()).isEqualTo("renamed");
        assertThat(updated.description()).isNull();
        assertThat(updated.executionScope()).isEqualTo(GuardrailExecutionScope.GLOBAL);
        assertThat(updated.rules()).containsExactly(
                rule(GuardrailType.TOPIC, "{\"allowed_topics\":[\"billing\"],\"restricted_topics\":[\"politics\"]}"));
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.lastUpdatedAt()).isAfter(created.lastUpdatedAt());
    }

    @Test
    @DisplayName("update answers 404 for another workspace's policy and 409 for a taken name")
    void updateErrors() {
        var workspace = newWorkspace();
        var first = create(workspace, piiPolicy("first"));
        create(workspace, piiPolicy("second"));

        try (var response = request(workspace, "/" + first.id()).put(Entity.json(piiPolicy("SECOND")))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
        }
        try (var response = request(newWorkspace(), "/" + first.id()).put(Entity.json(piiPolicy("other")))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
        assertThat(get(workspace, first.id())).isEqualTo(first);
    }

    @Test
    @DisplayName("find pages a workspace's policies, newest first unless sorted, and filters by name")
    void find() {
        var workspace = newWorkspace();
        var supportBot = create(workspace, piiPolicy("support-bot"));
        var salesBot = create(workspace, piiPolicy("sales-bot"));
        var internal = create(workspace, piiPolicy("internal"));
        create(newWorkspace(), piiPolicy("other-bot"));

        var newestFirst = find(workspace, null, null, 1, 10);
        assertThat(newestFirst.total()).isEqualTo(3);
        assertThat(newestFirst.content()).containsExactly(internal, salesBot, supportBot);
        assertThat(newestFirst.sortableBy()).containsExactly("name", "created_at", "last_updated_at");

        var byNameDescending = find(workspace, null,
                List.of(SortingField.builder().field("name").direction(Direction.DESC).build()), 1, 10);
        assertThat(byNameDescending.content()).containsExactly(supportBot, salesBot, internal);

        var secondBot = find(workspace, "BOT", null, 2, 1);
        assertThat(secondBot.total()).isEqualTo(2);
        assertThat(secondBot.page()).isEqualTo(2);
        assertThat(secondBot.content()).containsExactly(supportBot);
    }

    @Test
    @DisplayName("delete removes the workspace's policies and leaves other workspaces alone")
    void delete() {
        var workspace = newWorkspace();
        var otherWorkspace = newWorkspace();
        var deleted = create(workspace, piiPolicy("deleted"));
        var kept = create(workspace, piiPolicy("kept"));
        var otherWorkspacePolicy = create(otherWorkspace, piiPolicy("deleted"));

        var batch = new BatchDelete(Set.of(deleted.id(), otherWorkspacePolicy.id(), randomUUID()));
        try (var response = request(workspace, "/delete").post(Entity.json(batch))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }

        assertThat(getStatus(workspace, deleted.id())).isEqualTo(HttpStatus.SC_NOT_FOUND);
        assertThat(getStatus(workspace, kept.id())).isEqualTo(HttpStatus.SC_OK);
        assertThat(get(otherWorkspace, otherWorkspacePolicy.id())).isEqualTo(otherWorkspacePolicy);
        // The deleted policy's name is free again.
        create(workspace, piiPolicy("deleted"));
    }

    @Test
    @DisplayName("retrieve returns the named policies plus every global policy")
    void retrieve() {
        var workspace = newWorkspace();
        var named = create(workspace, piiPolicy("support-bot"));
        create(workspace, piiPolicy("sales-bot"));
        var global = create(workspace, piiPolicy("workspace-wide").toBuilder()
                .executionScope(GuardrailExecutionScope.GLOBAL).build());
        create(newWorkspace(), piiPolicy("other-global").toBuilder()
                .executionScope(GuardrailExecutionScope.GLOBAL).build());

        assertThat(retrieve(workspace, Set.of("SUPPORT-BOT"))).containsExactly(named, global);
        assertThat(retrieve(workspace, Set.of("workspace-wide"))).containsExactly(global);
        assertThat(retrieve(workspace, Set.of())).containsExactly(global);
    }

    @Test
    @DisplayName("retrieve fails when a named policy does not exist")
    void retrieveUnknownName() {
        var workspace = newWorkspace();
        create(workspace, piiPolicy("support-bot"));

        try (var response = request(workspace, "/retrieve")
                .post(Entity.json(new GuardrailPolicyRetrieve(Set.of("support-bot", "missing"))))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            assertThat(response.readEntity(ErrorMessage.class).getMessage())
                    .isEqualTo("Guardrail policies not found: missing");
        }
    }
}
