package com.comet.opik.api.resources.v1.priv;

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
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Guardrails run on this deployment, but serviceToggles.guardrailsTrainingEnabled is off, as config-test.yml sets it. */
@DisplayName("Guardrail Models Resource Test with the training toggle off")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class GuardrailModelsToggleOffResourceTest {

    private static final String RESOURCE_PATH = "%s/v1/private/guardrails/models";
    private static final String API_KEY = randomUUID().toString();
    private static final String WORKSPACE_NAME = randomUUID().toString();

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
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, randomUUID().toString(),
                randomUUID().toString());
    }

    @AfterAll
    void tearDownAll() {
        multipartClient.close();
        wireMock.server().stop();
    }

    private Invocation.Builder request(String path) {
        return withCredentials(client.target(RESOURCE_PATH.formatted(baseURI) + path).request());
    }

    private Invocation.Builder upload(String path) {
        return withCredentials(multipartClient.target(RESOURCE_PATH.formatted(baseURI) + path).request());
    }

    private static Invocation.Builder withCredentials(Invocation.Builder request) {
        return request
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, WORKSPACE_NAME);
    }

    private static Entity<FormDataMultiPart> multipart() {
        var multiPart = new FormDataMultiPart().field("name", "Toxicity").field("description", "Flags it");
        multiPart.bodyPart(new FormDataBodyPart("file",
                new ByteArrayInputStream("{\"text\": \"t\", \"label\": \"yes\"}".getBytes(StandardCharsets.UTF_8)),
                MediaType.APPLICATION_OCTET_STREAM_TYPE));
        return Entity.entity(multiPart, multiPart.getMediaType());
    }

    Stream<Arguments> endpoints() {
        var id = "/" + randomUUID();
        var runId = "/runs/" + randomUUID();
        return Stream.of(
                Arguments.of("create", (Function<GuardrailModelsToggleOffResourceTest, Response>) test -> test
                        .upload("").post(multipart())),
                Arguments.of("find",
                        (Function<GuardrailModelsToggleOffResourceTest, Response>) test -> test.request("").get()),
                Arguments.of("get by id",
                        (Function<GuardrailModelsToggleOffResourceTest, Response>) test -> test.request(id).get()),
                Arguments.of("update", (Function<GuardrailModelsToggleOffResourceTest, Response>) test -> test
                        .request(id).method("PATCH", Entity.json(new GuardrailModelUpdate("Flags it")))),
                Arguments.of("delete",
                        (Function<GuardrailModelsToggleOffResourceTest, Response>) test -> test.request(id)
                                .delete()),
                Arguments.of("retrain", (Function<GuardrailModelsToggleOffResourceTest, Response>) test -> test
                        .upload(id + "/runs").post(multipart())),
                Arguments.of("find runs",
                        (Function<GuardrailModelsToggleOffResourceTest, Response>) test -> test
                                .request(id + "/runs").get()),
                Arguments.of("get run",
                        (Function<GuardrailModelsToggleOffResourceTest, Response>) test -> test.request(id + runId)
                                .get()),
                Arguments.of("cancel run",
                        (Function<GuardrailModelsToggleOffResourceTest, Response>) test -> test
                                .request(id + runId + "/cancel").post(Entity.json(""))));
    }

    @Test
    @DisplayName("while the training toggle is off, no guardrail model endpoint asks the platform about the organization")
    void noEndpointAsksThePlatformWhileTheToggleIsOff() {
        endpoints().forEach(arguments -> {
            @SuppressWarnings("unchecked")
            var call = (Function<GuardrailModelsToggleOffResourceTest, Response>) arguments.get()[1];
            try (var response = call.apply(this)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            }
        });

        wireMock.server().verify(0, postRequestedFor(urlPathMatching("/opik/workspace-features.*")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("every guardrail model endpoint answers 403 while the training toggle is off")
    void endpointIsForbiddenWhileTheToggleIsOff(String endpoint,
            Function<GuardrailModelsToggleOffResourceTest, Response> call) {
        try (var response = call.apply(this)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            assertThat(response.readEntity(ErrorMessage.class).getMessage())
                    .isEqualTo("Guardrail model training is not enabled");
        }
    }
}
