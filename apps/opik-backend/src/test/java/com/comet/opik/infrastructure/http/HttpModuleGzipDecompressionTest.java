package com.comet.opik.infrastructure.http;

import com.codahale.metrics.MetricRegistry;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import io.dropwizard.client.JerseyClientBuilder;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the client-side gzip double-decompression defect.
 *
 * <p>Runs with production gzip settings ({@code gzipEnabled: true}), which the shared
 * {@code config-test.yml} disables — so the existing suite never exercises the response-decoding path.
 * With gzip enabled, Dropwizard wires both Apache HttpClient's transparent decompression and Jersey's
 * {@code GZipDecoder}; without the reconciling filter registered by {@link HttpModule#buildClient}, the
 * stale {@code Content-Encoding: gzip} header triggers a second decode and {@code readEntity} throws
 * {@code java.util.zip.ZipException: Not in GZIP format}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpModuleGzipDecompressionTest {

    private static final WireMockUtils.WireMockRuntime WIRE_MOCK = WireMockUtils.startWireMock();

    private Client gzipEnabledClient;

    @BeforeAll
    void setUpAll() throws Exception {
        WIRE_MOCK.server().start();
        gzipEnabledClient = newGzipEnabledClient();
    }

    @AfterAll
    void tearDownAll() {
        WIRE_MOCK.server().stop();
        gzipEnabledClient.close();
    }

    @BeforeEach
    void beforeEach() {
        WIRE_MOCK.server().resetAll();
    }

    @Test
    void readEntity__whenResponseIsGzipCompressed__thenSharedClientDecodesExactlyOnce() {
        // A body large enough that the upstream gzips it, like a real Dropwizard/Jetty upstream over its
        // minimum entity size. With gzipEnabled: true both HttpClient and Jersey's GZipDecoder are active;
        // the reconciling filter drops the stale Content-Encoding header so only HttpClient decodes.
        var largeBody = "{\"payload\":\"" + "x".repeat(4000) + "\"}";
        WIRE_MOCK.server().stubFor(get(urlPathEqualTo("/models")).willReturn(okJson(largeBody)));

        try (Response response = gzipEnabledClient.target(WIRE_MOCK.server().url("/models"))
                .request(MediaType.APPLICATION_JSON)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(String.class)).isEqualTo(largeBody);
        }
    }

    /**
     * Builds a client through {@link HttpModule#buildClient} with production gzip settings
     * ({@code gzipEnabled: true}), so it registers {@code GZipDecoder} and the reconciling filter.
     */
    private Client newGzipEnabledClient() {
        var jerseyConfig = TestConfigUtils.loadConfigTest().getJerseyClient();
        jerseyConfig.setGzipEnabled(true);

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicLong counter = new AtomicLong();

            @Override
            public Thread newThread(Runnable runnable) {
                var thread = new Thread(runnable, "gzip-decompress-test-client-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        var executor = new ThreadPoolExecutor(2, 8, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), threadFactory);

        return HttpModule.buildClient(
                new JerseyClientBuilder(new MetricRegistry()).using(executor),
                jerseyConfig);
    }
}
