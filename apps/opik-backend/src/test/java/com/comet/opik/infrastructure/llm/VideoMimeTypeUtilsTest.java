package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.resources.utils.WireMockUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VideoMimeTypeUtilsTest {

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();

    @AfterAll
    void tearDown() {
        wireMock.server().stop();
    }

    @Test
    void internalDestinationsAreNeverRequested() {
        wireMock.server().stubFor(head(anyUrl()).willReturn(aResponse().withHeader("Content-Type", "video/mp4")));

        var mimeType = VideoMimeTypeUtils.detectMimeTypeFromHttpHead(
                "http://localhost:%d/video".formatted(wireMock.server().port()));

        assertThat(mimeType).isNull();
        wireMock.server().verify(0, anyRequestedFor(anyUrl()));
    }
}
