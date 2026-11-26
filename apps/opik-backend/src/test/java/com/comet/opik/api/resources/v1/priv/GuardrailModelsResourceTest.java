package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.GuardrailExecutionScope;
import com.comet.opik.api.GuardrailModel;
import com.comet.opik.api.GuardrailModel.GuardrailModelPage;
import com.comet.opik.api.GuardrailModelConfig;
import com.comet.opik.api.GuardrailModelUpdate;
import com.comet.opik.api.GuardrailPolicy;
import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRun.GuardrailTrainingRunPage;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.error.ErrorMessage;
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
import com.comet.opik.domain.GuardrailPolicyDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.GuardrailTrainingRunStatus.CANCELLED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.COMPLETED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.FAILED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.QUEUED;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
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
    private TransactionTemplate template;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplate template) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        multipartClient = ClientBuilder.newClient().register(MultiPartFeature.class);
        // Writes go through the app's TransactionTemplate, because a handle borrowed outside one can be a pooled
        // connection an earlier read-only transaction left read-only.
        this.template = template;
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

    private static String newName() {
        return "model-" + randomUUID();
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

    /** Each part only when given, so a request can leave one out. */
    private static Entity<FormDataMultiPart> multipart(String name, String description, GuardrailModelConfig config,
            String dataset) {
        var multiPart = new FormDataMultiPart();
        if (name != null) {
            multiPart.field("name", name);
        }
        if (description != null) {
            multiPart.field("description", description);
        }
        if (config != null) {
            multiPart.bodyPart(new FormDataBodyPart("config", JsonUtils.writeValueAsString(config),
                    MediaType.APPLICATION_JSON_TYPE));
        }
        if (dataset != null) {
            multiPart.bodyPart(new FormDataBodyPart("file",
                    new ByteArrayInputStream(dataset.getBytes(StandardCharsets.UTF_8)),
                    MediaType.APPLICATION_OCTET_STREAM_TYPE));
        }
        return Entity.entity(multiPart, multiPart.getMediaType());
    }

    /**
     * A body written by hand, so the config part can go without a Content-Type, as a browser's FormData and curl -F
     * send a text part. An empty configContentType leaves the header out.
     */
    private static Entity<byte[]> rawMultipart(String name, String configContentType, String config) {
        var boundary = randomUUID().toString();
        var configHeader = configContentType.isEmpty() ? "" : "Content-Type: %s\r\n".formatted(configContentType);
        var body = """
                --%1$s\r
                Content-Disposition: form-data; name="name"\r
                \r
                %2$s\r
                --%1$s\r
                Content-Disposition: form-data; name="config"\r
                %3$s\r
                %4$s\r
                --%1$s\r
                Content-Disposition: form-data; name="file"; filename="data.jsonl"\r
                Content-Type: application/octet-stream\r
                \r
                %5$s\r
                --%1$s--\r
                """.formatted(boundary, name, configHeader, config, VALID_DATASET);
        return Entity.entity(body.getBytes(StandardCharsets.UTF_8), "multipart/form-data; boundary=" + boundary);
    }

    private static GuardrailModelConfig configFor(String name) {
        return new GuardrailModelConfig("Does the text break the rule " + name + "?");
    }

    private Response postModel(Workspace workspace, String name, String description, GuardrailModelConfig config,
            String dataset) {
        return upload(workspace, "").post(multipart(name, description, config, dataset));
    }

    private UUID created(Response response) {
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
        assertThat(response.hasEntity()).isFalse();
        return TestUtils.getIdFromLocation(response.getLocation());
    }

    private UUID create(Workspace workspace, String name) {
        try (var response = postModel(workspace, name, "Flags " + name, configFor(name), VALID_DATASET)) {
            return created(response);
        }
    }

    private GuardrailModel get(Workspace workspace, UUID id) {
        try (var response = request(workspace, "/" + id).get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(GuardrailModel.class);
        }
    }

    private int getStatus(Workspace workspace, String path) {
        try (var response = request(workspace, path).get()) {
            return response.getStatus();
        }
    }

    private GuardrailModelPage find(Workspace workspace, Function<WebTarget, WebTarget> query) {
        try (var response = request(workspace, query.apply(client.target(RESOURCE_PATH.formatted(baseURI))))
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(GuardrailModelPage.class);
        }
    }

    private Response postRun(Workspace workspace, UUID modelId, String dataset) {
        return upload(workspace, "/" + modelId + "/runs").post(multipart(null, null, null, dataset));
    }

    private UUID retrain(Workspace workspace, UUID modelId) {
        try (var response = postRun(workspace, modelId, VALID_DATASET)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    private GuardrailTrainingRun getRun(Workspace workspace, UUID modelId, UUID runId) {
        try (var response = request(workspace, "/" + modelId + "/runs/" + runId).get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(GuardrailTrainingRun.class);
        }
    }

    private GuardrailTrainingRunPage getRuns(Workspace workspace, UUID modelId, int page, int size) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI) + "/" + modelId + "/runs")
                .queryParam("page", page)
                .queryParam("size", size);
        try (var response = request(workspace, target).get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(GuardrailTrainingRunPage.class);
        }
    }

    private Response cancel(Workspace workspace, UUID modelId, UUID runId) {
        return request(workspace, "/" + modelId + "/runs/" + runId + "/cancel").post(Entity.json(""));
    }

    private void cancelLatestRun(Workspace workspace, UUID modelId) {
        try (var response = cancel(workspace, modelId, get(workspace, modelId).latestRun().id())) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    /**
     * Moves the run to the status in MySQL, as its job will once training runs on opik-inference: the API itself only
     * queues and cancels runs.
     */
    private GuardrailTrainingRun move(Workspace workspace, GuardrailTrainingRun run,
            GuardrailTrainingRunStatus status) {
        int updated = template.inTransaction(WRITE, handle -> handle
                .createUpdate("UPDATE guardrail_training_runs SET status = :status WHERE id = :id")
                .bind("status", status.name())
                .bind("id", run.id().toString())
                .execute());
        assertThat(updated).isOne();
        return getRun(workspace, run.modelId(), run.id());
    }

    private static GuardrailTrainingRun queuedRun(UUID modelId) {
        return GuardrailTrainingRun.builder()
                .modelId(modelId)
                .status(QUEUED)
                .attempt(1)
                .createdBy(USER)
                .lastUpdatedBy(USER)
                .build();
    }

    private static void assertErrors(Response response, int status, String... errors) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.readEntity(ErrorMessage.class)).isEqualTo(new ErrorMessage(List.of(errors)));
    }

    /** Writes a policy rule that uses the model, in the workspace. */
    private void useInPolicy(Workspace workspace, UUID modelId) {
        // The policies API does not take custom classifier rules yet, so the rule is written directly.
        var policy = GuardrailPolicy.builder()
                .id(randomUUID())
                .name(randomUUID().toString())
                .executionScope(GuardrailExecutionScope.ON_REQUEST)
                .build();
        template.inTransaction(WRITE, handle -> {
            var policies = handle.attach(GuardrailPolicyDAO.class);
            policies.save(policy, workspace.id(), USER);
            policies.saveRules(List.of(randomUUID()), policy.id(), List.of(GuardrailType.CUSTOM_CLASSIFIER),
                    List.of(JsonUtils.createObjectNode().put("model_id", modelId.toString())), USER);
            return null;
        });
    }

    /** One endpoint, called on a model and a run that may not exist. */
    private record Call(String name, String path, boolean upload, Function<Invocation.Builder, Response> send) {
        @Override
        public String toString() {
            return name;
        }
    }

    Stream<Call> reads() {
        var id = "/" + randomUUID();
        return Stream.of(
                new Call("list", "", false, Invocation.Builder::get),
                new Call("get", id, false, Invocation.Builder::get),
                new Call("list runs", id + "/runs", false, Invocation.Builder::get),
                new Call("get run", id + "/runs/" + randomUUID(), false, Invocation.Builder::get));
    }

    Stream<Call> writes() {
        var id = "/" + randomUUID();
        return Stream.of(
                new Call("create", "", true, builder -> builder.post(
                        multipart(newName(), randomUUID().toString(), configFor(newName()), VALID_DATASET))),
                new Call("update", id, false, builder -> builder.method("PATCH",
                        Entity.json(GuardrailModelUpdate.builder().description(randomUUID().toString()).build()))),
                new Call("delete", id, false, Invocation.Builder::delete),
                new Call("retrain", id + "/runs", true,
                        builder -> builder.post(multipart(null, null, null, VALID_DATASET))),
                new Call("cancel run", id + "/runs/" + randomUUID() + "/cancel", false,
                        builder -> builder.post(Entity.json(""))));
    }

    private Response send(Workspace workspace, Call call) {
        return call.send().apply(call.upload() ? upload(workspace, call.path()) : request(workspace, call.path()));
    }

    @Test
    @DisplayName("A model is created with its first run queued, and read with it")
    void createAndGet() {
        var workspace = newWorkspace();
        // Not ASCII: form fields arrive without a charset, as browsers send them.
        var name = "Naïve résumé check " + randomUUID();

        var id = create(workspace, name);

        var runs = getRuns(workspace, id, 1, 10);
        assertThat(runs.content()).hasSize(1);
        var firstRun = runs.content().getFirst();
        assertThat(firstRun)
                .usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "lastUpdatedAt")
                .isEqualTo(queuedRun(id));
        var model = get(workspace, id);
        assertThat(model)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "lastUpdatedAt")
                .isEqualTo(GuardrailModel.builder()
                        .id(id)
                        .name(name)
                        .description("Flags " + name)
                        .config(configFor(name))
                        .latestRun(firstRun)
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build());
    }

    @Test
    @DisplayName("A model needs no description, and a blank one counts as none")
    void createWithoutDescription() {
        var workspace = newWorkspace();
        var name = newName();
        var otherName = newName();

        UUID id;
        try (var response = postModel(workspace, name, null, configFor(name), VALID_DATASET)) {
            id = created(response);
        }
        // A form sends an empty field for an empty input.
        UUID otherId;
        try (var response = postModel(workspace, otherName, " ", configFor(otherName), VALID_DATASET)) {
            otherId = created(response);
        }

        var model = get(workspace, id);
        assertThat(model)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "lastUpdatedAt")
                .isEqualTo(GuardrailModel.builder()
                        .id(id)
                        .name(name)
                        .config(configFor(name))
                        .latestRun(model.latestRun())
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build());
        assertThat(get(workspace, otherId).description()).isNull();
    }

    @ParameterizedTest(name = "Content-Type [{0}]")
    @ValueSource(strings = {"", "text/plain"})
    @DisplayName("The config part is read as JSON whatever its Content-Type, as browsers and curl send it with none")
    void createWhenConfigPartIsNotTypedAsJson(String configContentType) {
        var workspace = newWorkspace();
        var name = newName();

        UUID id;
        try (var response = upload(workspace, "").post(
                rawMultipart(name, configContentType, JsonUtils.writeValueAsString(configFor(name))))) {
            id = created(response);
        }

        assertThat(get(workspace, id).config()).isEqualTo(configFor(name));
    }

    @Test
    @DisplayName("A config part that is not JSON is a 400, and nothing is created")
    void createWhenConfigPartIsNotJsonReturnsBadRequest() {
        var workspace = newWorkspace();

        try (var response = upload(workspace, "").post(rawMultipart(newName(), "", "Is the text spam?"))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                    .isEqualTo("Unable to process the config part as JSON. Unrecognized token 'Is': was expecting"
                            + " (JSON String, Number (or 'NaN'/'+INF'/'-INF'), Array, Object or token 'null',"
                            + " 'true' or 'false')");
        }

        assertThat(find(workspace, target -> target).total()).isZero();
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
    @DisplayName("Invalid training data is a 400 listing its problems, and nothing is created")
    void createWhenDatasetInvalidReturnsBadRequest(String scenario, String dataset, List<String> errors) {
        var workspace = newWorkspace();

        var name = newName();
        try (var response = postModel(workspace, name, randomUUID().toString(), configFor(name), dataset)) {
            assertErrors(response, HttpStatus.SC_BAD_REQUEST, errors.toArray(String[]::new));
        }

        assertThat(find(workspace, target -> target).total()).isZero();
    }

    @Test
    @DisplayName("A name the workspace has, ignoring case, is a 409, and nothing is created")
    void createWhenNameTakenReturnsConflict() {
        var workspace = newWorkspace();
        var name = newName();
        create(workspace, name);

        try (var response = postModel(workspace, name.toUpperCase(), randomUUID().toString(), configFor(name),
                VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_CONFLICT, "Guardrail model already exists");
        }

        assertThat(find(workspace, target -> target).total()).isOne();
    }

    @Test
    @DisplayName("A model without a name or a config, or with a blank prompt, is not created")
    void createWhenNameOrConfigMissingIsRejected() {
        var workspace = newWorkspace();
        var name = newName();

        try (var response = postModel(workspace, null, randomUUID().toString(), configFor(name), VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "The request body name must not be blank");
        }
        try (var response = postModel(workspace, name, randomUUID().toString(), null, VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "The request body config must not be null");
        }
        try (var response = postModel(workspace, name, randomUUID().toString(), new GuardrailModelConfig(" "),
                VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "prompt must not be blank");
        }

        assertThat(find(workspace, target -> target).total()).isZero();
    }

    @Test
    @DisplayName("A name, a description or a prompt at its maximum length is accepted, one character longer is a 422")
    void createWhenAtMaxLength() {
        var workspace = newWorkspace();
        var name = "n".repeat(GuardrailModel.NAME_MAX_LENGTH);
        var description = "d".repeat(GuardrailModel.DESCRIPTION_MAX_LENGTH);
        var prompt = "p".repeat(GuardrailModelConfig.PROMPT_MAX_LENGTH);

        try (var response = postModel(workspace, name + "n", randomUUID().toString(), configFor(name),
                VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY,
                    "The request body name cannot exceed 255 characters");
        }
        try (var response = postModel(workspace, randomUUID().toString(), description + "d", configFor(name),
                VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY,
                    "The request body description cannot exceed 1000 characters");
        }
        try (var response = postModel(workspace, randomUUID().toString(), description,
                new GuardrailModelConfig(prompt + "p"), VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "prompt cannot exceed 10000 characters");
        }
        UUID id;
        try (var response = postModel(workspace, name, description, new GuardrailModelConfig(prompt),
                VALID_DATASET)) {
            id = created(response);
        }

        var model = get(workspace, id);
        assertThat(model.name()).isEqualTo(name);
        assertThat(model.description()).isEqualTo(description);
        assertThat(model.config()).isEqualTo(new GuardrailModelConfig(prompt));
        try (var response = request(workspace, "/" + id).method("PATCH",
                Entity.json(GuardrailModelUpdate.builder().description(description + "d").build()))) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "description cannot exceed 1000 characters");
        }
        try (var response = request(workspace, "/" + id).method("PATCH",
                Entity.json(GuardrailModelUpdate.builder().config(new GuardrailModelConfig(prompt + "p")).build()))) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY,
                    "config.prompt cannot exceed 10000 characters");
        }
    }

    @Test
    @DisplayName("The page filters by a usable model, its latest run's status and a partial name, and sorts")
    void find() {
        var workspace = newWorkspace();
        var suffix = randomUUID().toString();
        var usableId = create(workspace, "a-" + suffix);
        move(workspace, get(workspace, usableId).latestRun(), COMPLETED);
        var trainingId = create(workspace, "b-" + suffix);
        var usable = get(workspace, usableId);
        var training = get(workspace, trainingId);

        var page = find(workspace, target -> target.queryParam("sorting",
                TestUtils.toURLEncodedQueryParam(List.of(new SortingField("name", Direction.DESC)))));

        assertThat(page).isEqualTo(GuardrailModelPage.builder()
                .content(List.of(training, usable))
                .page(1)
                .size(2)
                .total(2)
                .sortableBy(List.of("name", "created_at", "last_updated_at"))
                .build());
        assertThat(usable.servingRun()).isEqualTo(usable.latestRun());
        assertThat(usable.servingRun().status()).isEqualTo(COMPLETED);
        assertThat(training.servingRun()).isNull();
        assertThat(find(workspace, target -> target.queryParam("usable_only", true)).content())
                .containsExactly(usable);
        assertThat(find(workspace, target -> target.queryParam("latest_run_status", "queued")).content())
                .containsExactly(training);
        assertThat(find(workspace, target -> target.queryParam("name", "B-" + suffix.substring(0, 8).toUpperCase()))
                .content())
                .containsExactly(training);
        assertThat(find(newWorkspace(), target -> target).total()).isZero();
    }

    @Test
    @DisplayName("A model and its runs are found in their own workspace only")
    void getWhenAnotherWorkspaceReturnsNotFound() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var run = get(workspace, id).latestRun();

        var other = newWorkspace();
        assertThat(getStatus(other, "/" + id)).isEqualTo(HttpStatus.SC_NOT_FOUND);
        assertThat(getStatus(other, "/" + id + "/runs")).isEqualTo(HttpStatus.SC_NOT_FOUND);
        assertThat(getStatus(other, "/" + id + "/runs/" + run.id())).isEqualTo(HttpStatus.SC_NOT_FOUND);
        try (var response = cancel(other, id, run.id())) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }

        assertThat(getRun(workspace, id, run.id())).isEqualTo(run);
    }

    @Test
    @DisplayName("The description and the config are updated separately, a blank description removes it; a blank prompt or an unknown model is refused")
    void update() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var model = get(workspace, id);
        var description = randomUUID().toString();
        var config = configFor(newName());

        try (var response = request(workspace, "/" + id).method("PATCH",
                Entity.json(GuardrailModelUpdate.builder().description(description).build()))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        assertThat(get(workspace, id))
                .usingRecursiveComparison()
                .ignoringFields("lastUpdatedAt")
                .isEqualTo(model.toBuilder().description(description).build());
        try (var response = request(workspace, "/" + id).method("PATCH",
                Entity.json(GuardrailModelUpdate.builder().config(config).build()))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        assertThat(get(workspace, id))
                .usingRecursiveComparison()
                .ignoringFields("lastUpdatedAt")
                .isEqualTo(model.toBuilder().description(description).config(config).build());
        // A form sends an empty field for a cleared input.
        try (var response = request(workspace, "/" + id).method("PATCH",
                Entity.json(GuardrailModelUpdate.builder().description(" ").build()))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        assertThat(get(workspace, id))
                .usingRecursiveComparison()
                .ignoringFields("lastUpdatedAt")
                .isEqualTo(model.toBuilder().description(null).config(config).build());

        try (var response = request(workspace, "/" + id).method("PATCH",
                Entity.json(GuardrailModelUpdate.builder().config(new GuardrailModelConfig(" ")).build()))) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "config.prompt must not be blank");
        }
        try (var response = request(workspace, "/" + randomUUID()).method("PATCH",
                Entity.json(GuardrailModelUpdate.builder().description(description).build()))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("A model is deleted with its runs; deleting it again does nothing")
    void delete() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var runId = get(workspace, id).latestRun().id();

        try (var response = request(workspace, "/" + id).delete()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }

        assertThat(getStatus(workspace, "/" + id)).isEqualTo(HttpStatus.SC_NOT_FOUND);
        assertThat(getStatus(workspace, "/" + id + "/runs/" + runId)).isEqualTo(HttpStatus.SC_NOT_FOUND);
        try (var response = request(workspace, "/" + id).delete()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    @Test
    @DisplayName("A model a policy rule uses cannot be deleted")
    void deleteWhenReferencedReturnsConflict() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var model = get(workspace, id);
        useInPolicy(workspace, id);

        try (var response = request(workspace, "/" + id).delete()) {
            assertErrors(response, HttpStatus.SC_CONFLICT, "Guardrail model is used by a policy rule");
        }

        assertThat(get(workspace, id)).isEqualTo(model);
    }

    @Test
    @DisplayName("A retrain is a 409 while a run is in flight; once it ends, a new run serves after the completed one")
    void retrain() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());

        try (var response = postRun(workspace, id, VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_CONFLICT, "Guardrail model has a training run in flight");
        }

        var completed = move(workspace, get(workspace, id).latestRun(), COMPLETED);
        var runId = retrain(workspace, id);

        var run = getRun(workspace, id, runId);
        assertThat(run)
                .usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "lastUpdatedAt")
                .isEqualTo(queuedRun(id));
        var model = get(workspace, id);
        assertThat(model.servingRun()).isEqualTo(completed);
        assertThat(model.latestRun()).isEqualTo(run);
        try (var response = postRun(workspace, randomUUID(), VALID_DATASET)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("Invalid training data on a retrain is a 400, and no run is started")
    void retrainWhenDatasetInvalidReturnsBadRequest() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        cancelLatestRun(workspace, id);

        try (var response = postRun(workspace, id, dataset(18, 4))) {
            assertErrors(response, HttpStatus.SC_BAD_REQUEST,
                    "the file needs at least 5 rows per label, label 'no', rows '4'");
        }

        assertThat(getRuns(workspace, id, 1, 10).total()).isOne();
    }

    @Test
    @DisplayName("Of several retrains at once, one starts a run and the others are a 409")
    void retrainWhenConcurrentStartsOneRun() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        cancelLatestRun(workspace, id);
        int retrains = 5;

        List<Integer> statuses;
        try (var executor = Executors.newFixedThreadPool(retrains)) {
            var responses = IntStream.range(0, retrains)
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                        try (var response = postRun(workspace, id, VALID_DATASET)) {
                            return response.getStatus();
                        }
                    }, executor))
                    .toList();
            statuses = responses.stream().map(CompletableFuture::join).toList();
        }

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.SC_CREATED, HttpStatus.SC_CONFLICT,
                HttpStatus.SC_CONFLICT, HttpStatus.SC_CONFLICT, HttpStatus.SC_CONFLICT);
        assertThat(getRuns(workspace, id, 1, 10).total()).isEqualTo(2);
    }

    @Test
    @DisplayName("A model's runs page newest first")
    void findRuns() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var first = get(workspace, id).latestRun().id();
        cancelLatestRun(workspace, id);
        var second = retrain(workspace, id);
        cancelLatestRun(workspace, id);
        var third = retrain(workspace, id);
        var newestFirst = Stream.of(third, second, first).map(runId -> getRun(workspace, id, runId)).toList();

        assertThat(getRuns(workspace, id, 1, 2)).isEqualTo(GuardrailTrainingRunPage.builder()
                .content(newestFirst.subList(0, 2))
                .page(1)
                .size(2)
                .total(3)
                .build());
        assertThat(getRuns(workspace, id, 2, 2)).isEqualTo(GuardrailTrainingRunPage.builder()
                .content(newestFirst.subList(2, 3))
                .page(2)
                .size(1)
                .total(3)
                .build());
    }

    @ParameterizedTest
    @EnumSource(value = GuardrailTrainingRunStatus.class, names = {"QUEUED", "RUNNING"})
    @DisplayName("A run in flight is cancelled with who cancelled it and when; cancelling it again changes nothing")
    void cancelInFlight(GuardrailTrainingRunStatus status) {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var queued = get(workspace, id).latestRun();
        var run = status == QUEUED ? queued : move(workspace, queued, status);

        try (var response = cancel(workspace, id, run.id())) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }

        var cancelled = getRun(workspace, id, run.id());
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(cancelled)
                .usingRecursiveComparison()
                .ignoringFields("finishedAt", "lastUpdatedAt")
                .isEqualTo(run.toBuilder()
                        .status(CANCELLED)
                        .statusMessage("Cancelled by " + USER)
                        .lastUpdatedBy(USER)
                        .build());
        try (var response = cancel(workspace, id, run.id())) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        assertThat(getRun(workspace, id, run.id())).isEqualTo(cancelled);
    }

    @ParameterizedTest
    @EnumSource(value = GuardrailTrainingRunStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("A run that has completed or failed is not cancelled")
    void cancelWhenEndedReturnsConflict(GuardrailTrainingRunStatus status) {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var run = move(workspace, get(workspace, id).latestRun(), status);

        try (var response = cancel(workspace, id, run.id())) {
            assertErrors(response, HttpStatus.SC_CONFLICT, "Guardrail training run has already ended");
        }

        assertThat(getRun(workspace, id, run.id())).isEqualTo(run);
    }

    @Test
    @DisplayName("A run is read and cancelled under its own model only")
    void runOfAnotherModelReturnsNotFound() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var otherId = create(workspace, newName());
        var run = get(workspace, id).latestRun();

        assertThat(getStatus(workspace, "/" + otherId + "/runs/" + run.id())).isEqualTo(HttpStatus.SC_NOT_FOUND);
        try (var response = cancel(workspace, otherId, run.id())) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
        assertThat(getStatus(workspace, "/" + id + "/runs/" + randomUUID())).isEqualTo(HttpStatus.SC_NOT_FOUND);
        try (var response = cancel(workspace, id, randomUUID())) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }

        assertThat(getRun(workspace, id, run.id())).isEqualTo(run);
    }

    @Test
    @DisplayName("The page lists newest first by default, sorts by its dates, ignores an unknown sort field, and pages")
    void findOrderAndPages() {
        var workspace = newWorkspace();
        var firstId = create(workspace, newName());
        var secondId = create(workspace, newName());
        var thirdId = create(workspace, newName());
        try (var response = request(workspace, "/" + firstId).method("PATCH",
                Entity.json(GuardrailModelUpdate.builder().description(randomUUID().toString()).build()))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        var first = get(workspace, firstId);
        var second = get(workspace, secondId);
        var third = get(workspace, thirdId);
        Function<List<SortingField>, Function<WebTarget, WebTarget>> sortedBy = sorting -> target -> target
                .queryParam("sorting", TestUtils.toURLEncodedQueryParam(sorting));

        assertThat(find(workspace, target -> target).content()).containsExactly(third, second, first);
        assertThat(find(workspace, target -> target.queryParam("page", 2).queryParam("size", 2)))
                .isEqualTo(GuardrailModelPage.builder()
                        .content(List.of(first))
                        .page(2)
                        .size(1)
                        .total(3)
                        .sortableBy(List.of("name", "created_at", "last_updated_at"))
                        .build());
        assertThat(find(workspace, sortedBy.apply(List.of(new SortingField("created_at", Direction.ASC))))
                .content())
                .containsExactly(first, second, third);
        assertThat(find(workspace, sortedBy.apply(List.of(new SortingField("last_updated_at", Direction.DESC))))
                .content())
                .containsExactly(first, third, second);
        assertThat(find(workspace, sortedBy.apply(List.of(new SortingField("unknown", Direction.ASC)))).content())
                .containsExactly(third, second, first);
        try (var response = request(workspace,
                client.target(RESOURCE_PATH.formatted(baseURI)).queryParam("sorting", "not json")).get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("LIKE wildcards in the name filter match only themselves")
    void findWhenNameHasWildcards() {
        var workspace = newWorkspace();
        var underscored = get(workspace, create(workspace, "with_underscore-" + randomUUID()));
        create(workspace, "plain-" + randomUUID());

        assertThat(find(workspace, target -> target.queryParam("name", "_")).content()).containsExactly(underscored);
        assertThat(find(workspace, target -> target.queryParam("name", "%")).total()).isZero();
    }

    @Test
    @DisplayName("A page or a size below 1 is a 400, for models and for runs")
    void findWhenPageOrSizeBelowOneIsRejected() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());

        Stream.of("", "/" + id + "/runs").forEach(path -> Stream.of("page", "size").forEach(parameter -> {
            var target = client.target(RESOURCE_PATH.formatted(baseURI) + path).queryParam(parameter, 0);
            try (var response = request(workspace, target).get()) {
                assertErrors(response, HttpStatus.SC_BAD_REQUEST,
                        "query param %s must be greater than or equal to 1".formatted(parameter));
            }
        }));
    }

    @Test
    @DisplayName("A create or a retrain without a file is a 422, and creates nothing")
    void createOrRetrainWithoutFileIsRejected() {
        var workspace = newWorkspace();
        var name = newName();

        try (var response = postModel(workspace, name, randomUUID().toString(), configFor(name), null)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "The request body must not be null");
        }
        assertThat(find(workspace, target -> target).total()).isZero();

        var id = create(workspace, newName());
        cancelLatestRun(workspace, id);
        var run = new FormDataMultiPart().field("note", randomUUID().toString());
        try (var response = upload(workspace, "/" + id + "/runs").post(Entity.entity(run, run.getMediaType()))) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "The request body must not be null");
        }
        assertThat(getRuns(workspace, id, 1, 10).total()).isOne();
    }

    @Test
    @DisplayName("A failed retrain leaves the completed run serving, is the model's latest status, and can be retrained")
    void retrainWhenLatestRunFailed() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var completed = move(workspace, get(workspace, id).latestRun(), COMPLETED);
        var failed = move(workspace, getRun(workspace, id, retrain(workspace, id)), FAILED);

        var model = get(workspace, id);
        assertThat(model.servingRun()).isEqualTo(completed);
        assertThat(model.latestRun()).isEqualTo(failed);
        assertThat(find(workspace, target -> target.queryParam("latest_run_status", "failed")).content())
                .containsExactly(model);
        assertThat(find(workspace, target -> target.queryParam("usable_only", true)).content())
                .containsExactly(model);

        var runId = retrain(workspace, id);
        assertThat(get(workspace, id).latestRun()).isEqualTo(getRun(workspace, id, runId));
    }

    @Test
    @DisplayName("A delete leaves another workspace's model alone, and a rule in another workspace does not hold one")
    void deleteWhenAnotherWorkspace() {
        var workspace = newWorkspace();
        var other = newWorkspace();
        var otherModel = get(other, create(other, newName()));
        var id = create(workspace, newName());
        useInPolicy(other, id);

        try (var response = request(workspace, "/" + otherModel.id()).delete()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        assertThat(get(other, otherModel.id())).isEqualTo(otherModel);

        try (var response = request(workspace, "/" + id).delete()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        assertThat(getStatus(workspace, "/" + id)).isEqualTo(HttpStatus.SC_NOT_FOUND);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"reads", "writes"})
    @DisplayName("A workspace whose organization does not have guardrails gets 403 on every endpoint")
    void whenGuardrailsNotEnabledForOrganizationReturnsForbidden(Call call) {
        var workspace = newWorkspace(false);

        try (var response = send(workspace, call)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                    .isEqualTo("Guardrails is available on the Enterprise plan");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    @DisplayName("Every write needs the workspace settings permission")
    void writeWithoutWorkspaceSettingsPermissionIsForbidden(Call call) {
        var workspace = new Workspace(randomUUID().toString(), randomUUID().toString(), randomUUID().toString());
        AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), workspace.apiKey(), workspace.name(),
                WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE.getValue());

        try (var response = send(workspace, call)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
        }
    }

    @Test
    @DisplayName("A browser session is checked with its own session cookie")
    void sessionCallerIsCheckedWithItsSessionCookie() {
        var sessionToken = randomUUID().toString();
        var workspaceName = randomUUID().toString();
        // AuthTestUtils' session stub answers without a workspace name, which the platform does return.
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/auth-session"))
                .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(USER, randomUUID().toString(),
                        workspaceName, null))));
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features-session"))
                .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                .willReturn(okJson("{\"workspaceName\":\"%s\",\"features\":{\"guardrails\":true}}"
                        .formatted(workspaceName))));

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        }
    }
}
