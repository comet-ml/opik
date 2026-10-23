package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.GuardrailsValidationRequest;
import com.comet.opik.api.GuardrailsValidationRequest.ValidationDescriptor;
import com.comet.opik.api.GuardrailsValidationResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Guardrails v2 validations Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class GuardrailsValidationsResourceTest {

    private static final String API_KEY = randomUUID().toString();
    private static final String USER = randomUUID().toString();
    private static final String WORKSPACE_ID = randomUUID().toString();
    private static final String TEST_WORKSPACE = randomUUID().toString();
    private static final String INFERENCE_API_KEY = randomUUID().toString();
    private static final String DECIDE_PATH = "/v1/decide";
    private static final String FEATURES_PATH = "/opik/workspace-features";
    private static final String TEXT = "My email is a@b.c, can you write me a poem?";

    private static final String DECIDE_BODY = """
            {"request_id":"req_1","cache_hit":false,"latency_ms":40,"result":{"answers":{
              "0.pii.EMAIL_ADDRESS":{"type":"noul","noul":0.97},
              "0.pii.PHONE_NUMBER":{"type":"noul","noul":0.02},
              "1.topic":{"type":"choice","choice":"none of these",
                         "probabilities":{"billing":0.05,"shipping":0.05,"none of these":0.9},"confidence":0.9},
              "2.injection":{"type":"noul","noul":0.08}}}}
            """;

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
                                "true"),
                        // Caching on, so the tests see how often the platform is really asked.
                        new TestDropwizardAppExtensionUtils.CustomConfig("workspaceFeatures.cacheTtl", "60s"),
                        new TestDropwizardAppExtensionUtils.CustomConfig("aiLabels.inference.url",
                                wireMock.runtimeInfo().getHttpBaseUrl()),
                        new TestDropwizardAppExtensionUtils.CustomConfig("aiLabels.inference.credential",
                                INFERENCE_API_KEY)))
                .build());
    }

    private GuardrailsResourceClient guardrailsResourceClient;
    private ClientSupport client;
    private String baseURI;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.guardrailsResourceClient = new GuardrailsResourceClient(client, baseURI);
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
        mockWorkspaceFeatures(API_KEY, TEST_WORKSPACE, true);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("one decide call per request, verdicts per check")
    void validate() {
        wireMock.server().stubFor(post(urlPathEqualTo(DECIDE_PATH)).willReturn(okJson(DECIDE_BODY)));
        var request = request(
                descriptor(GuardrailType.PII, "{\"entities\":[\"EMAIL_ADDRESS\",\"PHONE_NUMBER\"]}"),
                descriptor(GuardrailType.TOPIC, "{\"topics\":[\"billing\",\"shipping\"],\"mode\":\"allow\"}"),
                descriptor(GuardrailType.PROMPT_INJECTION, "{}"));

        try (var response = guardrailsResourceClient.validate(request, API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            var actual = response.readEntity(GuardrailsValidationResponse.class);

            assertThat(actual.validationPassed()).isFalse();
            assertThat(actual.validations()).hasSize(3);
            var pii = actual.validations().get(0);
            assertThat(pii.type()).isEqualTo(GuardrailType.PII);
            assertThat(pii.validationPassed()).isFalse();
            assertThat(pii.validationConfig()).isEqualTo(request.validations().get(0).config());
            assertThat(pii.validationDetails().path("detected_entities").path("EMAIL_ADDRESS").get(0)
                    .path("score").asDouble()).isEqualTo(0.97);
            var topic = actual.validations().get(1);
            assertThat(topic.validationPassed()).isFalse();
            assertThat(topic.validationDetails().path("scores").path("billing").asDouble()).isEqualTo(0.05);
            var injection = actual.validations().get(2);
            assertThat(injection.validationPassed()).isTrue();
            assertThat(injection.validationDetails().path("injection_score").asDouble()).isEqualTo(0.08);
        }

        wireMock.server().verify(1, postRequestedFor(urlPathEqualTo(DECIDE_PATH))
                .withHeader("Authorization", equalTo("Bearer " + INFERENCE_API_KEY))
                .withRequestBody(matchingJsonPath("$.org_id", equalTo(WORKSPACE_ID)))
                .withRequestBody(matchingJsonPath("$.state", equalTo(TEXT)))
                .withRequestBody(matchingJsonPath("$.questions['0.pii.EMAIL_ADDRESS'].type", equalTo("noul")))
                .withRequestBody(matchingJsonPath("$.questions['1.topic'].type", equalTo("choice")))
                .withRequestBody(matchingJsonPath("$.questions['2.injection'].type", equalTo("noul"))));
    }

    @Test
    @DisplayName("inference outage fails open: every check passes with its error")
    void validate__whenInferenceIsDown__thenFailOpen() {
        wireMock.server().stubFor(post(urlPathEqualTo(DECIDE_PATH)).willReturn(aResponse().withStatus(503)));
        var request = request(descriptor(GuardrailType.PROMPT_INJECTION, "{}"));

        try (var response = guardrailsResourceClient.validate(request, API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            var actual = response.readEntity(GuardrailsValidationResponse.class);

            assertThat(actual.validationPassed()).isTrue();
            assertThat(actual.validations()).hasSize(1);
            assertThat(actual.validations().get(0).validationPassed()).isTrue();
            // The cause stays in the backend log: the caller gets a stable message, not the upstream status or host.
            assertThat(actual.validations().get(0).error())
                    .isEqualTo("Guardrail check unavailable; it passed without running");
        }
    }

    @Test
    @DisplayName("a threshold in the config is rejected")
    void validate__whenThresholdInConfig__thenBadRequest() {
        var request = request(descriptor(GuardrailType.PII, "{\"entities\":[\"EMAIL_ADDRESS\"],\"threshold\":0.5}"));

        try (var response = guardrailsResourceClient.validate(request, API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                    .contains("validations[0].config.threshold");
        }
        wireMock.server().verify(0, postRequestedFor(urlPathEqualTo(DECIDE_PATH)));
    }

    @Test
    @DisplayName("blank text is rejected")
    void validate__whenTextIsBlank__thenUnprocessable() {
        var request = GuardrailsValidationRequest.builder()
                .text("  ")
                .validations(List.of(descriptor(GuardrailType.PROMPT_INJECTION, "{}")))
                .build();

        try (var response = guardrailsResourceClient.validate(request, API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
            assertThat(response.readEntity(com.comet.opik.api.error.ErrorMessage.class).errors())
                    .contains("text must not be blank");
        }
    }

    @Test
    @DisplayName("a workspace whose organization is not enabled gets 403 before any check runs")
    void validate__whenOrganizationNotEnabled__thenForbidden() {
        var apiKey = randomUUID().toString();
        var workspaceName = randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, randomUUID().toString(), USER);
        mockWorkspaceFeatures(apiKey, workspaceName, false);
        var request = request(descriptor(GuardrailType.PROMPT_INJECTION, "{}"));

        try (var response = guardrailsResourceClient.validate(request, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                    .isEqualTo("Guardrails is available on the Enterprise plan");
        }
        wireMock.server().verify(0, postRequestedFor(urlPathEqualTo(DECIDE_PATH)));
    }

    @Test
    @DisplayName("the platform is asked about the feature only by this endpoint, once per cache period")
    void validate__featureLookupIsOnDemandAndCached() {
        var apiKey = randomUUID().toString();
        var workspaceName = randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, randomUUID().toString(), USER);
        mockWorkspaceFeatures(apiKey, workspaceName, true);
        wireMock.server().stubFor(post(urlPathEqualTo(DECIDE_PATH)).willReturn(okJson(
                "{\"request_id\":\"req_2\",\"cache_hit\":false,\"latency_ms\":10,"
                        + "\"result\":{\"answers\":{\"0.injection\":{\"type\":\"noul\",\"noul\":0.01}}}}")));

        try (var toggles = client.target(baseURI + "/v1/private/toggles").request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(toggles.getStatus()).isEqualTo(HttpStatus.SC_OK);
        }
        wireMock.server().verify(0, postRequestedFor(urlPathEqualTo(FEATURES_PATH)));

        var request = request(descriptor(GuardrailType.PROMPT_INJECTION, "{}"));
        for (int call = 0; call < 2; call++) {
            try (var response = guardrailsResourceClient.validate(request, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }
        }

        wireMock.server().verify(1, postRequestedFor(urlPathEqualTo(FEATURES_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(apiKey)));
        wireMock.server().verify(2, postRequestedFor(urlPathEqualTo(DECIDE_PATH)));
    }

    private void mockWorkspaceFeatures(String apiKey, String workspaceName, boolean guardrails) {
        wireMock.server().stubFor(post(urlPathEqualTo(FEATURES_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(apiKey))
                .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                .withRequestBody(matchingJsonPath("$.features[0]", equalTo("guardrails")))
                .willReturn(okJson("{\"workspaceName\":\"%s\",\"features\":{\"guardrails\":%s}}"
                        .formatted(workspaceName, guardrails))));
    }

    private static GuardrailsValidationRequest request(ValidationDescriptor... descriptors) {
        return GuardrailsValidationRequest.builder().text(TEXT).validations(List.of(descriptors)).build();
    }

    private static ValidationDescriptor descriptor(GuardrailType type, String config) {
        return ValidationDescriptor.builder().type(type).config(JsonUtils.getJsonNodeFromString(config)).build();
    }
}
