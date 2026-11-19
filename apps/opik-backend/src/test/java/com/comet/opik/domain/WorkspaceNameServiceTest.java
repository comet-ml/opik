package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.common.base.Ticker;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("workspace name or id")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceNameServiceTest {

    private static final String NAME_PATH = "/workspaces/workspace-name";

    private WireMockServer server;
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return nanos.get();
        }
    };

    @BeforeAll
    void startServer() {
        server = WireMockUtils.startWireMock().server();
    }

    @AfterAll
    void stopServer() {
        server.stop();
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
        nanos.addAndGet(WorkspaceNameService.LOOKUP_FAILURE_TTL.minusMillis(1).toNanos());
        assertThat(service.nameOrId(workspaceId)).isEqualTo(workspaceId);
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(NAME_PATH)))).hasSize(1);

        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        assertThat(service.nameOrId(workspaceId)).isEqualTo(workspaceId);
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(NAME_PATH)))).hasSize(2);
    }

    @Test
    @DisplayName("the id when no React service is configured")
    void noReactService() {
        var workspaceId = UUID.randomUUID().toString();

        assertThat(service(null).nameOrId(workspaceId)).isEqualTo(workspaceId);
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(NAME_PATH)))).isEmpty();
    }

    private WorkspaceNameService service(String reactServiceUrl) {
        var authentication = new AuthenticationConfig();
        authentication
                .setReactService(reactServiceUrl == null ? null : new AuthenticationConfig.UrlConfig(reactServiceUrl));
        return new WorkspaceNameService(TestHttpClientUtils.client(), authentication, ticker);
    }
}
