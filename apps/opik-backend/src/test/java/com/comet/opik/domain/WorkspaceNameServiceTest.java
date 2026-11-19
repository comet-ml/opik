package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.CacheConfiguration;
import com.comet.opik.infrastructure.cache.CacheManager;
import com.comet.opik.infrastructure.redis.RedisModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("workspace name or id")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceNameServiceTest {

    private static final String NAME_PATH = "/workspaces/workspace-name";
    private static final Duration LOOKUP_FAILURE_TTL = Duration.ofSeconds(2);

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();

    private WireMockServer server;
    private RedissonClient redisson;
    private CacheManager cacheManager;

    @BeforeAll
    void setUpAll() {
        redis.start();
        var redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(redis.getRedisURI());
        redisson = Redisson.create(redissonConfig);
        cacheManager = new RedisModule().cacheManager(redisson.reactive(), redisson);
        server = WireMockUtils.startWireMock().server();
    }

    @AfterAll
    void tearDownAll() {
        server.stop();
        redisson.shutdown();
        redis.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
    }

    @Test
    @DisplayName("the React service's name")
    void name() {
        var name = RandomStringUtils.secure().nextAlphanumeric(16);
        server.stubFor(get(urlPathEqualTo(NAME_PATH)).willReturn(aResponse().withStatus(200).withBody(name)));

        assertThat(service(server.baseUrl()).nameOrId(UUID.randomUUID().toString())).isEqualTo(name);
    }

    @Test
    @DisplayName("the id when the React service fails, without asking it again until the failure expires")
    void failedLookupFallsBackToTheIdAndBacksOff() {
        var workspaceId = UUID.randomUUID().toString();
        server.stubFor(get(urlPathEqualTo(NAME_PATH)).willReturn(aResponse().withStatus(500)));
        var service = service(server.baseUrl());

        assertThat(service.nameOrId(workspaceId)).isEqualTo(workspaceId);
        var before = requests();
        // The failure is cached before the first call returns, so the next call doesn't ask the React service.
        assertThat(service.nameOrId(workspaceId)).isEqualTo(workspaceId);
        assertThat(requests()).isEqualTo(before);

        // Once the failure expires, the next call asks again.
        var asked = requests();
        await().atMost(LOOKUP_FAILURE_TTL.multipliedBy(2)).untilAsserted(() -> {
            assertThat(service.nameOrId(workspaceId)).isEqualTo(workspaceId);
            assertThat(requests()).isGreaterThan(asked);
        });
    }

    @Test
    @DisplayName("the id when no React service is configured")
    void noReactService() {
        var workspaceId = UUID.randomUUID().toString();

        assertThat(service(null).nameOrId(workspaceId)).isEqualTo(workspaceId);
        assertThat(requests()).isZero();
    }

    private int requests() {
        return server.findAll(getRequestedFor(urlPathEqualTo(NAME_PATH))).size();
    }

    private WorkspaceNameService service(String reactServiceUrl) {
        var authentication = new AuthenticationConfig();
        authentication
                .setReactService(reactServiceUrl == null ? null : new AuthenticationConfig.UrlConfig(reactServiceUrl));
        var cacheConfiguration = new CacheConfiguration();
        cacheConfiguration.setEnabled(true);
        cacheConfiguration.setDefaultDuration(Duration.ofSeconds(1));
        cacheConfiguration.setCaches(Map.of(WorkspaceNameService.LOOKUP_FAILURES_CACHE, LOOKUP_FAILURE_TTL));
        return new WorkspaceNameService(TestHttpClientUtils.client(), authentication, cacheManager,
                cacheConfiguration);
    }
}
