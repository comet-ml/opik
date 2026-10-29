package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LlmModelRegistryTestUtils {

    private static final LlmModelRegistryService BUNDLED_REGISTRY = new LlmModelRegistryService(
            new LlmModelRegistryConfig());

    /** The registry a deployment without a CDN or local override serves: the files shipped with the release. */
    public static LlmModelRegistryService bundledRegistry() {
        return BUNDLED_REGISTRY;
    }

    public static LlmModelRegistryService registry(LlmModelRegistryConfig config) {
        return new LlmModelRegistryService(config);
    }
}
