package com.comet.opik.infrastructure.llm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.openai.OpenAIModule;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.openai.core.http.HttpClient;
import lombok.experimental.UtilityClass;

/**
 * The provider dependencies the app injects, wired by the production modules from {@code config-test.yml}, for tests
 * that build generators and models by hand.
 */
@UtilityClass
public class LlmTestClients {

    private static final LlmProviderClientConfig CONFIG = TestConfigUtils.loadConfigTest().getLlmProviderClient();

    public static final RemoteMediaInliner REMOTE_MEDIA_INLINER = remoteMediaInliner(
            CONFIG.getVideoUrlDestinationGuard());

    public static final HttpClient RESPONSES_API_HTTP_CLIENT = new OpenAIModule().responsesApiHttpClient(CONFIG);

    /** For tests serving media from a local mock server, which the strict mode of config-test.yml refuses. */
    public static RemoteMediaInliner remoteMediaInliner(DestinationGuard.Mode mode) {
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();
        config.setVideoUrlDestinationGuard(mode);
        return new LlmModule().remoteMediaInliner(TestHttpClientUtils.client(), config);
    }
}
