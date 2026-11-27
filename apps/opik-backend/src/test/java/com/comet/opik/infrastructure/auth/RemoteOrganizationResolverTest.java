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
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
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
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.forbidden;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteOrganizationResolverTest {

    private static final Duration FRESH_FOR = Duration.ofMinutes(1);
    private static final Duration KEPT_FOR = Duration.ofMinutes(5);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(10);
    private static final Duration SLOW_COMET = Duration.ofSeconds(2);
    private static final int CONCURRENT_LOOKUPS = 50;
    private static final long NO_KEY = -2;

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
        cache = new WorkspaceOrganizationCache(redissonClient.reactive(), FRESH_FOR, KEPT_FOR, RETRY_AFTER_FAILURE,
                clock);
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
        void oneRequestAskingTwiceAsksCometOnce() {
            stubApiKey(organization(organizationId, false));

            assertThat(ask()).isEqualTo(organizationId);
            assertThat(ask()).isEqualTo(organizationId);

            assertCometCalls(API_KEY_PATH, 1);
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

            clock.advance(FRESH_FOR.minusSeconds(1));

            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void staleCacheIsServedAtOnceAndRefreshedOnceInTheBackground() {
            stubApiKey(organization(organizationId, false));
            ask();
            var movedOrganizationId = UUID.randomUUID().toString();
            wireMock.server().resetAll();
            stubApiKey(slowly(organization(movedOrganizationId, false)));

            clock.advance(FRESH_FOR);

            StepVerifier.create(lookup(requestContext))
                    .expectNext(organizationId)
                    .expectComplete()
                    .verify(SLOW_COMET.dividedBy(4));
            assertThat(askConcurrently(requestContext))
                    .isEqualTo(Collections.nCopies(CONCURRENT_LOOKUPS, Optional.of(organizationId)));
            await().untilAsserted(() -> assertThat(cachedEntry()).isEqualTo(entry(movedOrganizationId, FRESH_FOR)));
            assertThat(ask()).isEqualTo(movedOrganizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void staleOrganizationIsRefreshedForACallerWithOtherCredentialsWhenTheSharedRefreshIsDenied() {
            stubApiKey(organization(organizationId, false));
            ask();
            var movedOrganizationId = UUID.randomUUID().toString();
            var deniedApiKey = UUID.randomUUID().toString();
            wireMock.server().resetAll();
            stubApiKey(deniedApiKey, slowly(forbidden()));
            stubApiKey(organization(movedOrganizationId, false));
            clock.advance(FRESH_FOR);

            assertThat(lookup(request(CometCredentials.apiKey(deniedApiKey))).block()).isEqualTo(organizationId);
            await().untilAsserted(() -> assertApiKeyCalls(deniedApiKey, 1));
            assertThat(ask()).isEqualTo(organizationId);

            await().untilAsserted(() -> assertThat(cachedEntry()).isEqualTo(entry(movedOrganizationId, FRESH_FOR)));
            assertThat(ask()).isEqualTo(movedOrganizationId);
            assertApiKeyCalls(deniedApiKey, 1);
            assertApiKeyCalls(apiKey, 1);
        }

        static Stream<Arguments> answersThatMeanNoOrganization() {
            return Stream.of(
                    arguments("404", given(test -> notFound())),
                    arguments("an organization with a blank ID", given(test -> test.organization("", false))),
                    arguments("Comet's unknown-workspace 400", given(test -> cometError(400, "No such workspace!"))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("answersThatMeanNoOrganization")
        void answerThatMeansNoOrganizationLeavesNoneAndIsRemembered(String name,
                Given<ResponseDefinitionBuilder> answer) {
            stubApiKey(answer.of(RemoteOrganizationResolverTest.this));

            assertThat(ask()).isNull();

            assertThat(ask()).isNull();
            assertCometCalls(API_KEY_PATH, 1);
        }

        static Stream<Arguments> cometDown() {
            return Stream.of(
                    arguments("a server error", given(test -> serverError())),
                    arguments("a 502", given(test -> aResponse().withStatus(502))),
                    arguments("a connection failure",
                            given(test -> aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))));
        }

        static Stream<Arguments> failuresOfOneCall() {
            return Stream.of(
                    arguments("another 400 from Comet",
                            given(test -> cometError(400, RandomStringUtils.secure().nextAlphabetic(20)))),
                    arguments("a 400 without Comet's error body", given(test -> badRequest())),
                    arguments("a 400 whose body isn't JSON", given(test -> aResponse().withStatus(400)
                            .withHeader("Content-Type", "text/plain").withBody("No such workspace!"))),
                    arguments("a success without a body", given(test -> aResponse().withStatus(200))),
                    arguments("a success whose body isn't JSON", given(test -> aResponse().withStatus(200)
                            .withHeader("Content-Type", "text/html").withBody("<html>Comet</html>"))),
                    arguments("a success whose JSON can't be read", given(test -> aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json").withBody("{\"organizationId\": "))));
        }

        static Stream<Arguments> failedAnswers() {
            return Stream.concat(cometDown(), failuresOfOneCall());
        }

        static Stream<Arguments> denials() {
            return Stream.of(
                    arguments("403", given(test -> forbidden())),
                    arguments("401", given(test -> unauthorized())));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cometDown")
        void cometDownLeavesNoneAndCometIsAskedAgainOnlyAfterTheRetryWindow(String name,
                Given<ResponseDefinitionBuilder> answer) {
            stubApiKey(answer.of(RemoteOrganizationResolverTest.this));

            assertThat(ask()).isNull();

            clock.advance(RETRY_AFTER_FAILURE.minusSeconds(1));
            assertThat(ask()).isNull();
            assertCometCalls(API_KEY_PATH, 1);

            clock.advance(Duration.ofSeconds(1));
            assertThat(ask()).isNull();
            assertCometCalls(API_KEY_PATH, 2);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("failuresOfOneCall")
        void failureOfOneCallLeavesNoneAndTheNextCallerAsksAgain(String name,
                Given<ResponseDefinitionBuilder> answer) {
            stubApiKey(answer.of(RemoteOrganizationResolverTest.this));

            assertThat(ask()).isNull();

            assertThat(failureTimeToLive()).isEqualTo(NO_KEY);
            assertThat(ask()).isNull();
            assertCometCalls(API_KEY_PATH, 2);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("denials")
        void denialLeavesNoneAndCometIsAskedAgain(String name, Given<ResponseDefinitionBuilder> denial) {
            stubApiKey(denial.of(RemoteOrganizationResolverTest.this));

            assertThat(ask()).isNull();

            assertThat(ask()).isNull();
            assertCometCalls(API_KEY_PATH, 2);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("failedAnswers")
        void failedAnswerKeepsTheStaleOrganizationAndWaitsBeforeAskingAgain(String name,
                Given<ResponseDefinitionBuilder> answer) {
            stubApiKey(organization(organizationId, false));
            ask();
            wireMock.server().resetAll();
            stubApiKey(answer.of(RemoteOrganizationResolverTest.this));
            clock.advance(FRESH_FOR);

            assertThat(ask()).isEqualTo(organizationId);
            await().untilAsserted(
                    () -> assertThat(cachedEntry()).isEqualTo(entry(organizationId, RETRY_AFTER_FAILURE)));

            clock.advance(RETRY_AFTER_FAILURE.minusSeconds(1));

            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
            assertThat(failureTimeToLive()).isEqualTo(NO_KEY);
        }

        @Test
        void cometErrorStillServesTheStaleOrganizationWhenRedisCannotDelayTheNextCall() {
            var failingCache = spy(cache);
            doReturn(Mono.error(new RedisException("Redis is down"))).when(failingCache).retryLater(workspaceId);
            resolver = new RemoteOrganizationResolver(() -> requestContext, client, failingCache, true);
            stubApiKey(organization(organizationId, false));
            ask();
            wireMock.server().resetAll();
            stubApiKey(serverError());
            clock.advance(FRESH_FOR);

            var logs = LogCaptureUtils.logEventsOf(() -> await().untilAsserted(() -> {
                assertThat(ask()).isEqualTo(organizationId);
                wireMock.server().verify(moreThanOrExactly(2), cometCalls(API_KEY_PATH));
            }));

            assertThat(logs).anySatisfy(line -> assertThat(line)
                    .startsWith("Could not delay the next Comet call, workspace '%s'".formatted(workspaceId))
                    .contains("Redis is down"));
        }

        static Stream<Arguments> cometFailures() {
            return Stream.of(
                    arguments("a server error, with its status", serverError(),
                            "error type 'WebApplicationException', status '500'"),
                    arguments("a success without a body, with its status", aResponse().withStatus(200),
                            "error type 'WebApplicationException', status '200'"),
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

            assertThat(logs).filteredOn(line -> line.startsWith("Could not read the Comet error body, status '400'"))
                    .singleElement()
                    .satisfies(line -> assertThat(line).contains("ProcessingException"));
        }

        @Test
        void staleOrganizationIsDroppedWhenCometNoLongerReportsOne() {
            stubApiKey(organization(organizationId, false));
            ask();
            wireMock.server().resetAll();
            stubApiKey(notFound());
            clock.advance(FRESH_FOR);

            assertThat(ask()).isEqualTo(organizationId);

            await().untilAsserted(() -> assertThat(ask()).isNull());
            assertCometCalls(API_KEY_PATH, 1);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("denials")
        void staleOrganizationIsKeptWhenCometDeniesTheCaller(String name, Given<ResponseDefinitionBuilder> denial) {
            stubApiKey(organization(organizationId, false));
            ask();
            wireMock.server().resetAll();
            stubApiKey(denial.of(RemoteOrganizationResolverTest.this));
            clock.advance(FRESH_FOR);

            assertThat(ask()).isEqualTo(organizationId);

            await().untilAsserted(() -> {
                assertThat(ask()).isEqualTo(organizationId);
                wireMock.server().verify(moreThanOrExactly(2), cometCalls(API_KEY_PATH));
            });
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
            requestContext = request(CometCredentials.builder().kind(kind).value(value).build());

            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(path, 1);
        }
    }

    @Nested
    class ConcurrentLookups {

        @Test
        void lookupsWithNoEntryShareOneCometCall() {
            stubApiKey(slowly(organization(organizationId, false)));

            assertThat(askConcurrently(requestContext))
                    .isEqualTo(Collections.nCopies(CONCURRENT_LOOKUPS, Optional.of(organizationId)));
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void cancelledLookupDoesNotCancelTheCometCallOthersWaitOn() {
            stubApiKey(slowly(organization(organizationId, false)));
            var cancelled = lookup(requestContext).subscribe();
            await().untilAsserted(() -> assertCometCalls(API_KEY_PATH, 1));

            cancelled.dispose();

            StepVerifier.create(lookup(requestContext))
                    .expectNext(organizationId)
                    .expectComplete()
                    .verify(SLOW_COMET.multipliedBy(2));
            assertThat(cachedEntry()).isEqualTo(entry(organizationId, FRESH_FOR));
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void denialOfOneCallerDoesNotDecideForACallerWithOtherCredentials() {
            var deniedApiKey = UUID.randomUUID().toString();
            stubApiKey(deniedApiKey, slowly(forbidden()));
            stubApiKey(organization(organizationId, false));
            var denied = lookup(request(CometCredentials.apiKey(deniedApiKey))).toFuture();
            await().untilAsserted(() -> assertApiKeyCalls(deniedApiKey, 1));

            StepVerifier.create(lookup(requestContext))
                    .expectNext(organizationId)
                    .verifyComplete();

            assertThat(denied.join()).isNull();
            assertApiKeyCalls(deniedApiKey, 1);
            assertApiKeyCalls(apiKey, 1);
        }

        @Test
        void callersDeniedForOtherCredentialsShareOneNewLookup() {
            var deniedApiKey = UUID.randomUUID().toString();
            stubApiKey(deniedApiKey, slowly(forbidden()));
            stubApiKey(slowly(organization(organizationId, false)));
            var denied = lookup(request(CometCredentials.apiKey(deniedApiKey))).toFuture();
            await().untilAsserted(() -> assertApiKeyCalls(deniedApiKey, 1));

            assertThat(askConcurrently(requestContext))
                    .isEqualTo(Collections.nCopies(CONCURRENT_LOOKUPS, Optional.of(organizationId)));

            assertThat(denied.join()).isNull();
            assertApiKeyCalls(deniedApiKey, 1);
            assertApiKeyCalls(apiKey, 1);
        }

        @Test
        void callersWithTheDeniedCredentialsShareTheDenial() {
            stubApiKey(slowly(forbidden()));

            assertThat(askConcurrently(requestContext))
                    .isEqualTo(Collections.nCopies(CONCURRENT_LOOKUPS, Optional.<String>empty()));
            assertCometCalls(API_KEY_PATH, 1);
        }
    }

    @Nested
    class CometFailureWithNoEntry {

        @Test
        void isRememberedSoNoLookupAsksCometUntilTheRetryWindowEnds() {
            stubApiKey(serverError());

            assertThat(ask()).isNull();
            assertThat(askConcurrently(requestContext))
                    .isEqualTo(Collections.nCopies(CONCURRENT_LOOKUPS, Optional.<String>empty()));
            clock.advance(RETRY_AFTER_FAILURE.minusSeconds(1));
            assertThat(ask()).isNull();
            assertCometCalls(API_KEY_PATH, 1);

            wireMock.server().resetAll();
            stubApiKey(organization(organizationId, false));
            clock.advance(Duration.ofSeconds(1));

            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void isKeptApartFromTheEntryAndOnlyForTheRetryWindow() {
            stubApiKey(serverError());

            ask();

            assertThat(cachedEntry()).isNull();
            assertThat(failureTimeToLive()).isBetween(1L, RETRY_AFTER_FAILURE.toMillis());
        }

        @Test
        void isNeverKeptLongerThanAnAnswer() {
            var keptForLessThanTheRetryWindow = new WorkspaceOrganizationCache(redissonClient.reactive(), FRESH_FOR,
                    FRESH_FOR, FRESH_FOR.multipliedBy(2), clock);
            resolver = new RemoteOrganizationResolver(() -> requestContext, client, keptForLessThanTheRetryWindow,
                    true);
            stubApiKey(serverError());

            ask();

            assertThat(failureTimeToLive()).isBetween(1L, FRESH_FOR.toMillis());
        }

        @Test
        void noOrganizationAnswerIsAnEntryKeptForTheStaleTime() {
            stubApiKey(notFound());

            ask();

            assertThat(cachedEntry()).isEqualTo(entry(null, FRESH_FOR));
            assertThat(entryTimeToLive()).isBetween(KEPT_FOR.minusSeconds(10).toMillis(), KEPT_FOR.toMillis());
            assertThat(failureTimeToLive()).isEqualTo(NO_KEY);
        }

        @Test
        void keepsAnAnswerSavedMeanwhileAndRefreshesItAfterTheRetryWindow() {
            stubApiKey(slowly(serverError()));
            var failing = lookup(requestContext).toFuture();
            await().untilAsserted(() -> assertCometCalls(API_KEY_PATH, 1));

            cache.save(workspaceId, organizationId).block();

            assertThat(failing.join()).isNull();
            assertThat(cachedEntry()).isEqualTo(entry(organizationId, RETRY_AFTER_FAILURE));
            assertThat(entryTimeToLive()).isBetween(KEPT_FOR.minusSeconds(10).toMillis(), KEPT_FOR.toMillis());
            assertThat(failureTimeToLive()).isEqualTo(NO_KEY);
            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void answerSavedAfterTheFailureWins() {
            stubApiKey(serverError());
            ask();

            cache.save(workspaceId, organizationId).block();

            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
        }

        @Test
        void answerSavedAfterTheFailureEndsTheRetryWindow() {
            stubApiKey(serverError());
            ask();
            cache.save(workspaceId, organizationId).block();
            wireMock.server().resetAll();
            stubApiKey(organization(organizationId, false));

            // The saved answer expiring must not bring the failure back
            entryMap().delete();

            assertThat(failureTimeToLive()).isEqualTo(NO_KEY);
            assertThat(ask()).isEqualTo(organizationId);
            assertCometCalls(API_KEY_PATH, 1);
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
                            error type 'WebApplicationException', status '500'\
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

            var logs = LogCaptureUtils.logEventsOf(() -> StepVerifier
                    .create(jobResolver.requireOrganizationAdmin().contextWrite(job(organizationId)))
                    .expectErrorSatisfies(error -> assertForbidden(error,
                            RemoteOrganizationResolver.UNKNOWN_ORGANIZATION))
                    .verify());

            assertThat(wireMock.server().getAllServeEvents()).isEmpty();
            assertThat(logs).anySatisfy(line -> assertThat(line)
                    .startsWith("Organization admin check outside a request, so there is no caller to check")
                    .contains("OutOfScopeException"));
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

        @Test
        void jobGetsTheOrganizationItCarries() {
            StepVerifier.create(offResolver().organizationId().contextWrite(job(organizationId)))
                    .expectNext(organizationId)
                    .verifyComplete();
            verifyNoInteractions(untouchedCache);
        }

        @Test
        void jobWithoutAnOrganizationGetsNone() {
            StepVerifier.create(offResolver().organizationId().contextWrite(job(null)))
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
                .build();
    }

    private String ask() {
        return lookup(requestContext).block();
    }

    private Mono<String> lookup(RequestContext context) {
        return resolver.organizationId().contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, context));
    }

    private List<Optional<String>> askConcurrently(RequestContext context) {
        return Flux.range(0, CONCURRENT_LOOKUPS)
                .flatMap(unused -> lookup(context)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .subscribeOn(Schedulers.parallel()), CONCURRENT_LOOKUPS)
                .collectList()
                .block();
    }

    private WorkspaceOrganizationCache.Entry cachedEntry() {
        return cache.find(workspaceId).block();
    }

    private WorkspaceOrganizationCache.Entry entry(String expectedOrganizationId, Duration freshFor) {
        return WorkspaceOrganizationCache.Entry.builder()
                .organizationId(expectedOrganizationId)
                .refreshAfter(clock.instant().plus(freshFor))
                .build();
    }

    private RMap<Object, Object> entryMap() {
        return redissonClient.getMap("opik:workspace-organization:%s".formatted(workspaceId));
    }

    private long entryTimeToLive() {
        return entryMap().remainTimeToLive();
    }

    private long failureTimeToLive() {
        return redissonClient.getBucket("opik:workspace-organization-failed:%s".formatted(workspaceId))
                .remainTimeToLive();
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
        stubApiKey(apiKey, response);
    }

    private void stubApiKey(String key, ResponseDefinitionBuilder response) {
        MappingBuilder stub = post(urlPathEqualTo(API_KEY_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(key))
                .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)));
        wireMock.server().stubFor(stub.willReturn(response));
    }

    private static ResponseDefinitionBuilder slowly(ResponseDefinitionBuilder response) {
        return response.withFixedDelay(Math.toIntExact(SLOW_COMET.toMillis()));
    }

    private ResponseDefinitionBuilder organization(String id, boolean isAdmin) {
        return okJson("""
                {"workspaceId": "%s", "workspaceName": "%s", "organizationId": "%s",
                 "organizationName": "%s", "isOrganizationAdmin": %s}
                """.formatted(workspaceId, workspaceName, id, organizationName, isAdmin));
    }

    private RequestPatternBuilder cometCalls(String path) {
        return postRequestedFor(urlPathEqualTo(path))
                .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)));
    }

    private void assertCometCalls(String path, int count) {
        wireMock.server().verify(count, cometCalls(path));
    }

    private void assertApiKeyCalls(String key, int count) {
        wireMock.server().verify(count, cometCalls(API_KEY_PATH).withHeader(HttpHeaders.AUTHORIZATION, equalTo(key)));
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

        private volatile Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

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
