package com.comet.opik.infrastructure.llm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmModule;
import com.comet.opik.infrastructure.llm.customllm.LlmHttpClientBuilders;
import com.comet.opik.infrastructure.llm.openai.OpenAIModule;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.openai.core.http.HttpClient;
import jakarta.ws.rs.client.Client;
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

    public static final VideoMimeTypeUtils VIDEO_MIME_TYPE_UTILS = new VideoMimeTypeUtils(TestHttpClientUtils.client(),
            CONFIG);

    public static final HttpClient RESPONSES_API_HTTP_CLIENT = new OpenAIModule().responsesApiHttpClient(CONFIG);

    public static final LlmHttpClientBuilders LLM_HTTP_CLIENT_BUILDERS = llmHttpClientBuilders();

    private static LlmHttpClientBuilders llmHttpClientBuilders() {
        var module = new CustomLlmModule();
        return module.llmHttpClientBuilders(CONFIG, module.llmHttpClient(module.providerBaseUrlGuard(CONFIG)));
    }

    /** For tests serving media from a local mock server, which the strict mode of config-test.yml refuses. */
    public static RemoteMediaInliner remoteMediaInliner(DestinationGuard.Mode mode) {
        return remoteMediaInliner(mode, TestHttpClientUtils.client());
    }

    public static RemoteMediaInliner remoteMediaInliner(DestinationGuard.Mode mode, Client client) {
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();
        config.setVideoUrlDestinationGuard(mode);
        return new RemoteMediaInliner(client, config);
    }

    /** For tests of the schemes a media URL may use, which config-test.yml leaves at {@code plaintext_or_tls}. */
    public static RemoteMediaInliner remoteMediaInliner(DestinationGuard.Mode mode, DestinationGuard.Scheme scheme) {
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();
        config.setVideoUrlDestinationGuard(mode);
        config.setMediaUrlDestinationScheme(scheme);
        return new RemoteMediaInliner(TestHttpClientUtils.client(), config);
    }

    /** As {@link #remoteMediaInliner(DestinationGuard.Mode)}, for the MIME type lookup of video URLs. */
    public static VideoMimeTypeUtils videoMimeTypeUtils(DestinationGuard.Mode mode) {
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();
        config.setVideoUrlDestinationGuard(mode);
        return new VideoMimeTypeUtils(TestHttpClientUtils.client(), config);
    }
}
