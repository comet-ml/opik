package com.comet.opik.infrastructure.http;

import com.codahale.metrics.MetricRegistry;
import com.comet.opik.TestConfigUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.dropwizard.client.JerseyClientBuilder;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
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

    // gzip disabled so WireMock serves the explicitly gzipped body verbatim rather than re-encoding it —
    // the response-side gzip is asserted by the stub, not left to WireMock's auto-gzip default.
    private final WireMockServer wireMockServer = new WireMockServer(
            wireMockConfig().dynamicPort().dynamicHttpsPort().gzipDisabled(true));

    private Client gzipEnabledClient;

    @BeforeAll
    void setUpAll() {
        wireMockServer.start();
        gzipEnabledClient = newGzipEnabledClient();
    }

    @AfterAll
    void tearDownAll() {
        wireMockServer.stop();
        gzipEnabledClient.close();
    }

    @BeforeEach
    void beforeEach() {
        wireMockServer.resetAll();
    }

    @Test
    void readEntity__whenResponseIsGzipCompressed__thenSharedClientDecodesExactlyOnce() {
        var body = "{\"payload\":\"" + "x".repeat(4000) + "\"}";

        // Simulate a real upstream that gzips its response: gzipped bytes + Content-Encoding: gzip.
        // With gzipEnabled: true, Apache HttpClient transparently decodes the entity, but the stale
        // Content-Encoding header survives into the Jersey response; without the reconciling filter,
        // GZipDecoder decodes the already-plain stream a second time and readEntity throws
        // 'ZipException: Not in GZIP format'. The filter drops the stale header, leaving a single decode.
        wireMockServer.stubFor(get(urlPathEqualTo("/models")).willReturn(aResponse()
                .withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .withHeader(HttpHeaders.CONTENT_ENCODING, "gzip")
                .withBody(gzip(body))));

        try (Response response = gzipEnabledClient.target(wireMockServer.url("/models"))
                .request(MediaType.APPLICATION_JSON)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(String.class)).isEqualTo(body);
        }
    }

    private static byte[] gzip(String value) {
        var baos = new ByteArrayOutputStream();
        try (var gzipStream = new GZIPOutputStream(baos)) {
            gzipStream.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
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
