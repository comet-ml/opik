package com.comet.opik.infrastructure.net;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import jakarta.ws.rs.ProcessingException;
import okhttp3.Request;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A host whose DNS answer changes between the pre-flight check and the connection: public when checked, then the
 * loopback address a mock server listens on. Both guarded clients refuse the connection.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Destination guard against a DNS answer that changes before connecting")
class DestinationGuardRebindingTest {

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();

    private String host;
    private String url;
    private DestinationGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        wireMock.server().resetAll();
        wireMock.server().stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200)));
        host = "rebind-%s.test".formatted(RandomStringUtils.secure().nextAlphanumeric(8).toLowerCase());
        url = "http://%s:%d/%s".formatted(host, wireMock.server().port(), UUID.randomUUID());
        var publicAddress = InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34});
        var loopback = InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1});
        var lookups = new AtomicInteger();
        guard = DestinationGuard.builder()
                .mode(DestinationGuard.Mode.STRICT)
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .resolver(name -> new InetAddress[]{lookups.getAndIncrement() == 0 ? publicAddress : loopback})
                .build();
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("the OkHttp LLM client refuses the address it would connect to")
    void okHttpClientRefusesTheChangedAnswer() {
        var client = DestinationGuardDns.clientBuilder(guard).build();

        assertThatCode(() -> guard.validate(url)).doesNotThrowAnyException();
        assertThatThrownBy(() -> client.newCall(new Request.Builder().url(url).build()).execute().close())
                .isExactlyInstanceOf(RefusedHostException.class);

        assertThat(wireMock.server().findAll(anyRequestedFor(anyUrl()))).isEmpty();
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();
    }

    @Test
    @DisplayName("the shared Jersey client refuses the address it would connect to")
    void jerseyClientRefusesTheChangedAnswer() {
        var client = TestHttpClientUtils.client();

        assertThatCode(() -> guard.validate(url)).doesNotThrowAnyException();
        assertThatThrownBy(() -> client.target(url)
                .request()
                .property(DestinationGuardDnsResolver.PROPERTY, guard)
                .get()
                .close())
                .isExactlyInstanceOf(ProcessingException.class)
                .matches(DestinationGuardDns::isRefused);

        assertThat(wireMock.server().findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }
}
