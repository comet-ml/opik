package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.LlmProviderTokenAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.config.Config;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Auth Token Provider Test")
class AuthTokenProviderTest {

    private static final String TOKEN_PATH = "/oauth/token";
    private static final String SECRET_VALUE = "super-s3cr3t";

    /**
     * Single-flight correctness belongs to {@code RedissonLockService}'s own tests (which is also
     * package-private); here the lock is a passthrough so the provider's orchestration is what's
     * under test.
     */
    private static final LockService PASSTHROUGH_LOCK_SERVICE = new LockService() {
        @Override
        public <T> Mono<T> executeWithLock(Lock lock, Mono<T> action) {
            return action;
        }

        @Override
        public <T> Mono<T> executeWithLockCustomExpire(Lock lock, Mono<T> action, Duration duration) {
            return action;
        }

        @Override
        public <T> Flux<T> executeWithLock(Lock lock, Flux<T> action) {
            return action;
        }

        @Override
        public <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction,
                Duration actionTimeout, Duration lockTimeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction,
                Duration actionTimeout, Duration lockTimeout, boolean holdUntilExpiry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Boolean> lockUsingToken(Lock lock, Duration lockDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> unlockUsingToken(Lock lock) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<String> tryAcquireSlot(Lock lock, int totalSlots, Duration leaseTime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Boolean> refreshSlot(Lock lock, String permitId, Duration leaseTime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Boolean> releaseSlot(Lock lock, String permitId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> addSlotPermits(Lock lock, int delta) {
            throw new UnsupportedOperationException();
        }
    };

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private WireMockServer wireMock;
    private RedissonClient redisson;
    private StringRedisClient stringRedisClient;
    private AuthTokenProvider provider;

    @BeforeAll
    void setUpAll() {
        redis.start();
        var redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(redis.getRedisURI());
        redisson = Redisson.create(redissonConfig);
        stringRedisClient = new StringRedisClient(redisson);

        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        var opikConfiguration = new OpikConfiguration();
        opikConfiguration.getEncryption().setKey("0123456789abcdef");
        EncryptionUtils.setConfig(opikConfiguration);

        provider = new AuthTokenProvider(stringRedisClient, PASSTHROUGH_LOCK_SERVICE, relaxedConfig());
    }

    private static LlmProviderTokenAuthConfig relaxedConfig() {
        var config = new LlmProviderTokenAuthConfig();
        config.setDestinationGuard(DestinationGuard.Mode.RELAXED);
        return config;
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
        redisson.shutdown();
        redis.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
    }

    private ProviderAuthConfig.ProviderAuthConfigBuilder oauthRecipe() {
        return ProviderAuthConfig.builder()
                .tokenUrl(wireMock.baseUrl() + TOKEN_PATH)
                .credentials(List.of(
                        credential("grant_type", "client_credentials", false),
                        credential("client_id", "opik-prod", false),
                        credential("client_secret", SECRET_VALUE, true)));
    }

    private ProviderAuthConfig.Credential credential(String key, String value, boolean secret) {
        return ProviderAuthConfig.Credential.builder().key(key).value(value).secret(secret).build();
    }

    private void stubToken(String body) {
        wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(okJson(body)));
    }

    private List<String> cachedKeys(UUID providerId) {
        var keys = new java.util.ArrayList<String>();
        redisson.getKeys()
                .getKeys(KeysScanOptions.defaults().pattern("llm_auth_token:%s:*".formatted(providerId)))
                .forEach(keys::add);
        return keys;
    }

    @Test
    @DisplayName("form mode fetches, caches encrypted, and serves cache hits")
    void formModeFetchesAndCaches() {
        stubToken("{\"access_token\": \"tok-1\", \"expires_in\": 3600}");
        var providerId = UUID.randomUUID();
        var recipe = oauthRecipe().build();

        assertThat(provider.bearer("ws", providerId, recipe)).isEqualTo("tok-1");
        assertThat(provider.bearer("ws", providerId, recipe)).isEqualTo("tok-1");

        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_secret=" + SECRET_VALUE)));

        // the cached value is ciphertext: neither the token nor the recipe is readable in Redis
        var keys = cachedKeys(providerId);
        assertThat(keys).hasSize(1);
        String rawValue = stringRedisClient.getBucket(keys.getFirst()).get();
        assertThat(rawValue).doesNotContain("tok-1", SECRET_VALUE);
    }

    @Test
    @DisplayName("basic mode sends id/secret in the Basic header and the remaining fields in the body")
    void basicModeSendsBasicHeader() {
        stubToken("{\"access_token\": \"tok-basic\", \"expires_in\": 3600}");
        var recipe = oauthRecipe()
                .sendAs(ProviderAuthConfig.SendAs.BASIC)
                .credentials(List.of(
                        credential("grant_type", "client_credentials", false),
                        credential("scope", "data:read", false),
                        credential("client_id", "opik-prod", false),
                        credential("client_secret", SECRET_VALUE, true)))
                .build();

        assertThat(provider.bearer("ws", UUID.randomUUID(), recipe)).isEqualTo("tok-basic");

        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString(("opik-prod:" + SECRET_VALUE).getBytes());
        wireMock.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withHeader("Authorization", equalTo(expectedBasic))
                .withRequestBody(equalTo("grant_type=client_credentials&scope=data%3Aread")));
    }

    @Test
    @DisplayName("json mode sends the credentials as a JSON body")
    void jsonModeSendsJsonBody() {
        stubToken("{\"access_token\": \"tok-json\", \"expires_in\": 3600}");
        var recipe = oauthRecipe().sendAs(ProviderAuthConfig.SendAs.JSON).build();

        assertThat(provider.bearer("ws", UUID.randomUUID(), recipe)).isEqualTo("tok-json");

        wireMock.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(
                        "{\"grant_type\": \"client_credentials\", \"client_id\": \"opik-prod\", \"client_secret\": \"%s\"}"
                                .formatted(SECRET_VALUE))));
    }

    @Test
    @DisplayName("dot-path token field and fallback lifetime cover the service-account shape")
    void dotPathAndFallbackTtl() {
        stubToken("{\"result\": {\"jwt\": \"tok-nested\"}}");
        var recipe = oauthRecipe()
                .tokenField("result.jwt")
                .expiresField("result.ttl")
                .fallbackTtlSeconds(90_000L)
                .build();

        assertThat(provider.bearer("ws", UUID.randomUUID(), recipe)).isEqualTo("tok-nested");
    }

    @Test
    @DisplayName("a zero fallback leaves tokens uncached when the reply states no lifetime")
    void zeroTtlFetchesPerCall() {
        stubToken("{\"access_token\": \"tok-0\"}");
        var providerId = UUID.randomUUID();
        var recipe = oauthRecipe().fallbackTtlSeconds(0L).build();

        provider.bearer("ws", providerId, recipe);
        provider.bearer("ws", providerId, recipe);

        wireMock.verify(exactly(2), postRequestedFor(urlEqualTo(TOKEN_PATH)));
        assertThat(cachedKeys(providerId)).isEmpty();
    }

    @Test
    @DisplayName("a reply-stated lifetime wins over the fallback, including a zero fallback")
    void replyLifetimeWinsOverFallback() {
        stubToken("{\"access_token\": \"tok-authoritative\", \"expires_in\": 3600}");
        var providerId = UUID.randomUUID();
        var recipe = oauthRecipe().fallbackTtlSeconds(0L).build();

        provider.bearer("ws", providerId, recipe);
        provider.bearer("ws", providerId, recipe);

        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(TOKEN_PATH)));
        assertThat(cachedKeys(providerId)).hasSize(1);
    }

    @Test
    @DisplayName("a token inside the refresh window is refetched")
    void refreshWindowTriggersRefetch() throws InterruptedException {
        // 1s lifetime with the default 0.25 fraction: fresh for ~750ms, inside the window after
        stubToken("{\"access_token\": \"tok-refresh\", \"expires_in\": 1}");
        var providerId = UUID.randomUUID();
        var recipe = oauthRecipe().build();

        provider.bearer("ws", providerId, recipe);
        provider.bearer("ws", providerId, recipe);
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(TOKEN_PATH)));

        Thread.sleep(800);
        provider.bearer("ws", providerId, recipe);

        wireMock.verify(exactly(2), postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    @DisplayName("editing the recipe changes the cache key, so the old token is never served")
    void configChangeChangesCacheKey() {
        stubToken("{\"access_token\": \"tok-cfg\", \"expires_in\": 3600}");
        var providerId = UUID.randomUUID();

        provider.bearer("ws", providerId, oauthRecipe().build());
        provider.bearer("ws", providerId, oauthRecipe()
                .credentials(List.of(
                        credential("grant_type", "client_credentials", false),
                        credential("client_id", "opik-prod", false),
                        credential("client_secret", "rotated-secret", true)))
                .build());

        wireMock.verify(exactly(2), postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    @DisplayName("invalidate drops the cached token so the next call refetches")
    void invalidateForcesRefetch() {
        stubToken("{\"access_token\": \"tok-inv\", \"expires_in\": 3600}");
        var providerId = UUID.randomUUID();
        var recipe = oauthRecipe().build();

        provider.bearer("ws", providerId, recipe);
        provider.invalidateAfterGatewayRejection("ws", providerId, recipe);
        provider.bearer("ws", providerId, recipe);

        wireMock.verify(exactly(2), postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    @DisplayName("upstream errors surface status and body with credential values redacted")
    void upstreamErrorIsSurfacedRedacted() {
        wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(401)
                .withBody("{\"error\": \"invalid_client\", \"echo\": \"%s\"}".formatted(SECRET_VALUE))));

        assertThatThrownBy(() -> provider.bearer("ws", UUID.randomUUID(), oauthRecipe().build()))
                .isInstanceOf(AuthTokenException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid_client")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(SECRET_VALUE));
    }

    @Test
    @DisplayName("a missing token field names the reply's top-level fields, never values")
    void missingTokenFieldListsTopLevelKeys() {
        stubToken("{\"token\": \"the-actual-token\", \"ttl\": 60}");

        assertThatThrownBy(() -> provider.bearer("ws", UUID.randomUUID(), oauthRecipe().build()))
                .isInstanceOf(AuthTokenException.class)
                .hasMessageContaining("access_token")
                .hasMessageContaining("[token, ttl]")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("the-actual-token"));
    }

    @Test
    @DisplayName("a lock-wait timeout recovers from the holder's cache write instead of fetching directly")
    void lockTimeoutRecoversFromTheHoldersCacheWrite() {
        stubToken("{\"access_token\": \"tok-held\", \"expires_in\": 3600}");
        var recipe = oauthRecipe().build();
        UUID providerId = UUID.randomUUID();

        // the "holder": a normal provider sharing the same Redis, whose fetch caches tok-held
        var holder = new AuthTokenProvider(stringRedisClient, PASSTHROUGH_LOCK_SERVICE, relaxedConfig());
        // the "waiter": its lock wait elapses (empty result), but only after the holder has
        // written — exactly the sequence the timed-out-waiter re-read is for
        LockService timedOutLock = org.mockito.Mockito.mock(LockService.class);
        org.mockito.Mockito.when(timedOutLock.executeWithLockCustomExpire(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> Mono.fromRunnable(() -> holder.bearer("ws", providerId, recipe))
                        .then(Mono.empty()));
        var waiter = new AuthTokenProvider(stringRedisClient, timedOutLock, relaxedConfig());

        assertThat(waiter.bearer("ws", providerId, recipe)).isEqualTo("tok-held");
        // one fetch total (the holder's); the waiter recovered from the cache
        wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    @DisplayName("rotating a secret changes the cache key, so a stale cached token is never served")
    void rotatedSecretChangesTheCacheKey() {
        stubToken("{\"access_token\": \"tok-rotation\", \"expires_in\": 3600}");
        UUID providerId = UUID.randomUUID();

        provider.bearer("ws", providerId, oauthRecipe().build());
        provider.bearer("ws", providerId, oauthRecipe().build());

        var rotated = oauthRecipe()
                .credentials(List.of(
                        credential("grant_type", "client_credentials", false),
                        credential("client_id", "opik-prod", false),
                        credential("client_secret", "rotated-" + SECRET_VALUE, true)))
                .build();
        provider.bearer("ws", providerId, rotated);

        // the hash covers unmasked values: fetch 1 (cold), cache hit, fetch 2 (rotated secret).
        // Load-bearing — masking earlier in the chain would silently keep serving the stale token.
        wireMock.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    @DisplayName("a non-http(s) token_url surfaces as a clear auth error, never a raw exception")
    void nonHttpTokenUrlIsRejected() {
        var recipe = oauthRecipe().tokenUrl("mailto:auth@example.com").build();

        assertThatThrownBy(() -> provider.bearer("ws", UUID.randomUUID(), recipe))
                .isInstanceOf(AuthTokenException.class)
                .hasMessageContaining("not a usable http(s) URL");
    }

    @Test
    @DisplayName("an absurdly large lifetime is rejected before it can corrupt cache arithmetic")
    void oversizedLifetimeIsRejected() {
        stubToken("{\"access_token\": \"tok-huge\", \"expires_in\": 9223372036854775807}");

        assertThatThrownBy(() -> provider.bearer("ws", UUID.randomUUID(), oauthRecipe().build()))
                .isInstanceOf(AuthTokenException.class)
                .hasMessageContaining("outside the accepted range");
    }

    @Test
    @DisplayName("a reply without a lifetime and without a fallback is a clear error")
    void missingLifetimeWithoutFallbackIsRejected() {
        stubToken("{\"access_token\": \"tok-nolife\"}");

        assertThatThrownBy(() -> provider.bearer("ws", UUID.randomUUID(), oauthRecipe().build()))
                .isInstanceOf(AuthTokenException.class)
                .hasMessageContaining("expires_in")
                .hasMessageContaining("no fallback lifetime");
    }

    @Test
    @DisplayName("a non-JSON reply is a clear error")
    void nonJsonReplyIsRejected() {
        wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withBody("<html>gateway login page</html>")));

        assertThatThrownBy(() -> provider.bearer("ws", UUID.randomUUID(), oauthRecipe().build()))
                .isInstanceOf(AuthTokenException.class)
                .hasMessageContaining("non-JSON reply");
    }

    @Test
    @DisplayName("an unreachable token URL is a clear error, not a hang")
    void unreachableTokenUrlIsRejected() {
        var recipe = oauthRecipe().tokenUrl("http://localhost:1/token").build();

        assertThatThrownBy(() -> provider.bearer("ws", UUID.randomUUID(), recipe))
                .isInstanceOf(AuthTokenException.class)
                .hasMessageContaining("could not reach");
    }

    @Test
    @DisplayName("a strict destination guard refuses the token URL before any request is made")
    void strictGuardRefusesNonPublicTokenUrl() {
        var strictConfig = new LlmProviderTokenAuthConfig();
        strictConfig.setDestinationGuard(DestinationGuard.Mode.STRICT);
        var strictProvider = new AuthTokenProvider(stringRedisClient, PASSTHROUGH_LOCK_SERVICE, strictConfig);

        assertThatThrownBy(() -> strictProvider.bearer("ws", UUID.randomUUID(), oauthRecipe().build()))
                .isInstanceOf(AuthTokenException.class)
                .hasMessageContaining("only https");
        wireMock.verify(exactly(0), postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    @DisplayName("a Redis outage degrades to a direct fetch instead of failing the call")
    void redisOutageDegradesToDirectFetch() {
        var flakyRedis = RedisContainerUtils.newRedisContainer();
        flakyRedis.start();
        var flakyConfig = new Config();
        flakyConfig.useSingleServer()
                .setAddress(flakyRedis.getRedisURI())
                .setTimeout(500)
                .setRetryAttempts(0)
                .setConnectTimeout(500);
        var flakyRedisson = Redisson.create(flakyConfig);
        try {
            var flakyProvider = new AuthTokenProvider(new StringRedisClient(flakyRedisson),
                    PASSTHROUGH_LOCK_SERVICE, relaxedConfig());
            flakyRedis.stop();

            stubToken("{\"access_token\": \"tok-degraded\", \"expires_in\": 3600}");

            assertThat(flakyProvider.bearer("ws", UUID.randomUUID(), oauthRecipe().build()))
                    .isEqualTo("tok-degraded");
            wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(TOKEN_PATH)));
        } finally {
            flakyRedisson.shutdown();
        }
    }
}
