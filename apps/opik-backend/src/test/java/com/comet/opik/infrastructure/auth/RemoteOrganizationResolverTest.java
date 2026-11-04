package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.RedisConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.RetryUtils;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.google.inject.OutOfScopeException;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.testcontainers.lifecycle.Startables;
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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
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

    private String workspaceId;
    private String workspaceName;
    private String organizationId;
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
        workspaceId = UUID.randomUUID().toString();
        workspaceName = "workspace-" + UUID.randomUUID();
        organizationId = UUID.randomUUID().toString();
        apiKey = UUID.randomUUID().toString();
        requestContext = request(CometCredentials.apiKey(apiKey));
        resolver = new RemoteOrganizationResolver(() -> requestContext, client, cache, true);
    }

    @Nested
    class RequestOrganization {

        @Test
        void coldCacheAsksCometAndRemembersTheAnswer() {
            stubApiKey(organization(organizationId, false));

            resolver.resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
            StepVerifier.create(cache.find(workspaceId).map(WorkspaceOrganizationCache.Entry::organizationId))
                    .expectNext(organizationId)
                    .verifyComplete();
        }

        @Test
        void warmCacheAnswersWithoutComet() {
            stubApiKey(organization(organizationId, false));
            resolver.resolveRequestOrganization();

            requestContext = request(CometCredentials.apiKey(apiKey));
            clock.advance(FRESH_FOR.minusSeconds(1));
            resolver.resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void staleCacheAsksCometAgain() {
            stubApiKey(organization(organizationId, false));
            resolver.resolveRequestOrganization();
            var movedOrganizationId = UUID.randomUUID().toString();
            wireMock.server().resetAll();
            stubApiKey(organization(movedOrganizationId, false));

            requestContext = request(CometCredentials.apiKey(apiKey));
            clock.advance(FRESH_FOR);
            resolver.resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isEqualTo(movedOrganizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void notFoundMeansNoOrganizationAndIsRemembered() {
            stubApiKey(notFound());

            resolver.resolveRequestOrganization();
            requestContext = request(CometCredentials.apiKey(apiKey));
            resolver.resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isNull();
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void cometErrorKeepsTheStaleOrganizationAndWaitsBeforeAskingAgain() {
            stubApiKey(organization(organizationId, false));
            resolver.resolveRequestOrganization();
            wireMock.server().resetAll();
            stubApiKey(serverError());
            clock.advance(FRESH_FOR);

            requestContext = request(CometCredentials.apiKey(apiKey));
            resolver.resolveRequestOrganization();
            assertThat(requestContext.getOrganizationId()).isEqualTo(organizationId);

            requestContext = request(CometCredentials.apiKey(apiKey));
            clock.advance(WorkspaceOrganizationCache.RETRY_AFTER_FAILURE.minusSeconds(1));
            resolver.resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void cometErrorWithNothingCachedLeavesNoOrganization() {
            stubApiKey(serverError());

            resolver.resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isNull();
        }

        @Test
        void requestWithoutCometCredentialsOnlyReadsTheCache() {
            requestContext = request(null);

            resolver.resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isNull();
            assertThat(wireMock.server().getAllServeEvents()).isEmpty();
        }

        @Test
        void defaultWorkspaceNeverAsksComet() {
            requestContext = request(CometCredentials.apiKey(apiKey)).toBuilder()
                    .workspaceName(ProjectService.DEFAULT_WORKSPACE_NAME)
                    .build();

            resolver.resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isNull();
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

            resolver.resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isEqualTo(organizationId);
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
            resolver.resolveRequestOrganization();
            clock.advance(KEPT_FOR.minusMinutes(1));

            StepVerifier.create(resolver.organizationId().contextWrite(job(null)))
                    .expectNext(organizationId)
                    .verifyComplete();
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void jobWithoutAnOrganizationAndNothingCachedHasNone() {
            StepVerifier.create(resolver.organizationId().contextWrite(job(null)))
                    .verifyComplete();
            assertThat(wireMock.server().getAllServeEvents()).isEmpty();
        }

        @Test
        void contextHoldingTheDefaultOrganizationHasNone() {
            StepVerifier.create(resolver.organizationId()
                    .contextWrite(job(ProjectService.DEFAULT_ORGANIZATION_ID)))
                    .verifyComplete();
        }

        @Test
        void noContextHasNone() {
            StepVerifier.create(resolver.organizationId())
                    .verifyComplete();
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

        @Test
        void nonAdminIsRefused() {
            stubApiKey(organization(organizationId, false));

            StepVerifier.create(resolver.requireOrganizationAdmin())
                    .expectErrorSatisfies(error -> assertForbidden(error,
                            RemoteOrganizationResolver.NOT_ORGANIZATION_ADMIN))
                    .verify();
        }

        @Test
        void everyCheckAsksComet() {
            stubApiKey(organization(organizationId, true));
            resolver.resolveRequestOrganization();

            StepVerifier.create(resolver.requireOrganizationAdmin().then(resolver.requireOrganizationAdmin()))
                    .expectNext(organizationId)
                    .verifyComplete();

            assertCometCalls(API_KEY_PATH, 3);
        }

        @Test
        void workspaceWithoutOrganizationIsRefused() {
            stubApiKey(notFound());

            StepVerifier.create(resolver.requireOrganizationAdmin())
                    .expectErrorSatisfies(error -> assertForbidden(error,
                            RemoteOrganizationResolver.UNKNOWN_ORGANIZATION))
                    .verify();
        }

        @Test
        void cometErrorIsServiceUnavailable() {
            stubApiKey(serverError());

            StepVerifier.create(resolver.requireOrganizationAdmin())
                    .expectErrorSatisfies(error -> {
                        assertThat(error)
                                .isInstanceOf(ServiceUnavailableException.class)
                                .hasMessage(RemoteOrganizationResolver.ADMIN_CHECK_FAILED);
                        assertThat(((ServiceUnavailableException) error).getResponse().getStatus())
                                .isEqualTo(503);
                    })
                    .verify();
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

            offResolver().resolveRequestOrganization();

            assertThat(requestContext.getOrganizationId()).isNull();
            assertCometCalls(API_KEY_PATH, 0);
            verifyNoInteractions(untouchedCache);
        }

        @Test
        void jobWithoutAnOrganizationDoesNotReadTheCache() {
            StepVerifier.create(offResolver().organizationId().contextWrite(job(null)))
                    .verifyComplete();
            verifyNoInteractions(untouchedCache);
        }

        @Test
        void jobCarryingAnOrganizationStillUsesIt() {
            StepVerifier.create(offResolver().organizationId().contextWrite(job(organizationId)))
                    .expectNext(organizationId)
                    .verifyComplete();
            verifyNoInteractions(untouchedCache);
        }
    }

    private RequestContext request(CometCredentials credentials) {
        return RequestContext.builder()
                .userName("user")
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .cometCredentials(credentials)
                .build();
    }

    private Context job(String jobOrganizationId) {
        var context = Context.of(RequestContext.USER_NAME, "user", RequestContext.WORKSPACE_ID, workspaceId);
        return jobOrganizationId == null ? context : context.put(RequestContext.ORGANIZATION_ID, jobOrganizationId);
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
                 "organizationName": "Example", "isOrganizationAdmin": %s}
                """.formatted(workspaceId, workspaceName, id, isAdmin));
    }

    private void assertCometCalls(String path, int count) {
        wireMock.server().verify(count, postRequestedFor(urlPathEqualTo(path)));
    }

    private static void assertForbidden(Throwable error, String message) {
        assertThat(error).isInstanceOf(ForbiddenException.class).hasMessage(message);
        assertThat(((ForbiddenException) error).getResponse().getStatus()).isEqualTo(403);
    }

    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-11-04T12:00:00Z");

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
