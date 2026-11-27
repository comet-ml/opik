package com.comet.opik.infrastructure.llm.openrouter;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Optional;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum OpenRouterRegion {
    GLOBAL("global", "openrouter.ai"),
    EU("eu", "eu.openrouter.ai"),
    US("us", "us.openrouter.ai"),
    UNKNOWN("unknown", null);

    @JsonValue
    private final String value;
    private final String host;

    public boolean isRegional() {
        return this == EU || this == US;
    }

    public static OpenRouterRegion fromUrl(String url) {
        return hostOf(url)
                .flatMap(host -> Arrays.stream(values())
                        .filter(region -> host.equalsIgnoreCase(region.host))
                        .findFirst())
                .orElse(UNKNOWN);
    }

    /**
     * Mirrors how the OpenRouter chat client picks its URL: the workspace key's base URL when it has one, otherwise
     * the server's {@code openRouterUrl}.
     */
    public static OpenRouterRegion effective(String workspaceBaseUrl, String serverUrl) {
        return fromUrl(StringUtils.isNotBlank(workspaceBaseUrl) ? workspaceBaseUrl : serverUrl);
    }

    private static Optional<String> hostOf(String url) {
        if (StringUtils.isBlank(url)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(new URI(url).getHost());
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
    }
}
