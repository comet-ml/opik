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
import com.comet.opik.domain.GuardrailTrainingRunDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.utils.JsonUtils;
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
import org.jdbi.v3.core.Jdbi;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
    private static final List<String> RUN_FIELDS_NOT_IN_THE_API = List.of("workspaceId", "metadata");

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
    private GuardrailTrainingRunDAO runs;
    private TransactionTemplate template;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi, TransactionTemplate template) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        multipartClient = ClientBuilder.newClient().register(MultiPartFeature.class);
        // The job side of a run is not wired yet, so the tests move runs in MySQL the way it will. Reads go straight
        // to the app's Jdbi; writes go through its TransactionTemplate, because a handle borrowed outside one can be a
        // pooled connection an earlier read-only transaction left read-only.
        runs = jdbi.onDemand(GuardrailTrainingRunDAO.class);
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

    /** Moves the model's run in flight to the status, as its job will once training runs on opik-inference. */
    private GuardrailTrainingRun end(Workspace workspace, UUID modelId, GuardrailTrainingRunStatus status) {
        var run = runs.findInFlight(modelId, workspace.id()).orElseThrow();
        int updated = template.inTransaction(WRITE, handle -> handle.attach(GuardrailTrainingRunDAO.class)
                .updateStatus(run.id(), workspace.id(), run.status(), status, null, null, USER));
        assertThat(updated).isOne();
        return runs.findById(run.id(), workspace.id()).orElseThrow();
    }

    private static void assertRun(GuardrailTrainingRun actual, GuardrailTrainingRun expected) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(RUN_FIELDS_NOT_IN_THE_API.toArray(String[]::new))
                .isEqualTo(expected);
    }

    private static void assertErrors(Response response, int status, String... errors) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.readEntity(com.comet.opik.api.error.ErrorMessage.class).errors())
                .containsExactly(errors);
    }

    @Test
    @DisplayName("A model is created with its first run queued, and read with it")
    void createAndGet() {
        var workspace = newWorkspace();
        // Not ASCII: form fields arrive without a charset, as browsers send them.
        var name = "Токсичность " + randomUUID();

        var id = create(workspace, name);

        var model = get(workspace, id);
        var firstRun = runs.findInFlight(id, workspace.id()).orElseThrow();
        assertThat(model)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "lastUpdatedAt", "latestRun")
                .isEqualTo(GuardrailModel.builder()
                        .id(id)
                        .name(name)
                        .description("Flags " + name)
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build());
        assertRun(model.latestRun(), firstRun);
        assertThat(firstRun.status()).isEqualTo(QUEUED);
        assertThat(firstRun.attempt()).isOne();
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

        try (var response = postModel(workspace, "model", "Flags it", dataset)) {
            assertErrors(response, HttpStatus.SC_BAD_REQUEST, errors.toArray(String[]::new));
        }

        assertThat(find(workspace, target -> target).total()).isZero();
    }

    @Test
    @DisplayName("A name the workspace has, ignoring case, is a 409")
    void createWhenNameTakenReturnsConflict() {
        var workspace = newWorkspace();
        create(workspace, "Toxicity");

        try (var response = postModel(workspace, "toxicity", "Flags it", VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_CONFLICT, "Guardrail model already exists");
        }
    }

    @Test
    @DisplayName("A model without a name or a description is not created")
    void createWhenNameOrDescriptionMissingIsRejected() {
        var workspace = newWorkspace();

        try (var response = postModel(workspace, null, "Flags it", VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, "The request body name must not be blank");
        }
        try (var response = postModel(workspace, "model", " ", VALID_DATASET)) {
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

        try (var response = postModel(workspace, name + "n", "Flags it", VALID_DATASET)) {
            assertErrors(response, HttpStatus.SC_UNPROCESSABLE_ENTITY,
                    "The request body name cannot exceed 255 characters");
        }
        try (var response = postModel(workspace, "model", description + "d", VALID_DATASET)) {
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
        var usable = create(workspace, "alpha");
        var completed = end(workspace, usable, COMPLETED);
        var training = create(workspace, "beta");

        var page = find(workspace, target -> target.queryParam("sorting",
                TestUtils.toURLEncodedQueryParam(List.of(new SortingField("name", Direction.DESC)))));

        assertThat(page.content()).extracting(GuardrailModel::id).containsExactly(training, usable);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.sortableBy()).containsExactly("name", "created_at", "last_updated_at");
        assertRun(page.content().getLast().servingRun(), completed);
        assertThat(page.content().getFirst().servingRun()).isNull();
        assertThat(find(workspace, target -> target.queryParam("usable_only", true)).content())
                .extracting(GuardrailModel::id).containsExactly(usable);
        assertThat(find(workspace, target -> target.queryParam("latest_run_status", "queued")).content())
                .extracting(GuardrailModel::id).containsExactly(training);
        assertThat(find(workspace, target -> target.queryParam("name", "ET")).content())
                .extracting(GuardrailModel::id).containsExactly(training);
        assertThat(find(newWorkspace(), target -> target).total()).isZero();
    }

    @Test
    @DisplayName("A model is found in its own workspace only")
    void getWhenAnotherWorkspaceReturnsNotFound() {
        var id = create(newWorkspace(), "Toxicity");

        var other = newWorkspace();
        assertThat(getStatus(other, "/" + id)).isEqualTo(HttpStatus.SC_NOT_FOUND);
        assertThat(getStatus(other, "/" + id + "/runs")).isEqualTo(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("The description is updated; a blank one or an unknown model is refused")
    void updateDescription() {
        var workspace = newWorkspace();
        var id = create(workspace, "Toxicity");

        try (var response = request(workspace, "/" + id).method("PATCH",
                Entity.json(new GuardrailModelUpdate("Flags insults")))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        assertThat(get(workspace, id).description()).isEqualTo("Flags insults");

        try (var response = request(workspace, "/" + id).method("PATCH", Entity.json(new GuardrailModelUpdate(" ")))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
        try (var response = request(workspace, "/" + randomUUID()).method("PATCH",
                Entity.json(new GuardrailModelUpdate("Flags insults")))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("A model is deleted with its runs")
    void delete() {
        var workspace = newWorkspace();
        var id = create(workspace, "Toxicity");

        try (var response = request(workspace, "/" + id).delete()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }

        assertThat(getStatus(workspace, "/" + id)).isEqualTo(HttpStatus.SC_NOT_FOUND);
        assertThat(runs.countByModel(id, workspace.id())).isZero();
    }

    @Test
    @DisplayName("A model a policy rule uses cannot be deleted")
    void deleteWhenReferencedReturnsConflict() {
        var workspace = newWorkspace();
        var id = create(workspace, "Toxicity");
        // The policies API does not take custom classifier rules yet, so the rule is written directly.
        var policy = GuardrailPolicy.builder()
                .id(randomUUID())
                .name("policy")
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
        assertThat(getStatus(workspace, "/" + id)).isEqualTo(HttpStatus.SC_OK);
    }

    @Test
    @DisplayName("A retrain is a 409 while a run is in flight; once it ends, a new run serves after the completed one")
    void retrain() {
        var workspace = newWorkspace();
        var id = create(workspace, "Toxicity");

        try (var response = upload(workspace, "/" + id + "/runs").post(multipart(null, null, VALID_DATASET))) {
            assertErrors(response, HttpStatus.SC_CONFLICT, "Guardrail model has a training run in flight");
        }

        var completed = end(workspace, id, COMPLETED);
        UUID runId;
        try (var response = upload(workspace, "/" + id + "/runs").post(multipart(null, null, VALID_DATASET))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            runId = TestUtils.getIdFromLocation(response.getLocation());
        }

        var model = get(workspace, id);
        assertRun(model.servingRun(), completed);
        assertRun(model.latestRun(), runs.findById(runId, workspace.id()).orElseThrow());
        try (var response = upload(workspace, "/" + id + "/runs").post(multipart(null, null, dataset(1, 1)))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }
        try (var response = upload(workspace, "/" + randomUUID() + "/runs")
                .post(multipart(null, null, VALID_DATASET))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("A model's runs page newest first, one is read by id, and a run in flight is cancelled once")
    void runs() {
        var workspace = newWorkspace();
        var id = create(workspace, "Toxicity");
        var first = end(workspace, id, COMPLETED);
        UUID secondId;
        try (var response = upload(workspace, "/" + id + "/runs").post(multipart(null, null, VALID_DATASET))) {
            secondId = TestUtils.getIdFromLocation(response.getLocation());
        }

        GuardrailTrainingRunPage page;
        try (var response = request(workspace, "/" + id + "/runs").get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            page = response.readEntity(GuardrailTrainingRunPage.class);
        }
        assertThat(page.content()).extracting(GuardrailTrainingRun::id).containsExactly(secondId, first.id());
        assertThat(page.total()).isEqualTo(2);

        try (var response = request(workspace, "/" + id + "/runs/" + secondId + "/cancel").post(Entity.json(""))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
        GuardrailTrainingRun cancelled;
        try (var response = request(workspace, "/" + id + "/runs/" + secondId).get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            cancelled = response.readEntity(GuardrailTrainingRun.class);
        }
        assertThat(cancelled.status()).isEqualTo(CANCELLED);
        assertThat(cancelled.statusMessage()).isEqualTo("Cancelled by " + USER);

        try (var response = request(workspace, "/" + id + "/runs/" + secondId + "/cancel").post(Entity.json(""))) {
            assertErrors(response, HttpStatus.SC_CONFLICT, "Guardrail training run has already ended");
        }
        assertThat(getStatus(workspace, "/" + id + "/runs/" + randomUUID())).isEqualTo(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("A workspace whose organization does not have guardrails is refused")
    void whenGuardrailsNotEnabledForOrganizationReturnsForbidden() {
        var workspace = newWorkspace(false);

        assertThat(getStatus(workspace, "")).isEqualTo(HttpStatus.SC_FORBIDDEN);
        try (var response = postModel(workspace, "Toxicity", "Flags it", VALID_DATASET)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            assertThat(response.readEntity(ErrorMessage.class).getMessage())
                    .isEqualTo("Guardrails is available on the Enterprise plan");
        }
    }
}
