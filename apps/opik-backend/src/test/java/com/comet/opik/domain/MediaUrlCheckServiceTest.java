package com.comet.opik.domain;

import com.comet.opik.api.MediaUrlCheck.Result;
import com.comet.opik.api.MediaUrlCheck.Status;
import com.comet.opik.infrastructure.MediaUrlCheckConfig;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Media URL Check Service Test")
class MediaUrlCheckServiceTest {

    private static final String METADATA_URL = "http://169.254.169.254/latest/meta-data";

    private WireMockServer wireMock;
    private MediaUrlCheckService service;

    @BeforeAll
    void setUpAll() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        var config = new MediaUrlCheckConfig();
        config.setConnectTimeout(Duration.seconds(1));
        config.setPerUrlTimeout(Duration.milliseconds(500));

        // strict everywhere except the local mock server, which strict mode would refuse as loopback
        var strict = DestinationGuard.builder()
                .mode(DestinationGuard.Mode.STRICT)
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .build();
        var guard = mock(DestinationGuard.class);
        doAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (!url.startsWith(wireMock.baseUrl())) {
                strict.validate(url);
            }
            return null;
        }).when(guard).validate(anyString());

        service = new MediaUrlCheckService(config, guard);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
    }

    private String url(String path) {
        return wireMock.baseUrl() + path;
    }

    private Result checkOne(String url) {
        var results = service.check(List.of(url));
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    private static Result result(String url, Status status, Integer httpStatus, String contentType) {
        return Result.builder().url(url).status(status).httpStatus(httpStatus).contentType(contentType).build();
    }

    @Test
    @DisplayName("an image answers ok, and only its first byte is requested")
    void checkWhenImageReturnsOk() {
        wireMock.stubFor(get(urlEqualTo("/cat.png"))
                .willReturn(aResponse().withStatus(206).withHeader("Content-Type", "image/png").withBody("x")));

        assertThat(checkOne(url("/cat.png"))).isEqualTo(result(url("/cat.png"), Status.OK, 206, "image/png"));
        wireMock.verify(exactly(1), getRequestedFor(urlEqualTo("/cat.png")).withHeader("Range", equalTo("bytes=0-0")));
    }

    @Test
    @DisplayName("a missing file reports its HTTP status")
    void checkWhenNotFoundReturnsHttpError() {
        wireMock.stubFor(get(urlEqualTo("/missing.png")).willReturn(aResponse().withStatus(404)));

        assertThat(checkOne(url("/missing.png"))).isEqualTo(result(url("/missing.png"), Status.HTTP_ERROR, 404, null));
    }

    @Test
    @DisplayName("a web page instead of a media file reports its content type")
    void checkWhenHtmlReturnsNotMedia() {
        wireMock.stubFor(get(urlEqualTo("/page"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html; charset=utf-8")
                        .withBody("<html></html>")));

        assertThat(checkOne(url("/page"))).isEqualTo(result(url("/page"), Status.NOT_MEDIA, 200, "text/html"));
    }

    @Test
    @DisplayName("untyped binary and a missing content type are left for the provider to judge")
    void checkWhenContentTypeIsUnknownReturnsOk() {
        wireMock.stubFor(get(urlEqualTo("/upload"))
                .willReturn(aResponse().withStatus(206).withHeader("Content-Type", "binary/octet-stream")));
        wireMock.stubFor(get(urlEqualTo("/untyped")).willReturn(aResponse().withStatus(200)));

        assertThat(service.check(List.of(url("/upload"), url("/untyped")))).containsExactly(
                result(url("/upload"), Status.OK, 206, "binary/octet-stream"),
                result(url("/untyped"), Status.OK, 200, null));
    }

    @Test
    @DisplayName("a presigned URL that rejects HEAD still answers ok, because the check uses a ranged GET")
    void checkWhenHeadIsRejectedReturnsOk() {
        wireMock.stubFor(head(urlEqualTo("/bucket/photo.jpg?X-Amz-Signature=abc"))
                .willReturn(aResponse().withStatus(403)));
        wireMock.stubFor(get(urlEqualTo("/bucket/photo.jpg?X-Amz-Signature=abc"))
                .withHeader("Range", equalTo("bytes=0-0"))
                .willReturn(aResponse().withStatus(206).withHeader("Content-Type", "image/jpeg")));

        var signedUrl = url("/bucket/photo.jpg?X-Amz-Signature=abc");
        assertThat(checkOne(signedUrl)).isEqualTo(result(signedUrl, Status.OK, 206, "image/jpeg"));
        wireMock.verify(exactly(0), headRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("a private or internal address is blocked before any connection")
    void checkWhenHostIsInternalReturnsBlocked() {
        assertThat(checkOne(METADATA_URL)).isEqualTo(result(METADATA_URL, Status.BLOCKED, null, null));
    }

    @Test
    @DisplayName("a redirect to an internal address is blocked")
    void checkWhenRedirectTargetIsInternalReturnsBlocked() {
        wireMock.stubFor(get(urlEqualTo("/redirect"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", METADATA_URL)));

        assertThat(checkOne(url("/redirect"))).isEqualTo(result(url("/redirect"), Status.BLOCKED, null, null));
    }

    @Test
    @DisplayName("a redirect to a media file is followed")
    void checkWhenRedirectTargetIsImageReturnsOk() {
        wireMock.stubFor(get(urlEqualTo("/old.png"))
                .willReturn(aResponse().withStatus(301).withHeader("Location", "/new.png")));
        wireMock.stubFor(get(urlEqualTo("/new.png"))
                .willReturn(aResponse().withStatus(206).withHeader("Content-Type", "image/png")));

        assertThat(checkOne(url("/old.png"))).isEqualTo(result(url("/old.png"), Status.OK, 206, "image/png"));
    }

    @Test
    @DisplayName("a redirect loop stops after a few hops")
    void checkWhenRedirectsLoopReturnsTooManyRedirects() {
        wireMock.stubFor(get(urlEqualTo("/loop"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")));

        assertThat(checkOne(url("/loop"))).isEqualTo(result(url("/loop"), Status.TOO_MANY_REDIRECTS, null, null));
        wireMock.verify(exactly(6), getRequestedFor(urlEqualTo("/loop")));
    }

    @Test
    @DisplayName("a host that does not answer in time reports a timeout")
    void checkWhenHostIsSlowReturnsTimeout() {
        wireMock.stubFor(get(urlEqualTo("/slow.png"))
                .willReturn(aResponse().withStatus(206).withHeader("Content-Type", "image/png")
                        .withFixedDelay(2_000)));

        assertThat(checkOne(url("/slow.png"))).isEqualTo(result(url("/slow.png"), Status.TIMEOUT, null, null));
    }

    @Test
    @DisplayName("a host that does not resolve is unreachable, not blocked")
    void checkWhenHostDoesNotResolveReturnsUnreachable() {
        // .invalid is reserved (RFC 2606): guaranteed NXDOMAIN
        var unresolvable = "https://media.invalid/cat.png";

        assertThat(checkOne(unresolvable)).isEqualTo(result(unresolvable, Status.UNREACHABLE, null, null));
    }

    @Test
    @DisplayName("data URIs are skipped and other schemes or malformed URLs are invalid, without any request")
    void checkWhenNotHttpReturnsSkippedOrInvalid() {
        var dataUri = "data:image/png;base64,iVBORw0KGgo=";
        var ftpUrl = "ftp://example.com/cat.png";
        var malformed = "not a url";

        assertThat(service.check(List.of(dataUri, ftpUrl, malformed))).containsExactly(
                result(dataUri, Status.SKIPPED, null, null),
                result(ftpUrl, Status.INVALID_URL, null, null),
                result(malformed, Status.INVALID_URL, null, null));
        wireMock.verify(exactly(0), anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("results keep the request order")
    void checkWhenSeveralUrlsKeepsOrder() {
        wireMock.stubFor(get(urlEqualTo("/slow.png"))
                .willReturn(aResponse().withStatus(206).withHeader("Content-Type", "image/png")
                        .withFixedDelay(200)));
        wireMock.stubFor(get(urlEqualTo("/fast.mp4"))
                .willReturn(aResponse().withStatus(206).withHeader("Content-Type", "video/mp4")));

        assertThat(service.check(List.of(url("/slow.png"), url("/fast.mp4")))).containsExactly(
                result(url("/slow.png"), Status.OK, 206, "image/png"),
                result(url("/fast.mp4"), Status.OK, 206, "video/mp4"));
    }
}
