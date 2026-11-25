package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.GuardrailExecutionScope;
import com.comet.opik.api.GuardrailModel;
import com.comet.opik.api.GuardrailModel.GuardrailModelPage;
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
import static com.comet.opik.api.GuardrailTrainingRunStatus.QUEUED;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
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

    private UUID create(Workspace workspace, String name) {
        try (var response = postModel(workspace, name, "Flags " + name, VALID_DATASET)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            assertThat(response.hasEntity()).isFalse();
            return TestUtils.getIdFromLocation(response.getLocation());
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
        return upload(workspace, "/" + modelId + "/runs").post(multipart(null, null, dataset));
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

    @Test
    @DisplayName("A model is created with its first run queued, and read with it")
    void createAndGet() {
        var workspace = newWorkspace();
        // Not ASCII: form fields arrive without a charset, as browsers send them.
        var name = "Токсичность " + randomUUID();

        var id = create(workspace, name);

        var runs = getRuns(workspace, id, 1, 10);
        assertThat(runs.content()).hasSize(1);
        var firstRun = runs.content().getFirst();
        assertThat(firstRun)
                .usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "lastUpdatedAt")
                .isEqualTo(queuedRun(id));
        assertThat(get(workspace, id))
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "lastUpdatedAt")
                .isEqualTo(GuardrailModel.builder()
                        .id(id)
                        .name(name)
                        .description("Flags " + name)
                        .latestRun(firstRun)
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build());
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

        try (var response = postModel(workspace, randomUUID().toString(), randomUUID().toString(), dataset)) {
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

        try (var response = postModel(workspace, name.toUpperCase(), randomUUID().toString(), VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_CONFLICT, "Guardrail model already exists");
        }

        assertThat(find(workspace, target -> target).total()).isOne();
    }

    @Test
    @DisplayName("A model without a name or a description is not created")
    void createWhenNameOrDescriptionMissingIsRejected() {
        var workspace = newWorkspace();

        try (var response = postModel(workspace, null, randomUUID().toString(), VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "The request body name must not be blank");
        }
        try (var response = postModel(workspace, randomUUID().toString(), " ", VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY,
                    "The request body description must not be blank");
        }

        assertThat(find(workspace, target -> target).total()).isZero();
    }

    @Test
    @DisplayName("A name or a description at its maximum length is accepted, one character longer is a 422")
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
        UUID id;
        try (var response = postModel(workspace, name, description, VALID_DATASET)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            id = TestUtils.getIdFromLocation(response.getLocation());
        }

        assertThat(get(workspace, id).name()).isEqualTo(name);
        try (var response = request(workspace, "/" + id).method("PATCH",
                Entity.json(new GuardrailModelUpdate(description + "d")))) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "description cannot exceed 1000 characters");
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
    @DisplayName("The description is updated; a blank one or an unknown model is refused")
    void updateDescription() {
        var workspace = newWorkspace();
        var id = create(workspace, newName());
        var model = get(workspace, id);
        var description = randomUUID().toString();

        try (var response = request(workspace, "/" + id).method("PATCH",
                Entity.json(new GuardrailModelUpdate(description)))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        assertThat(get(workspace, id))
                .usingRecursiveComparison()
                .ignoringFields("lastUpdatedAt")
                .isEqualTo(model.toBuilder().description(description).build());

        try (var response = request(workspace, "/" + id).method("PATCH", Entity.json(new GuardrailModelUpdate(" ")))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
        try (var response = request(workspace, "/" + randomUUID()).method("PATCH",
                Entity.json(new GuardrailModelUpdate(description)))) {
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
                    List.of(JsonUtils.createObjectNode().put("model_id", id.toString())), USER);
            return null;
        });

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
    @DisplayName("A workspace whose organization does not have guardrails is refused")
    void whenGuardrailsNotEnabledForOrganizationReturnsForbidden() {
        var workspace = newWorkspace(false);

        try (var response = request(workspace, "").get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
        }
        try (var response = postModel(workspace, randomUUID().toString(), randomUUID().toString(), VALID_DATASET)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                    .isEqualTo("Guardrails is available on the Enterprise plan");
        }
    }
}
