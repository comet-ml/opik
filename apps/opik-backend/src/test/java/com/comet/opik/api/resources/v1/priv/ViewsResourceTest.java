package com.comet.opik.api.resources.v1.priv;

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
import com.comet.opik.api.resources.utils.resources.ViewsResourceClient;
import com.comet.opik.api.view.View;
import com.comet.opik.api.view.ViewArea;
import com.comet.opik.api.view.ViewAuthor;
import com.comet.opik.api.view.ViewCreate;
import com.comet.opik.api.view.ViewCurrentVersion;
import com.comet.opik.api.view.ViewInput;
import com.comet.opik.api.view.ViewInputKind;
import com.comet.opik.api.view.ViewType;
import com.comet.opik.api.view.ViewUpdate;
import com.comet.opik.api.view.ViewVersion;
import com.comet.opik.api.view.ViewVersionCreate;
import com.comet.opik.api.view.ViewVersionPage;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.view.ViewVersion.MAX_HTML_LENGTH;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ViewsResourceTest {

    private static final String USER = "user-" + UUID.randomUUID();
    private static final String API_KEY_A = UUID.randomUUID().toString();
    private static final String EDITOR = "editor-" + UUID.randomUUID();
    private static final String EDITOR_API_KEY_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_A = UUID.randomUUID().toString();
    private static final String API_KEY_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_B = UUID.randomUUID().toString();
    private static final String[] VERSION_IGNORED_FIELDS = {"createdAt"};
    private static final String HTML = "<div id=\"root\"></div><script>onViewData(function (data) {});</script>";
    private static final String VERSION_NOT_FOUND = "View version not found";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .build());
    }

    private ViewsResourceClient viewsResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        ClientSupportUtils.config(client);
        this.viewsResourceClient = new ViewsResourceClient(client, TestUtils.getBaseUrl(client));

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_A, WORKSPACE_NAME_A, WORKSPACE_ID_A, USER);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_B, WORKSPACE_NAME_B, WORKSPACE_ID_B, USER);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), EDITOR_API_KEY_A, WORKSPACE_NAME_A, WORKSPACE_ID_A,
                EDITOR);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Required permissions")
    class RequiredPermissions {

        static Stream<Arguments> endpoints() {
            var id = UUID.randomUUID();
            var request = widgetRequest();
            var version = versionOf(request);
            return Stream.of(
                    Arguments.of("create view", "", WorkspaceUserPermission.GENERATIVE_UI_CREATE,
                            (Function<Invocation.Builder, Response>) builder -> builder.post(Entity.json(request))),
                    Arguments.of("get view", "/" + id, WorkspaceUserPermission.GENERATIVE_UI_VIEW,
                            (Function<Invocation.Builder, Response>) Invocation.Builder::get),
                    Arguments.of("update view", "/" + id, WorkspaceUserPermission.GENERATIVE_UI_EDIT,
                            (Function<Invocation.Builder, Response>) builder -> builder.method("PATCH",
                                    Entity.json(ViewUpdate.builder().name("Renamed").build()))),
                    Arguments.of("delete view", "/" + id, WorkspaceUserPermission.GENERATIVE_UI_DELETE,
                            (Function<Invocation.Builder, Response>) Invocation.Builder::delete),
                    Arguments.of("create version", "/%s/versions".formatted(id),
                            WorkspaceUserPermission.GENERATIVE_UI_EDIT,
                            (Function<Invocation.Builder, Response>) builder -> builder.post(Entity.json(version))),
                    Arguments.of("get versions", "/%s/versions".formatted(id),
                            WorkspaceUserPermission.GENERATIVE_UI_VIEW,
                            (Function<Invocation.Builder, Response>) Invocation.Builder::get),
                    Arguments.of("get version", "/%s/versions/1".formatted(id),
                            WorkspaceUserPermission.GENERATIVE_UI_VIEW,
                            (Function<Invocation.Builder, Response>) Invocation.Builder::get),
                    Arguments.of("delete version", "/%s/versions/1".formatted(id),
                            WorkspaceUserPermission.GENERATIVE_UI_DELETE,
                            (Function<Invocation.Builder, Response>) Invocation.Builder::delete),
                    Arguments.of("set current version", "/%s/current".formatted(id),
                            WorkspaceUserPermission.GENERATIVE_UI_EDIT,
                            (Function<Invocation.Builder, Response>) builder -> builder
                                    .put(Entity.json(ViewCurrentVersion.builder().version(1).build()))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("endpoints")
        @DisplayName("each view endpoint passes its required permission to the auth endpoint")
        void endpointPassesRequiredPermissionToAuthEndpoint(String endpoint, String path,
                WorkspaceUserPermission permission, Function<Invocation.Builder, Response> call) {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(),
                    USER);

            wireMock.server().resetRequests();
            call.apply(viewsResourceClient.request(path, apiKey, workspaceName)).close();

            wireMock.server().verify(postRequestedFor(urlPathEqualTo("/opik/auth"))
                    .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                    .withRequestBody(matchingJsonPath("$.requiredPermissions",
                            equalToJson("[\"%s\"]".formatted(permission.getValue())))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("endpoints")
        @DisplayName("each view endpoint returns 403 when its permission is denied")
        void endpointReturnsForbiddenWhenPermissionDenied(String endpoint, String path,
                WorkspaceUserPermission permission, Function<Invocation.Builder, Response> call) {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                    permission.getValue());

            try (var response = call.apply(viewsResourceClient.request(path, apiKey, workspaceName))) {
                assertThat(response.getStatus()).isEqualTo(Response.Status.FORBIDDEN.getStatusCode());
            }
        }
    }

    @Nested
    @DisplayName("Create view")
    class CreateView {

        static Stream<Arguments> createViewStoresIt() {
            var request = widgetRequest();
            return Stream.of(
                    Arguments.of("defaults", request),
                    Arguments.of("author and engine version",
                            request.toBuilder().author(ViewAuthor.OLLIE).engineVersion(7).build()),
                    Arguments.of("libraries", request.toBuilder().libraries(Set.of("vega", "d3")).build()),
                    Arguments.of("input without columns", request.toBuilder()
                            .inputs(Map.of("cost", ViewInput.builder().kind(ViewInputKind.QUERY).queryId("q-cost")
                                    .build()))
                            .build()),
                    Arguments.of("html at the size cap", request.toBuilder().html("x".repeat(MAX_HTML_LENGTH)).build()),
                    Arguments.of("empty description", request.toBuilder().description("").build()),
                    Arguments.of("blank description", request.toBuilder().description("  ").build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void createViewStoresIt(String name, ViewCreate request) {
            View created = viewsResourceClient.create(request, API_KEY_A, WORKSPACE_NAME_A);

            assertThat(created)
                    .usingRecursiveComparison()
                    .ignoringFields("createdAt", "lastUpdatedAt", "version.createdAt")
                    .isEqualTo(expectedView(created.id(), request));
            assertThat(viewsResourceClient.get(created.id(), API_KEY_A, WORKSPACE_NAME_A)).isEqualTo(created);
        }

        @Test
        void createViewStoresEachLibraryOnce() {
            ObjectNode request = JsonUtils.getMapper().valueToTree(widgetRequest());
            request.putArray("libraries").add("vega").add("vega");

            try (var response = viewsResourceClient.callCreate(request, API_KEY_A, WORKSPACE_NAME_A)) {
                assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
                assertThat(response.readEntity(View.class).version().libraries()).containsExactly("vega");
            }
        }

        @Test
        void createViewWhenInvalidReturnsTheFailedFields() {
            var request = widgetRequest().toBuilder()
                    .name(" ")
                    .engineVersion(0)
                    .html("x".repeat(MAX_HTML_LENGTH + 1))
                    .inputs(Map.of("1cost", ViewInput.builder().kind(ViewInputKind.QUERY).queryId(" ").build()))
                    .build();

            try (var response = viewsResourceClient.callCreate(request, API_KEY_A, WORKSPACE_NAME_A)) {
                assertThat(response.getStatus()).isEqualTo(422);
                assertThat(response.readEntity(ErrorMessage.class).errors()).containsExactlyInAnyOrder(
                        "name must not be blank",
                        "engineVersion must be greater than or equal to 1",
                        "html size must be between 0 and %d".formatted(MAX_HTML_LENGTH),
                        "inputs<K>[1cost].<map key> must match \"%s\"".formatted(ViewVersion.INPUT_NAME_PATTERN),
                        "inputs[1cost].queryId must not be blank");
            }
        }

        static Stream<Arguments> invalidCreateIsRejected() {
            var request = widgetRequest();
            ObjectNode unknownArea = JsonUtils.getMapper().valueToTree(request);
            unknownArea.put("area", "sidebar");
            ObjectNode noArea = JsonUtils.getMapper().valueToTree(request);
            noArea.remove("area");
            ObjectNode unknownType = JsonUtils.getMapper().valueToTree(request);
            unknownType.put("type", "markdown");
            ObjectNode noInputs = JsonUtils.getMapper().valueToTree(request);
            noInputs.remove("inputs");
            ObjectNode nullInput = JsonUtils.getMapper().valueToTree(request);
            ((ObjectNode) nullInput.get("inputs")).putNull("cost");
            ObjectNode malformedLibrary = JsonUtils.getMapper().valueToTree(request);
            malformedLibrary.putArray("libraries").add("Vega Lite");
            ObjectNode nullLibrary = JsonUtils.getMapper().valueToTree(request);
            nullLibrary.putArray("libraries").add("vega").addNull();
            var tooManyInputs = IntStream.rangeClosed(1, 21).boxed()
                    .collect(Collectors.toMap(i -> "input" + i, i -> queryInput("q" + i)));
            var tooManyLibraries = IntStream.rangeClosed(1, 11).mapToObj(i -> "lib" + i).collect(Collectors.toSet());
            return Stream.of(
                    Arguments.of("blank name", request.toBuilder().name(" ").build(), 422,
                            "name must not be blank"),
                    Arguments.of("name over 150", request.toBuilder().name("n".repeat(151)).build(), 422,
                            "name size must be between 0 and 150"),
                    Arguments.of("description over 1000", request.toBuilder().description("d".repeat(1001)).build(),
                            422, "description size must be between 0 and 1000"),
                    Arguments.of("summary over 500", request.toBuilder().summary("s".repeat(501)).build(), 422,
                            "summary size must be between 0 and 500"),
                    Arguments.of("missing area", noArea, 422, "area must not be null"),
                    Arguments.of("unknown area", unknownArea, 400, "Unknown view area 'sidebar'"),
                    Arguments.of("unknown type", unknownType, 400, "Unknown view type 'markdown'"),
                    Arguments.of("missing inputs", noInputs, 422, "inputs must not be empty"),
                    Arguments.of("no inputs", request.toBuilder().inputs(Map.of()).build(), 422,
                            "inputs must not be empty"),
                    Arguments.of("over 20 inputs", request.toBuilder().inputs(tooManyInputs).build(), 422,
                            "inputs size must be between 0 and 20"),
                    Arguments.of("null input", nullInput, 422, "inputs[cost].<map value> must not be null"),
                    Arguments.of("malformed library name", malformedLibrary, 422,
                            "libraries[].<iterable element> must match \"%s\""
                                    .formatted(ViewVersion.LIBRARY_NAME_PATTERN)),
                    Arguments.of("null library", nullLibrary, 422, "libraries[].<iterable element> must not be null"),
                    Arguments.of("over 10 libraries", request.toBuilder().libraries(tooManyLibraries).build(), 422,
                            "libraries size must be between 0 and 10"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void invalidCreateIsRejected(String name, Object body, int status, String error) {
            try (var response = viewsResourceClient.callCreate(body, API_KEY_A, WORKSPACE_NAME_A)) {
                assertThat(response.getStatus()).isEqualTo(status);
                assertThat(errorOf(response)).contains(error);
            }
        }
    }

    @Nested
    @DisplayName("View versions")
    class ViewVersions {

        @Test
        void versionsAndRollback() {
            ViewCreate request = widgetRequest();
            View view = viewsResourceClient.create(request, API_KEY_A, WORKSPACE_NAME_A);
            var inputs = Map.of("latency", queryInput("q-latency"));

            ViewVersion second = viewsResourceClient.createVersion(view.id(), ViewVersionCreate.builder()
                    .summary("Latency instead of cost")
                    .author(ViewAuthor.AGENT)
                    .type(ViewType.HTML)
                    .engineVersion(1)
                    .inputs(inputs)
                    .html("<p>Latency</p>")
                    .build(), EDITOR_API_KEY_A, WORKSPACE_NAME_A);
            View afterSecond = viewsResourceClient.get(view.id(), API_KEY_A, WORKSPACE_NAME_A);

            assertThat(second)
                    .usingRecursiveComparison()
                    .ignoringFields(VERSION_IGNORED_FIELDS)
                    .isEqualTo(expectedVersion(view.id(), 2, ViewAuthor.AGENT, "Latency instead of cost", 1, inputs,
                            "<p>Latency</p>").toBuilder().createdBy(EDITOR).build());
            assertThat(afterSecond.currentVersion()).isEqualTo(2);
            assertThat(afterSecond.lastUpdatedBy()).isEqualTo(EDITOR);
            assertThat(afterSecond.version()).isEqualTo(second);

            View rolledBack = viewsResourceClient.setCurrent(view.id(), 1, API_KEY_A, WORKSPACE_NAME_A);

            assertThat(rolledBack.currentVersion()).isEqualTo(1);
            assertThat(rolledBack.lastUpdatedBy()).isEqualTo(USER);
            assertThat(rolledBack.version()).isEqualTo(view.version());
            assertThat(viewsResourceClient.getVersion(view.id(), 2, API_KEY_A, WORKSPACE_NAME_A)).isEqualTo(second);

            ViewVersion third = viewsResourceClient.createVersion(view.id(), versionOf(request), API_KEY_A,
                    WORKSPACE_NAME_A);

            assertThat(third.version()).isEqualTo(3);
            assertThat(viewsResourceClient.get(view.id(), API_KEY_A, WORKSPACE_NAME_A).currentVersion())
                    .isEqualTo(3);
            var versions = viewsResourceClient.getVersions(view.id(), API_KEY_A, WORKSPACE_NAME_A);
            assertThat(versions.content())
                    .extracting(ViewVersionPage.Item::version, ViewVersionPage.Item::author,
                            ViewVersionPage.Item::summary, ViewVersionPage.Item::createdBy)
                    .containsExactly(
                            tuple(3, ViewAuthor.USER, null, USER),
                            tuple(2, ViewAuthor.AGENT, "Latency instead of cost", EDITOR),
                            tuple(1, ViewAuthor.USER, request.summary(), USER));
            assertThat(versions.content()).allSatisfy(item -> assertThat(item.createdAt()).isNotNull());
            assertThat(versions)
                    .extracting(ViewVersionPage::page, ViewVersionPage::size, ViewVersionPage::total)
                    .containsExactly(1, 3, 3L);
        }

        @Test
        void getVersionsReturnsTheRequestedPage() {
            ViewCreate request = widgetRequest();
            View view = viewsResourceClient.create(request, API_KEY_A, WORKSPACE_NAME_A);
            viewsResourceClient.createVersion(view.id(), versionOf(request), API_KEY_A, WORKSPACE_NAME_A);
            viewsResourceClient.createVersion(view.id(), versionOf(request), API_KEY_A, WORKSPACE_NAME_A);

            var page = viewsResourceClient.getVersions(view.id(), 2, 2, API_KEY_A, WORKSPACE_NAME_A);

            assertThat(page.content()).extracting(ViewVersionPage.Item::version).containsExactly(1);
            assertThat(page).extracting(ViewVersionPage::page, ViewVersionPage::size, ViewVersionPage::total)
                    .containsExactly(2, 1, 3L);
        }

        @Test
        void getVersionsWhenOffsetExceedsIntRangeReturnsEmptyPage() {
            View view = viewsResourceClient.create(widgetRequest(), API_KEY_A, WORKSPACE_NAME_A);

            var page = viewsResourceClient.getVersions(view.id(), 30000000, 100, API_KEY_A, WORKSPACE_NAME_A);

            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isEqualTo(1);
        }

        @Test
        void createVersionInParallelGivesEachVersionItsOwnNumber() {
            ViewCreate request = widgetRequest();
            View view = viewsResourceClient.create(request, API_KEY_A, WORKSPACE_NAME_A);

            List<Integer> numbers = IntStream.range(0, 5).parallel()
                    .mapToObj(i -> viewsResourceClient
                            .createVersion(view.id(), versionOf(request), API_KEY_A, WORKSPACE_NAME_A)
                            .version())
                    .toList();

            assertThat(numbers).containsExactlyInAnyOrder(2, 3, 4, 5, 6);
            assertThat(viewsResourceClient.getVersions(view.id(), API_KEY_A, WORKSPACE_NAME_A).total())
                    .isEqualTo(6);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  "})
        void blankSummaryIsStoredAsNull(String summary) {
            ViewCreate request = widgetRequest().toBuilder().summary(summary).build();

            View created = viewsResourceClient.create(request, API_KEY_A, WORKSPACE_NAME_A);
            ViewVersion next = viewsResourceClient.createVersion(created.id(),
                    versionOf(request).toBuilder().summary(summary).build(), API_KEY_A, WORKSPACE_NAME_A);

            assertThat(created.version().summary()).isNull();
            assertThat(next.summary()).isNull();
            assertThat(viewsResourceClient.getVersions(created.id(), API_KEY_A, WORKSPACE_NAME_A).content())
                    .extracting(ViewVersionPage.Item::summary)
                    .containsOnlyNulls();
        }

        static Stream<Arguments> missingVersionReturnsNotFound() {
            return Stream.of(
                    Arguments.of("get version",
                            (BiFunction<ViewsResourceClient, UUID, Response>) (client, id) -> client
                                    .callGetVersion(id, 5, API_KEY_A, WORKSPACE_NAME_A)),
                    Arguments.of("set current version",
                            (BiFunction<ViewsResourceClient, UUID, Response>) (client, id) -> client
                                    .callSetCurrent(id, 5, API_KEY_A, WORKSPACE_NAME_A)),
                    Arguments.of("delete version",
                            (BiFunction<ViewsResourceClient, UUID, Response>) (client, id) -> client
                                    .callDeleteVersion(id, 5, API_KEY_A, WORKSPACE_NAME_A)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void missingVersionReturnsNotFound(String name, BiFunction<ViewsResourceClient, UUID, Response> call) {
            View view = viewsResourceClient.create(widgetRequest(), API_KEY_A, WORKSPACE_NAME_A);

            try (var response = call.apply(viewsResourceClient, view.id())) {
                assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
                assertThat(errorOf(response)).isEqualTo(VERSION_NOT_FOUND);
            }
            assertThat(viewsResourceClient.get(view.id(), API_KEY_A, WORKSPACE_NAME_A)).isEqualTo(view);
        }

        static Stream<Arguments> invalidRequestIsRejectedAndChangesNothing() {
            var version = versionOf(widgetRequest());
            ObjectNode noType = JsonUtils.getMapper().valueToTree(version);
            noType.remove("type");
            ObjectNode noEngineVersion = JsonUtils.getMapper().valueToTree(version);
            noEngineVersion.remove("engine_version");
            ObjectNode noHtml = JsonUtils.getMapper().valueToTree(version);
            noHtml.remove("html");
            ObjectNode unknownAuthor = JsonUtils.getMapper().valueToTree(version);
            unknownAuthor.put("author", "robot");
            ObjectNode unknownInputKind = JsonUtils.getMapper().valueToTree(version);
            ((ObjectNode) unknownInputKind.get("inputs").get("cost")).put("kind", "trace");
            ObjectNode malformedLibrary = JsonUtils.getMapper().valueToTree(version);
            malformedLibrary.putArray("libraries").add("Vega Lite");
            ObjectNode nullLibrary = JsonUtils.getMapper().valueToTree(version);
            nullLibrary.putArray("libraries").add("vega").addNull();
            ObjectNode blankColumnType = JsonUtils.getMapper().valueToTree(version);
            ((ObjectNode) blankColumnType.get("inputs").get("cost").get("columns")).put("model", " ");
            ObjectNode noQueryId = JsonUtils.getMapper().valueToTree(version);
            ((ObjectNode) noQueryId.get("inputs").get("cost")).remove("query_id");
            ObjectNode nullInput = JsonUtils.getMapper().valueToTree(version);
            ((ObjectNode) nullInput.get("inputs")).putNull("cost");
            var tooManyInputs = IntStream.rangeClosed(1, 21).boxed()
                    .collect(Collectors.toMap(i -> "input" + i, i -> queryInput("q" + i)));
            var tooManyColumns = IntStream.rangeClosed(1, 201).boxed()
                    .collect(Collectors.toMap(i -> "column" + i, i -> "string"));
            var tooManyLibraries = IntStream.rangeClosed(1, 11).mapToObj(i -> "lib" + i).collect(Collectors.toSet());
            return Stream.of(
                    Arguments.of("version without type", "POST", "/{id}/versions", noType, 422,
                            "type must not be null"),
                    Arguments.of("version without engine version", "POST", "/{id}/versions", noEngineVersion, 422,
                            "engineVersion must not be null"),
                    Arguments.of("version engine version 0", "POST", "/{id}/versions",
                            version.toBuilder().engineVersion(0).build(), 422,
                            "engineVersion must be greater than or equal to 1"),
                    Arguments.of("version without html", "POST", "/{id}/versions", noHtml, 422,
                            "html must not be blank"),
                    Arguments.of("version html over the cap", "POST", "/{id}/versions",
                            version.toBuilder().html("x".repeat(MAX_HTML_LENGTH + 1)).build(), 422,
                            "html size must be between 0 and %d".formatted(MAX_HTML_LENGTH)),
                    Arguments.of("version summary over 500", "POST", "/{id}/versions",
                            version.toBuilder().summary("s".repeat(501)).build(), 422,
                            "summary size must be between 0 and 500"),
                    Arguments.of("version unknown author", "POST", "/{id}/versions", unknownAuthor, 400,
                            "Unknown view author 'robot'"),
                    Arguments.of("version no inputs", "POST", "/{id}/versions",
                            version.toBuilder().inputs(Map.of()).build(), 422, "inputs must not be empty"),
                    Arguments.of("version over 20 inputs", "POST", "/{id}/versions",
                            version.toBuilder().inputs(tooManyInputs).build(), 422,
                            "inputs size must be between 0 and 20"),
                    Arguments.of("version null input", "POST", "/{id}/versions", nullInput, 422,
                            "inputs[cost].<map value> must not be null"),
                    Arguments.of("version input name with a dash", "POST", "/{id}/versions",
                            version.toBuilder().inputs(Map.of("my-cost", queryInput("q"))).build(), 422,
                            "inputs<K>[my-cost].<map key> must match \"%s\"".formatted(ViewVersion.INPUT_NAME_PATTERN)),
                    Arguments.of("version unknown input kind", "POST", "/{id}/versions", unknownInputKind, 400,
                            "Unknown view input kind 'trace'"),
                    Arguments.of("version input without query id", "POST", "/{id}/versions", noQueryId, 422,
                            "inputs[cost].queryId must not be blank"),
                    Arguments.of("version input over 200 columns", "POST", "/{id}/versions",
                            version.toBuilder().inputs(Map.of("cost", queryInput("q").toBuilder()
                                    .columns(tooManyColumns).build())).build(),
                            422, "inputs[cost].columns size must be between 0 and 200"),
                    Arguments.of("version column name over 255", "POST", "/{id}/versions",
                            version.toBuilder().inputs(Map.of("cost", queryInput("q").toBuilder()
                                    .columns(Map.of("c".repeat(256), "string")).build())).build(),
                            422, "size must be between 0 and 255"),
                    Arguments.of("version blank column type", "POST", "/{id}/versions", blankColumnType, 422,
                            "inputs[cost].columns[model].<map value> must not be blank"),
                    Arguments.of("version malformed library name", "POST", "/{id}/versions", malformedLibrary, 422,
                            "libraries[].<iterable element> must match \"%s\""
                                    .formatted(ViewVersion.LIBRARY_NAME_PATTERN)),
                    Arguments.of("version null library", "POST", "/{id}/versions", nullLibrary, 422,
                            "libraries[].<iterable element> must not be null"),
                    Arguments.of("version over 10 libraries", "POST", "/{id}/versions",
                            version.toBuilder().libraries(tooManyLibraries).build(), 422,
                            "libraries size must be between 0 and 10"),
                    Arguments.of("current version 0", "PUT", "/{id}/current",
                            ViewCurrentVersion.builder().version(0).build(), 422,
                            "version must be greater than or equal to 1"),
                    Arguments.of("current version missing", "PUT", "/{id}/current", Map.of(), 422,
                            "version must not be null"),
                    Arguments.of("blank new name", "PATCH", "/{id}", ViewUpdate.builder().name(" ").build(), 422,
                            "name must not be blank"),
                    Arguments.of("new name over 150", "PATCH", "/{id}",
                            ViewUpdate.builder().name("n".repeat(151)).build(), 422,
                            "name size must be between 0 and 150"),
                    Arguments.of("new description over 1000", "PATCH", "/{id}",
                            ViewUpdate.builder().description("d".repeat(1001)).build(), 422,
                            "description size must be between 0 and 1000"),
                    Arguments.of("page 0", "GET", "/{id}/versions?page=0", null, 400,
                            "query param page must be greater than or equal to 1"),
                    Arguments.of("size 0", "GET", "/{id}/versions?size=0", null, 400,
                            "query param size must be greater than or equal to 1"),
                    Arguments.of("size 101", "GET", "/{id}/versions?size=101", null, 400,
                            "query param size must be less than or equal to 100"),
                    Arguments.of("version 0", "GET", "/{id}/versions/0", null, 400,
                            "path param version must be greater than or equal to 1"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void invalidRequestIsRejectedAndChangesNothing(String name, String method, String path, Object body,
                int status, String error) {
            View view = viewsResourceClient.create(widgetRequest(), API_KEY_A, WORKSPACE_NAME_A);
            var builder = viewsResourceClient.request(path.replace("{id}", view.id().toString()), API_KEY_A,
                    WORKSPACE_NAME_A);

            try (var response = body == null ? builder.method(method) : builder.method(method, Entity.json(body))) {
                assertThat(response.getStatus()).isEqualTo(status);
                assertThat(errorOf(response)).contains(error);
            }
            assertThat(viewsResourceClient.get(view.id(), API_KEY_A, WORKSPACE_NAME_A)).isEqualTo(view);
            assertThat(viewsResourceClient.getVersions(view.id(), API_KEY_A, WORKSPACE_NAME_A).total()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Update view")
    class UpdateView {

        static Stream<Arguments> updateViewChangesOnlySentFields() {
            return Stream.of(
                    Arguments.of("name and description",
                            ViewUpdate.builder().name("Renamed").description("New").build(),
                            (UnaryOperator<View.ViewBuilder>) view -> view.name("Renamed").description("New")),
                    Arguments.of("name only", ViewUpdate.builder().name("Renamed").build(),
                            (UnaryOperator<View.ViewBuilder>) view -> view.name("Renamed")),
                    Arguments.of("description only", ViewUpdate.builder().description("New").build(),
                            (UnaryOperator<View.ViewBuilder>) view -> view.description("New")),
                    Arguments.of("empty description clears it", ViewUpdate.builder().description("").build(),
                            (UnaryOperator<View.ViewBuilder>) view -> view.description(null)),
                    Arguments.of("blank description clears it", ViewUpdate.builder().description("  ").build(),
                            (UnaryOperator<View.ViewBuilder>) view -> view.description(null)),
                    Arguments.of("empty body", ViewUpdate.builder().build(),
                            (UnaryOperator<View.ViewBuilder>) view -> view));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void updateViewChangesOnlySentFields(String name, ViewUpdate update, UnaryOperator<View.ViewBuilder> change) {
            View view = viewsResourceClient.create(widgetRequest(), API_KEY_A, WORKSPACE_NAME_A);
            View expected = change.apply(view.toBuilder()).build();

            View updated = viewsResourceClient.update(view.id(), update, EDITOR_API_KEY_A, WORKSPACE_NAME_A);

            if (expected.equals(view)) {
                assertThat(updated).isEqualTo(view);
            } else {
                assertThat(updated)
                        .usingRecursiveComparison()
                        .ignoringFields("lastUpdatedAt")
                        .isEqualTo(expected.toBuilder().lastUpdatedBy(EDITOR).build());
            }
            assertThat(viewsResourceClient.get(view.id(), API_KEY_A, WORKSPACE_NAME_A)).isEqualTo(updated);
        }

        @Test
        void updateViewWhenViewDoesNotExistReturnsNotFound() {
            assertNotFound(viewsResourceClient.callUpdate(UUID.randomUUID(),
                    ViewUpdate.builder().name("Renamed").build(), API_KEY_A, WORKSPACE_NAME_A));
            assertNotFound(viewsResourceClient.callUpdate(UUID.randomUUID(), ViewUpdate.builder().build(), API_KEY_A,
                    WORKSPACE_NAME_A));
        }
    }

    @Nested
    @DisplayName("Delete view")
    class DeleteView {

        @Test
        void deleteView() {
            ViewCreate request = widgetRequest();
            View view = viewsResourceClient.create(request, API_KEY_A, WORKSPACE_NAME_A);

            viewsResourceClient.delete(view.id(), API_KEY_A, WORKSPACE_NAME_A);

            assertNotFound(viewsResourceClient.callGet(view.id(), API_KEY_A, WORKSPACE_NAME_A));
            assertNotFound(viewsResourceClient.callDelete(view.id(), API_KEY_A, WORKSPACE_NAME_A));
            assertNotFound(viewsResourceClient.callSetCurrent(view.id(), 1, API_KEY_A, WORKSPACE_NAME_A));
            assertNotFound(viewsResourceClient.callGetVersions(view.id(), null, null, API_KEY_A, WORKSPACE_NAME_A));
            assertNotFound(viewsResourceClient.callGetVersion(view.id(), 1, API_KEY_A, WORKSPACE_NAME_A));
            assertNotFound(viewsResourceClient.callDeleteVersion(view.id(), 1, API_KEY_A, WORKSPACE_NAME_A));
            assertNotFound(viewsResourceClient.callUpdate(view.id(), ViewUpdate.builder().name("Renamed").build(),
                    API_KEY_A, WORKSPACE_NAME_A));
            assertNotFound(viewsResourceClient.callCreateVersion(view.id(), versionOf(request), API_KEY_A,
                    WORKSPACE_NAME_A));
        }

        @Test
        void deleteViewVersionNeverReusesItsNumber() {
            ViewCreate request = widgetRequest();
            View view = viewsResourceClient.create(request, API_KEY_A, WORKSPACE_NAME_A);
            viewsResourceClient.createVersion(view.id(), versionOf(request), API_KEY_A, WORKSPACE_NAME_A);
            viewsResourceClient.createVersion(view.id(), versionOf(request), API_KEY_A, WORKSPACE_NAME_A);
            viewsResourceClient.setCurrent(view.id(), 1, API_KEY_A, WORKSPACE_NAME_A);

            viewsResourceClient.deleteVersion(view.id(), 3, API_KEY_A, WORKSPACE_NAME_A);
            assertNotFound(viewsResourceClient.callGetVersion(view.id(), 3, API_KEY_A, WORKSPACE_NAME_A));
            assertThat(viewsResourceClient.get(view.id(), API_KEY_A, WORKSPACE_NAME_A).currentVersion())
                    .isEqualTo(1);

            ViewVersion next = viewsResourceClient.createVersion(view.id(), versionOf(request), API_KEY_A,
                    WORKSPACE_NAME_A);

            assertThat(next.version()).isEqualTo(4);
            assertThat(viewsResourceClient.getVersions(view.id(), API_KEY_A, WORKSPACE_NAME_A).content())
                    .extracting(ViewVersionPage.Item::version)
                    .containsExactly(4, 2, 1);
        }

        @Test
        void deleteViewVersionWhenItIsTheCurrentVersionReturnsConflict() {
            View view = viewsResourceClient.create(widgetRequest(), API_KEY_A, WORKSPACE_NAME_A);

            try (var response = viewsResourceClient.callDeleteVersion(view.id(), 1, API_KEY_A, WORKSPACE_NAME_A)) {
                assertThat(response.getStatus()).isEqualTo(Response.Status.CONFLICT.getStatusCode());
                assertThat(response.readEntity(ErrorMessage.class).errors()).containsExactly(
                        "Version 1 is the current version; select another version before deleting it");
            }
        }
    }

    @Nested
    @DisplayName("Workspace isolation")
    class WorkspaceIsolation {

        @Test
        void viewEndpointsFromAnotherWorkspaceReturnNotFoundAndChangeNothing() {
            ViewCreate request = widgetRequest();
            View view = viewsResourceClient.create(request, API_KEY_A, WORKSPACE_NAME_A);
            viewsResourceClient.createVersion(view.id(), versionOf(request), API_KEY_A, WORKSPACE_NAME_A);
            View before = viewsResourceClient.get(view.id(), API_KEY_A, WORKSPACE_NAME_A);
            ViewVersionPage versionsBefore = viewsResourceClient.getVersions(view.id(), API_KEY_A, WORKSPACE_NAME_A);

            assertNotFound(viewsResourceClient.callGet(view.id(), API_KEY_B, WORKSPACE_NAME_B));
            assertNotFound(viewsResourceClient.callGetVersions(view.id(), null, null, API_KEY_B, WORKSPACE_NAME_B));
            assertNotFound(viewsResourceClient.callGetVersion(view.id(), 1, API_KEY_B, WORKSPACE_NAME_B));
            assertNotFound(viewsResourceClient.callUpdate(view.id(), ViewUpdate.builder().name("Renamed").build(),
                    API_KEY_B, WORKSPACE_NAME_B));
            assertNotFound(viewsResourceClient.callCreateVersion(view.id(), versionOf(request), API_KEY_B,
                    WORKSPACE_NAME_B));
            assertNotFound(viewsResourceClient.callSetCurrent(view.id(), 1, API_KEY_B, WORKSPACE_NAME_B));
            assertNotFound(viewsResourceClient.callDeleteVersion(view.id(), 1, API_KEY_B, WORKSPACE_NAME_B));
            assertNotFound(viewsResourceClient.callDelete(view.id(), API_KEY_B, WORKSPACE_NAME_B));

            assertThat(viewsResourceClient.get(view.id(), API_KEY_A, WORKSPACE_NAME_A)).isEqualTo(before);
            assertThat(viewsResourceClient.getVersions(view.id(), API_KEY_A, WORKSPACE_NAME_A))
                    .isEqualTo(versionsBefore);
            assertThat(versionsBefore.content()).extracting(ViewVersionPage.Item::version).containsExactly(2, 1);
        }
    }

    private static View expectedView(UUID id, ViewCreate request) {
        return View.builder()
                .id(id)
                .area(request.area())
                .name(request.name())
                .description(StringUtils.isBlank(request.description()) ? null : request.description())
                .currentVersion(1)
                .createdBy(USER)
                .lastUpdatedBy(USER)
                .version(expectedVersion(id, 1, request.author() == null ? ViewAuthor.USER : request.author(),
                        request.summary(), request.engineVersion(), request.inputs(), request.html())
                        .toBuilder()
                        .libraries(request.libraries())
                        .build())
                .build();
    }

    private static ViewVersion expectedVersion(UUID viewId, int version, ViewAuthor author, String summary,
            int engineVersion, Map<String, ViewInput> inputs, String html) {
        return ViewVersion.builder()
                .viewId(viewId)
                .version(version)
                .author(author)
                .summary(summary)
                .type(ViewType.HTML)
                .engineVersion(engineVersion)
                .inputs(inputs)
                .html(html)
                .createdBy(USER)
                .build();
    }

    private static ViewVersionCreate versionOf(ViewCreate request) {
        return ViewVersionCreate.builder()
                .type(request.type())
                .engineVersion(request.engineVersion())
                .inputs(request.inputs())
                .html(request.html())
                .build();
    }

    private static ViewInput queryInput(String queryId) {
        return ViewInput.builder()
                .kind(ViewInputKind.QUERY)
                .queryId(queryId)
                .columns(Map.of("model", "string", "usd", "number"))
                .build();
    }

    private static ViewCreate widgetRequest() {
        return ViewCreate.builder()
                .area(ViewArea.WIDGET)
                .name("view-" + RandomStringUtils.secure().nextAlphanumeric(10))
                .description("description-" + RandomStringUtils.secure().nextAlphanumeric(10))
                .summary("summary-" + RandomStringUtils.secure().nextAlphanumeric(10))
                .type(ViewType.HTML)
                .engineVersion(1)
                .inputs(Map.of("cost", queryInput("q-cost")))
                .html(HTML)
                .build();
    }

    private static String errorOf(Response response) {
        JsonNode body = response.readEntity(JsonNode.class);
        return body.has("errors") ? body.get("errors").get(0).asText() : body.get("message").asText();
    }

    private static void assertNotFound(Response response) {
        try (response) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        }
    }
}
