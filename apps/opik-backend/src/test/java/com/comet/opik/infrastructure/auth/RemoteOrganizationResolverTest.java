package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.resources.utils.LogCaptureUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.RedisConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.RetryUtils;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.google.inject.OutOfScopeException;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.forbidden;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteOrganizationResolverTest {

    private static final Duration FRESH_FOR = Duration.ofMinutes(10);
    private static final Duration KEPT_FOR = Duration.ofHours(24);

    private static final String API_KEY_PATH = "/opik/workspace-organization";
    private static final String SESSION_PATH = "/opik/workspace-organization-session";
    private static final String USERNAME_PATH = "/opik/workspace-organization-by-username";

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();
    private final RedissonClient redissonClient;
    private final MovableClock clock = new MovableClock();
    private final WorkspaceOrganizationCache cache;
    private final WorkspaceOrganizationClient client;

    private RequestContext requestContext;
    private RemoteOrganizationResolver resolver;

    private String userName;
    private String workspaceId;
    private String workspaceName;
    private String organizationId;
    private String organizationName;
    private String apiKey;

    {
        Startables.deepStart(redis).join();
        var redisConfig = new RedisConfig();
        redisConfig.setSingleNodeUrl(redis.getRedisURI());
        redissonClient = Redisson.create(redisConfig.build());
        cache = new WorkspaceOrganizationCache(redissonClient.reactive(), FRESH_FOR, KEPT_FOR, clock);
        var httpClient = TestHttpClientUtils.client();
        client = new WorkspaceOrganizationClient(new RetriableHttpClient(httpClient),
                new AuthenticationConfig.UrlConfig(wireMock.server().url("")),
                RetryUtils.handleHttpErrors(0, Duration.ofMillis(250), Duration.ofSeconds(1)),
                Duration.ofSeconds(3));
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
        redissonClient.shutdown();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        userName = RandomStringUtils.secure().nextAlphanumeric(10);
        workspaceId = UUID.randomUUID().toString();
        workspaceName = "workspace-%s".formatted(UUID.randomUUID());
        organizationId = UUID.randomUUID().toString();
        organizationName = RandomStringUtils.secure().nextAlphanumeric(10);
        apiKey = UUID.randomUUID().toString();
        requestContext = request(CometCredentials.apiKey(apiKey));
        resolver = new RemoteOrganizationResolver(() -> requestContext, client, cache, true);
    }

    @Nested
    class RequestOrganization {

        @Test
        void requestThatNeverAsksTouchesNeitherCometNorTheCache() {
            var untouchedCache = mock(WorkspaceOrganizationCache.class);
            resolver = new RemoteOrganizationResolver(() -> requestContext, client, untouchedCache, true);
            stubApiKey(organization(organizationId, false));

            Mono.just("span").contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext)).block();

            assertCometCalls(API_KEY_PATH, 0);
            verifyNoInteractions(untouchedCache);
        }

        @Test
        void coldCacheAsksCometAndRemembersTheAnswer() {
            stubApiKey(organization(organizationId, false));

            assertThat(ask()).isEqualTo(organizationId);

            assertCometCalls(API_KEY_PATH, 1);
            StepVerifier.create(cache.find(workspaceId).map(WorkspaceOrganizationCache.Entry::organizationId))
                    .expectNext(organizationId)
                    .verifyComplete();
        }

        @Test
        void oneRequestAsksAtMostOnce() {
            stubApiKey(organization(organizationId, false));
            assertThat(ask()).isEqualTo(organizationId);
            wireMock.server().resetAll();
            stubApiKey(organization(UUID.randomUUID().toString(), false));
            clock.advance(FRESH_FOR);

            assertThat(ask()).isEqualTo(organizationId);

            assertCometCalls(API_KEY_PATH, 0);
        }

        @Test
        void lookupOffTheRequestThreadStillUsesTheCallersCredential() {
            stubApiKey(organization(organizationId, false));
            resolver = new RemoteOrganizationResolver(() -> {
                throw new OutOfScopeException("Not in a request");
            }, client, cache, true);

            var found = Mono.just("span")
                    .publishOn(Schedulers.boundedElastic())
                    .flatMap(unused -> resolver.organizationId())
                    .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext))
                    .block();

            assertThat(found).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void warmCacheAnswersWithoutComet() {
            stubApiKey(organization(organizationId, false));
            ask();

            requestContext = request(CometCredentials.apiKey(apiKey));
            clock.advance(FRESH_FOR.minusSeconds(1));

            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void staleCacheAsksCometAgain() {
            stubApiKey(organization(organizationId, false));
            ask();
            var movedOrganizationId = UUID.randomUUID().toString();
            wireMock.server().resetAll();
            stubApiKey(organization(movedOrganizationId, false));

            requestContext = request(CometCredentials.apiKey(apiKey));
            clock.advance(FRESH_FOR);

            assertThat(ask()).isEqualTo(movedOrganizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        static Stream<Arguments> answersWithoutAnOrganization() {
            return Stream.of(
                    arguments("404", given(test -> notFound()), true),
                    arguments("an organization with a blank ID", given(test -> test.organization("", false)), true),
                    arguments("Comet's unknown-workspace 400", given(test -> cometError(400, "No such workspace!")),
                            true),
                    arguments("another 400 from Comet",
                            given(test -> cometError(400, RandomStringUtils.secure().nextAlphabetic(20))), false),
                    arguments("a 400 without Comet's error body", given(test -> badRequest()), false),
                    arguments("401", given(test -> unauthorized()), false),
                    arguments("a server error", given(test -> serverError()), false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("answersWithoutAnOrganization")
        void answerWithoutAnOrganizationLeavesNoneAndIsRememberedOnlyWhenItMeansNone(String name,
                Given<ResponseDefinitionBuilder> answer, boolean remembered) {
            stubApiKey(answer.of(RemoteOrganizationResolverTest.this));

            assertThat(ask()).isNull();

            requestContext = request(CometCredentials.apiKey(apiKey));
            assertThat(ask()).isNull();
            assertCometCalls(API_KEY_PATH, remembered ? 1 : 2);
        }

        @Test
        void cometErrorKeepsTheStaleOrganizationAndWaitsBeforeAskingAgain() {
            stubApiKey(organization(organizationId, false));
            ask();
            wireMock.server().resetAll();
            stubApiKey(serverError());
            clock.advance(FRESH_FOR);

            requestContext = request(CometCredentials.apiKey(apiKey));
            assertThat(ask()).isEqualTo(organizationId);

            requestContext = request(CometCredentials.apiKey(apiKey));
            clock.advance(WorkspaceOrganizationCache.RETRY_AFTER_FAILURE.minusSeconds(1));

            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        static Stream<Arguments> cometFailures() {
            return Stream.of(
                    arguments("a server error, with its status", serverError(),
                            "error type 'UnexpectedResponseException', status '500'"),
                    arguments("a connection failure, without a status",
                            aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER), "error type 'ProcessingException'"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cometFailures")
        void cometFailureIsLoggedAsOneLine(String name, ResponseDefinitionBuilder failure, String detail) {
            stubApiKey(failure);

            var logs = LogCaptureUtils.logEventsOf(() -> ask());

            assertThat(logs).filteredOn(line -> line.startsWith("Comet organization call failed"))
                    .containsExactly(
                            "Comet organization call failed, operation 'organization lookup', workspace '%s', %s"
                                    .formatted(workspaceId, detail));
        }

        @Test
        void aBadRequestWithAnUnreadableBodyLogsWhyItWasUnreadable() {
            stubApiKey(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                    .withBody("not json"));

            var logs = LogCaptureUtils.logEventsOf(() -> ask());

            assertThat(logs).filteredOn(line -> line.startsWith("Comet answered '400' with a body"))
                    .singleElement()
                    .satisfies(line -> assertThat(line).contains("ProcessingException"));
        }

        static Stream<Arguments> answersAfterAStaleEntry() {
            return Stream.of(
                    arguments("404: Comet no longer reports one", given(test -> notFound()), false, 1),
                    arguments("403: the caller is denied", given(test -> forbidden()), true, 2),
                    arguments("401: the caller is denied", given(test -> unauthorized()), true, 2));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("answersAfterAStaleEntry")
        void staleOrganizationIsKeptOnlyWhenCometDeniesTheCaller(String name, Given<ResponseDefinitionBuilder> answer,
                boolean keepsStale, int cometCalls) {
            stubApiKey(organization(organizationId, false));
            ask();
            wireMock.server().resetAll();
            stubApiKey(answer.of(RemoteOrganizationResolverTest.this));
            clock.advance(FRESH_FOR);
            var expected = keepsStale ? organizationId : null;

            requestContext = request(CometCredentials.apiKey(apiKey));
            assertThat(ask()).isEqualTo(expected);

            requestContext = request(CometCredentials.apiKey(apiKey));
            assertThat(ask()).isEqualTo(expected);
            assertCometCalls(API_KEY_PATH, cometCalls);
        }

        @Test
        void requestWithoutCometCredentialsOnlyReadsTheCache() {
            requestContext = request(null);

            assertThat(ask()).isNull();
            assertThat(wireMock.server().getAllServeEvents()).isEmpty();
        }

        @Test
        void defaultWorkspaceNeverAsksComet() {
            requestContext = request(CometCredentials.apiKey(apiKey)).toBuilder()
                    .workspaceName(ProjectService.DEFAULT_WORKSPACE_NAME)
                    .build();

            assertThat(ask()).isNull();
            assertThat(wireMock.server().getAllServeEvents()).isEmpty();
        }

        static Stream<Arguments> credentials() {
            return Stream.of(
                    arguments("API key", CometCredentials.Kind.API_KEY, API_KEY_PATH),
                    arguments("session", CometCredentials.Kind.SESSION, SESSION_PATH),
                    arguments("OAuth username", CometCredentials.Kind.USERNAME, USERNAME_PATH));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("credentials")
        void eachCredentialAsksItsOwnEndpoint(String name, CometCredentials.Kind kind, String path) {
            var value = UUID.randomUUID().toString();
            var stub = post(urlPathEqualTo(path))
                    .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)));
            stub = switch (kind) {
                case API_KEY -> stub.withHeader(HttpHeaders.AUTHORIZATION, equalTo(value));
                case SESSION -> stub.withCookie(RequestContext.SESSION_COOKIE, equalTo(value));
                case USERNAME -> stub.withHeader(OAUTH_USERNAME_HEADER, equalTo(value));
            };
            wireMock.server().stubFor(stub.willReturn(organization(organizationId, false)));
            requestContext = request(new CometCredentials(kind, value));

            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(path, 1);
        }
    }

    @Nested
    class OrganizationId {

        @Test
        void jobCarryingAnOrganizationUsesIt() {
            StepVerifier.create(resolver.organizationId().contextWrite(job(organizationId)))
                    .expectNext(organizationId)
                    .verifyComplete();
        }

        @Test
        void jobWithoutAnOrganizationFindsItInTheCache() {
            stubApiKey(organization(organizationId, false));
            ask();
            clock.advance(KEPT_FOR.minusMinutes(1));

            StepVerifier.create(resolver.organizationId().contextWrite(job(null)))
                    .expectNext(organizationId)
                    .verifyComplete();
            assertCometCalls(API_KEY_PATH, 1);
        }

        static Stream<Arguments> contextsWithoutAnOrganization() {
            return Stream.of(
                    arguments("a job without one, with nothing cached", given(test -> test.job(null))),
                    arguments("a context holding the default organization",
                            given(test -> test.job(ProjectService.DEFAULT_ORGANIZATION_ID))),
                    arguments("no context", given(test -> Context.empty())));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("contextsWithoutAnOrganization")
        void hasNoneWithoutAskingComet(String name, Given<Context> context) {
            StepVerifier.create(resolver.organizationId().contextWrite(context.of(RemoteOrganizationResolverTest.this)))
                    .verifyComplete();
            assertThat(wireMock.server().getAllServeEvents()).isEmpty();
        }
    }

    @Nested
    class RequireOrganizationAdmin {

        @Test
        void adminGetsTheOrganization() {
            stubApiKey(organization(organizationId, true));

            StepVerifier.create(resolver.requireOrganizationAdmin())
                    .expectNext(organizationId)
                    .verifyComplete();
        }

        static Stream<Arguments> refusals() {
            return Stream.of(
                    arguments("a member who isn't an admin",
                            given(test -> test.organization(test.organizationId, false)),
                            RemoteOrganizationResolver.NOT_ORGANIZATION_ADMIN),
                    arguments("a workspace without an organization", given(test -> notFound()),
                            RemoteOrganizationResolver.UNKNOWN_ORGANIZATION),
                    arguments("a caller Comet denies", given(test -> forbidden()),
                            RemoteOrganizationResolver.UNKNOWN_ORGANIZATION));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("refusals")
        void isRefusedWith403(String name, Given<ResponseDefinitionBuilder> answer, String message) {
            stubApiKey(answer.of(RemoteOrganizationResolverTest.this));

            StepVerifier.create(resolver.requireOrganizationAdmin())
                    .expectErrorSatisfies(error -> assertForbidden(error, message))
                    .verify();
        }

        @Test
        void everyCheckAsksComet() {
            stubApiKey(organization(organizationId, true));
            ask();

            StepVerifier.create(resolver.requireOrganizationAdmin().then(resolver.requireOrganizationAdmin()))
                    .expectNext(organizationId)
                    .verifyComplete();

            assertCometCalls(API_KEY_PATH, 3);
        }

        @Test
        void cometErrorIsServiceUnavailable() {
            stubApiKey(serverError());

            StepVerifier.create(resolver.requireOrganizationAdmin())
                    .expectErrorSatisfies(error -> {
                        assertThat(error)
                                .isExactlyInstanceOf(ServiceUnavailableException.class)
                                .hasMessage(RemoteOrganizationResolver.ADMIN_CHECK_FAILED);
                        assertThat(((ServiceUnavailableException) error).getResponse().getStatus())
                                .isEqualTo(503);
                    })
                    .verify();
        }

        @Test
        void cometErrorOnTheAdminCheckIsLoggedAsOneLineWithItsStatus() {
            stubApiKey(serverError());

            var logs = LogCaptureUtils.logEventsOf(
                    () -> StepVerifier.create(resolver.requireOrganizationAdmin()).expectError().verify());

            assertThat(logs).filteredOn(line -> line.startsWith("Comet organization call failed"))
                    .containsExactly("""
                            Comet organization call failed, operation 'admin check', workspace '%s', \
                            error type 'UnexpectedResponseException', status '500'\
                            """.formatted(workspaceId));
        }

        @Test
        void callerWithoutCometCredentialsIsRefusedWithoutAskingComet() {
            requestContext = request(null);

            StepVerifier.create(resolver.requireOrganizationAdmin())
                    .expectErrorSatisfies(error -> assertForbidden(error,
                            RemoteOrganizationResolver.UNKNOWN_ORGANIZATION))
                    .verify();
            assertThat(wireMock.server().getAllServeEvents()).isEmpty();
        }

        @Test
        void jobOutsideARequestIsRefusedWithoutAskingComet() {
            var jobResolver = new RemoteOrganizationResolver(() -> {
                throw new OutOfScopeException("Not in a request");
            }, client, cache, true);

            StepVerifier.create(jobResolver.requireOrganizationAdmin().contextWrite(job(organizationId)))
                    .expectErrorSatisfies(error -> assertForbidden(error,
                            RemoteOrganizationResolver.UNKNOWN_ORGANIZATION))
                    .verify();
            assertThat(wireMock.server().getAllServeEvents()).isEmpty();
        }
    }

    @Nested
    class ModelPricesOff {

        private final WorkspaceOrganizationCache untouchedCache = mock(WorkspaceOrganizationCache.class);

        private RemoteOrganizationResolver offResolver() {
            return new RemoteOrganizationResolver(() -> requestContext, client, untouchedCache, false);
        }

        @Test
        void requestMakesNoCometCallAndGetsNoOrganization() {
            stubApiKey(organization(organizationId, false));

            var found = offResolver().organizationId()
                    .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext))
                    .block();

            assertThat(found).isNull();
            assertCometCalls(API_KEY_PATH, 0);
            verifyNoInteractions(untouchedCache);
        }

        @ParameterizedTest(name = "carries an organization: {0}")
        @ValueSource(booleans = {false, true})
        void jobUsesOnlyTheOrganizationItCarries(boolean carriesOrganization) {
            var carried = carriesOrganization ? organizationId : null;

            StepVerifier.create(offResolver().organizationId().contextWrite(job(carried)))
                    .expectNextSequence(Stream.ofNullable(carried).toList())
                    .verifyComplete();
            verifyNoInteractions(untouchedCache);
        }
    }

    private RequestContext request(CometCredentials credentials) {
        return RequestContext.builder()
                .userName(userName)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .cometCredentials(credentials)
                .organizationLookup(credentials == null ? null : new RequestOrganizationLookup(credentials))
                .build();
    }

    private String ask() {
        return resolver.organizationId()
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext))
                .block();
    }

    private Context job(String jobOrganizationId) {
        var context = Context.of(RequestContext.USER_NAME, userName, RequestContext.WORKSPACE_ID, workspaceId);
        return jobOrganizationId == null ? context : context.put(RequestContext.ORGANIZATION_ID, jobOrganizationId);
    }

    private static ResponseDefinitionBuilder cometError(int status, String msg) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"msg\": \"%s\", \"code\": %d}".formatted(msg, status));
    }

    private void stubApiKey(ResponseDefinitionBuilder response) {
        MappingBuilder stub = post(urlPathEqualTo(API_KEY_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(apiKey))
                .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)));
        wireMock.server().stubFor(stub.willReturn(response));
    }

    private ResponseDefinitionBuilder organization(String id, boolean isAdmin) {
        return okJson("""
                {"workspaceId": "%s", "workspaceName": "%s", "organizationId": "%s",
                 "organizationName": "%s", "isOrganizationAdmin": %s}
                """.formatted(workspaceId, workspaceName, id, organizationName, isAdmin));
    }

    private void assertCometCalls(String path, int count) {
        wireMock.server().verify(count, postRequestedFor(urlPathEqualTo(path)));
    }

    private static void assertForbidden(Throwable error, String message) {
        assertThat(error).isExactlyInstanceOf(ForbiddenException.class).hasMessage(message);
        assertThat(((ForbiddenException) error).getResponse().getStatus()).isEqualTo(403);
    }

    @FunctionalInterface
    private interface Given<T> {
        T of(RemoteOrganizationResolverTest test);
    }

    private static <T> Given<T> given(Given<T> given) {
        return given;
    }

    private static final class MovableClock extends Clock {

        private Instant now = Instant.now();

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
