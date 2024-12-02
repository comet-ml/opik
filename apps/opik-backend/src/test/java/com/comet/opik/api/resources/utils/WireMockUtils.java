package com.comet.opik.api.resources.utils;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import lombok.experimental.UtilityClass;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

@UtilityClass
public class WireMockUtils {
    public record WireMockRuntime(WireMockRuntimeInfo runtimeInfo, WireMockServer server) {
    }

    public static WireMockRuntime startWireMock() {
        final WireMockServer wireMockServer = new WireMockServer(wireMockConfig().dynamicPort().dynamicHttpsPort());

        wireMockServer.start();

        final WireMockRuntimeInfo runtimeInfo = new WireMockRuntimeInfo(wireMockServer);

        WireMock.configureFor(runtimeInfo.getWireMock());

        return new WireMockRuntime(runtimeInfo, wireMockServer);
    }
}
