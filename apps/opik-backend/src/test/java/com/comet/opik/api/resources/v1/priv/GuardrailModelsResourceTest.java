package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.GuardrailModel;
import com.comet.opik.api.GuardrailModelUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Guardrail Models Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class GuardrailModelsResourceTest {

    private static final String RESOURCE_PATH = "%s/v1/private/guardrails/models";
    private static final String USER = randomUUID().toString();
    private static final String VALID_DATASET = dataset(10, 10);

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
        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new TestDropwizardAppExtensionUtils.CustomConfig(
                                        "serviceToggles.guardrailsValidationsEnabled", "true"),
                                new TestDropwizardAppExtensionUtils.CustomConfig(
                                        "serviceToggles.guardrailsTrainingEnabled", "true"),
                                // Required with the toggles on; the models endpoints never call opik-inference yet.
                                new TestDropwizardAppExtensionUtils.CustomConfig("inference.url",
                                        wireMock.runtimeInfo().getHttpBaseUrl()),
                                new TestDropwizardAppExtensionUtils.CustomConfig("inference.credential",
                                        randomUUID().toString())))
                        .build());
    }

    private ClientSupport client;
    // The app's client uses the Grizzly connector, which PATCH needs and which mangles multipart Content-Type headers,
    // so uploads go through a client of their own.
    private Client multipartClient;
    private String baseURI;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        multipartClient = ClientBuilder.newClient().register(MultiPartFeature.class);
    }

    @AfterAll
    void tearDownAll() {
        multipartClient.close();
        wireMock.server().stop();
    }

    private record Workspace(String apiKey, String name, String id) {
    }

    private Workspace newWorkspace() {
        return newWorkspace(true);
    }

    private Workspace newWorkspace(boolean guardrailsEnabled) {
        var workspace = new Workspace(randomUUID().toString(), randomUUID().toString(), randomUUID().toString());
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), workspace.apiKey(), workspace.name(), workspace.id(),
                USER);
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(workspace.apiKey()))
                .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspace.name())))
                .willReturn(okJson("{\"workspaceName\":\"%s\",\"features\":{\"guardrails\":%s}}"
                        .formatted(workspace.name(), guardrailsEnabled))));
        return workspace;
    }

    private static String dataset(int yes, int no) {
        return Stream.concat(
                IntStream.range(0, yes).mapToObj(index -> "{\"text\": \"violating %d\", \"label\": \"yes\"}"
                        .formatted(index)),
                IntStream.range(0, no).mapToObj(index -> "{\"text\": \"fine %d\", \"label\": \"no\"}"
                        .formatted(index)))
                .collect(Collectors.joining("\n"));
    }

    private Invocation.Builder request(Workspace workspace, String path) {
        return request(workspace, client.target(RESOURCE_PATH.formatted(baseURI) + path));
    }

    private Invocation.Builder upload(Workspace workspace, String path) {
        return request(workspace, multipartClient.target(RESOURCE_PATH.formatted(baseURI) + path));
    }

    private Invocation.Builder request(Workspace workspace, WebTarget target) {
        return target.request()
                .header(HttpHeaders.AUTHORIZATION, workspace.apiKey())
                .header(WORKSPACE_HEADER, workspace.name());
    }

    private static Entity<FormDataMultiPart> multipart(String name, String description, String dataset) {
        var multiPart = new FormDataMultiPart();
        if (name != null) {
            multiPart.field("name", name);
        }
        if (description != null) {
            multiPart.field("description", description);
        }
        multiPart.bodyPart(new FormDataBodyPart("file",
                new ByteArrayInputStream(dataset.getBytes(StandardCharsets.UTF_8)),
                MediaType.APPLICATION_OCTET_STREAM_TYPE));
        return Entity.entity(multiPart, multiPart.getMediaType());
    }

    private Response postModel(Workspace workspace, String name, String description, String dataset) {
        return upload(workspace, "").post(multipart(name, description, dataset));
    }

    private static void assertErrors(Response response, int status, String... errors) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.readEntity(com.comet.opik.api.error.ErrorMessage.class).errors())
                .containsExactly(errors);
    }

    Stream<Arguments> invalidDatasets() {
        var tooLongText = "x".repeat(12_001);
        return Stream.of(
                Arguments.of("a line that is not JSON", dataset(10, 10) + "\nnot json",
                        List.of("not a JSON object, line '21'")),
                Arguments.of("a label other than yes or no",
                        dataset(10, 10) + "\n{\"text\": \"t\", \"label\": \"maybe\"}",
                        List.of("\"label\" must be \"yes\" or \"no\", line '21'")),
                Arguments.of("a text that is too long",
                        dataset(10, 10) + "\n{\"text\": \"%s\", \"label\": \"no\"}".formatted(tooLongText),
                        List.of("\"text\" is longer than 12,000 characters, line '21'")),
                Arguments.of("too few rows", dataset(5, 5),
                        List.of("the file needs 20 to 50,000 valid rows, rows '10'")),
                Arguments.of("too few rows of one label", dataset(18, 4),
                        List.of("the file needs at least 5 rows per label, label 'no', rows '4'")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDatasets")
    @DisplayName("Invalid training data is a 400 listing its problems, on create and on retrain")
    void createOrRetrainWhenDatasetInvalidReturnsBadRequest(String scenario, String dataset, List<String> errors) {
        var workspace = newWorkspace();

        try (var response = postModel(workspace, randomUUID().toString(), randomUUID().toString(), dataset)) {
            assertErrors(response, HttpStatus.SC_BAD_REQUEST, errors.toArray(String[]::new));
        }
        try (var response = upload(workspace, "/" + randomUUID() + "/runs").post(multipart(null, null, dataset))) {
            assertErrors(response, HttpStatus.SC_BAD_REQUEST, errors.toArray(String[]::new));
        }
    }

    @Test
    @DisplayName("A model without a name or a description is refused")
    void createWhenNameOrDescriptionMissingIsRejected() {
        var workspace = newWorkspace();

        try (var response = postModel(workspace, null, randomUUID().toString(), VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "The request body name must not be blank");
        }
        try (var response = postModel(workspace, randomUUID().toString(), " ", VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY,
                    "The request body description must not be blank");
        }
    }

    @Test
    @DisplayName("A name or a description at its maximum length passes validation, one character longer is a 422")
    void createWhenNameOrDescriptionAtMaxLength() {
        var workspace = newWorkspace();
        var name = "n".repeat(GuardrailModel.NAME_MAX_LENGTH);
        var description = "d".repeat(GuardrailModel.DESCRIPTION_MAX_LENGTH);

        try (var response = postModel(workspace, name + "n", randomUUID().toString(), VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY,
                    "The request body name cannot exceed 255 characters");
        }
        try (var response = postModel(workspace, randomUUID().toString(), description + "d", VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY,
                    "The request body description cannot exceed 1000 characters");
        }
        try (var response = postModel(workspace, name, description, VALID_DATASET)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_IMPLEMENTED);
        }

        try (var response = request(workspace, "/" + randomUUID()).method("PATCH",
                Entity.json(new GuardrailModelUpdate(description + "d")))) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "description cannot exceed 1000 characters");
        }
        try (var response = request(workspace, "/" + randomUUID()).method("PATCH",
                Entity.json(new GuardrailModelUpdate(" ")))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("A workspace whose organization does not have guardrails is refused")
    void whenGuardrailsNotEnabledForOrganizationReturnsForbidden() {
        var workspace = newWorkspace(false);

        try (var response = request(workspace, "").get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
        }
        try (var response = postModel(workspace, randomUUID().toString(), randomUUID().toString(), VALID_DATASET)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            assertThat(response.readEntity(ErrorMessage.class).getMessage())
                    .isEqualTo("Guardrails is available on the Enterprise plan");
        }
    }

    Stream<Arguments> endpoints() {
        var id = "/" + randomUUID();
        var runId = "/runs/" + randomUUID();
        return Stream.of(
                Arguments.of("create",
                        (Function<Workspace, Response>) workspace -> postModel(workspace, randomUUID().toString(),
                                randomUUID().toString(), VALID_DATASET)),
                Arguments.of("find", (Function<Workspace, Response>) workspace -> request(workspace, "").get()),
                Arguments.of("get by id", (Function<Workspace, Response>) workspace -> request(workspace, id).get()),
                Arguments.of("update", (Function<Workspace, Response>) workspace -> request(workspace, id)
                        .method("PATCH", Entity.json(new GuardrailModelUpdate(randomUUID().toString())))),
                Arguments.of("delete", (Function<Workspace, Response>) workspace -> request(workspace, id).delete()),
                Arguments.of("retrain", (Function<Workspace, Response>) workspace -> upload(workspace, id + "/runs")
                        .post(multipart(null, null, VALID_DATASET))),
                Arguments.of("find runs",
                        (Function<Workspace, Response>) workspace -> request(workspace, id + "/runs").get()),
                Arguments.of("get run",
                        (Function<Workspace, Response>) workspace -> request(workspace, id + runId).get()),
                Arguments.of("cancel run", (Function<Workspace, Response>) workspace -> request(workspace,
                        id + runId + "/cancel").post(Entity.json(""))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("Past the gates and validation, every endpoint is a 501 until its services are wired in")
    void endpointIsNotImplementedYet(String endpoint, Function<Workspace, Response> call) {
        try (var response = call.apply(newWorkspace())) {
            assertErrors(response, HttpStatus.SC_NOT_IMPLEMENTED, "Guardrail models are not available yet");
        }
    }
}
