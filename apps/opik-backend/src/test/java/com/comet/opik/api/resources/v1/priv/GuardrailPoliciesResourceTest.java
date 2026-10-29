package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.GuardrailPolicy;
import com.comet.opik.api.GuardrailPolicy.GuardrailPolicyRetrieve;
import com.comet.opik.api.GuardrailPolicyRule;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import java.util.function.Function;
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
    private static final String API_KEY = randomUUID().toString();
    private static final String USER = randomUUID().toString();
    private static final String WORKSPACE_ID = randomUUID().toString();
    private static final String TEST_WORKSPACE = randomUUID().toString();

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
        APP = newTestDropwizardAppExtension(MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory,
                wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private ClientSupport client;
    private String baseURI;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private Invocation.Builder request(String path) {
        return client.target(RESOURCE_PATH.formatted(baseURI) + path)
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE);
    }

    private static GuardrailPolicy policy() {
        return GuardrailPolicy.builder()
                .name("support-bot")
                .rules(List.of(GuardrailPolicyRule.builder()
                        .type(GuardrailType.PII)
                        .config(JsonUtils.getJsonNodeFromString("{\"entities\":[\"EMAIL_ADDRESS\"]}"))
                        .build()))
                .build();
    }

    Stream<Arguments> endpoints() {
        var id = "/" + randomUUID();
        return Stream.of(
                Arguments.of("create", (Function<GuardrailPoliciesResourceTest, Response>) test -> test.request("")
                        .post(Entity.json(policy()))),
                Arguments.of("get by id", (Function<GuardrailPoliciesResourceTest, Response>) test -> test.request(id)
                        .get()),
                Arguments.of("find", (Function<GuardrailPoliciesResourceTest, Response>) test -> test.request("")
                        .get()),
                Arguments.of("update", (Function<GuardrailPoliciesResourceTest, Response>) test -> test.request(id)
                        .put(Entity.json(policy()))),
                Arguments.of("delete", (Function<GuardrailPoliciesResourceTest, Response>) test -> test
                        .request("/delete").post(Entity.json(new BatchDelete(Set.of(randomUUID()))))),
                Arguments.of("retrieve", (Function<GuardrailPoliciesResourceTest, Response>) test -> test
                        .request("/retrieve").post(Entity.json(new GuardrailPolicyRetrieve(Set.of("support-bot"))))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("every guardrail policy endpoint answers 501 until policies are implemented")
    void endpointIsNotImplementedYet(String endpoint, Function<GuardrailPoliciesResourceTest, Response> call) {
        try (var response = call.apply(this)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_IMPLEMENTED);
            assertThat(response.readEntity(ErrorMessage.class).getMessage())
                    .isEqualTo("Guardrail policies are not available yet");
        }
    }

    Stream<Arguments> invalidPolicies() {
        var rule = policy().rules().getFirst();
        return Stream.of(
                Arguments.of("blank name", policy().toBuilder().name(" ").build(), "name must not be blank"),
                Arguments.of("no rules", policy().toBuilder().rules(List.of()).build(), "rules must not be empty"),
                Arguments.of("a rule without a type", policy().toBuilder()
                        .rules(List.of(rule.toBuilder().type(null).build())).build(),
                        "rules[0].type must not be null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPolicies")
    @DisplayName("an invalid policy is rejected before anything else")
    void invalidPolicyIsUnprocessable(String scenario, GuardrailPolicy policy, String expectedError) {
        try (var response = request("").post(Entity.json(policy))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
            assertThat(response.readEntity(com.comet.opik.api.error.ErrorMessage.class).errors())
                    .containsExactly(expectedError);
        }
    }
}
