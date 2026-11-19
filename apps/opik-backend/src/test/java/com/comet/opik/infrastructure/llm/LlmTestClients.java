package com.comet.opik.infrastructure.llm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmModule;
import com.comet.opik.infrastructure.llm.customllm.LlmHttpClientBuilders;
import com.comet.opik.infrastructure.llm.openai.OpenAIModule;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.openai.core.http.HttpClient;
import jakarta.ws.rs.client.Client;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * The provider dependencies the app injects, wired by the production modules from {@code config-test.yml}, for tests
 * that build generators and models by hand.
 */
@UtilityClass
public class LlmTestClients {

    private static final String LOOPBACK = "127.0.0.1";

    private static final LlmProviderClientConfig CONFIG = TestConfigUtils.loadConfigTest().getLlmProviderClient();

    public static final RemoteMediaInliner REMOTE_MEDIA_INLINER = remoteMediaInliner(
            CONFIG.getVideoUrlDestinationGuard());

    public static final VideoMimeTypeUtils VIDEO_MIME_TYPE_UTILS = new VideoMimeTypeUtils(TestHttpClientUtils.client(),
            CONFIG);

    public static final HttpClient RESPONSES_API_HTTP_CLIENT = new OpenAIModule().responsesApiHttpClient(CONFIG);

    public static final LlmHttpClientBuilders LLM_HTTP_CLIENT_BUILDERS = llmHttpClientBuilders();

    /**
     * Single-flight correctness belongs to {@code RedissonLockService}'s own tests (which is also
     * package-private); here the lock is a passthrough so the caller's orchestration is what's
     * under test.
     */
    public static final LockService PASSTHROUGH_LOCK_SERVICE = new LockService() {
        @Override
        public <T> Mono<T> executeWithLock(Lock lock, Mono<T> action) {
            return action;
        }

        @Override
        public <T> Mono<T> executeWithLockCustomExpire(Lock lock, Mono<T> action, Duration duration) {
            return action;
        }

        @Override
        public <T> Flux<T> executeWithLock(Lock lock, Flux<T> action) {
            return action;
        }

        @Override
        public <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction,
                Duration actionTimeout, Duration lockTimeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction,
                Duration actionTimeout, Duration lockTimeout, boolean holdUntilExpiry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Boolean> lockUsingToken(Lock lock, Duration lockDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> unlockUsingToken(Lock lock) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<String> tryAcquireSlot(Lock lock, int totalSlots, Duration leaseTime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Boolean> refreshSlot(Lock lock, String permitId, Duration leaseTime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Boolean> releaseSlot(Lock lock, String permitId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> addSlotPermits(Lock lock, int delta) {
            throw new UnsupportedOperationException();
        }
    };

    /**
     * A mock provider on 127.0.0.1 only. A server on every interface can share its port with another local process
     * bound to 127.0.0.1 alone, a language server say, which then gets the calls made to localhost; and localhost can
     * also resolve to ::1 first, where another process may listen on the same port.
     */
    public static WireMockServer newLoopbackWireMock() {
        return new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress(LOOPBACK));
    }

    /** The base URL of a {@link #newLoopbackWireMock()} server, by address rather than localhost. */
    public static String baseUrlOf(WireMockServer server) {
        return "http://%s:%d".formatted(LOOPBACK, server.port());
    }

    private static LlmHttpClientBuilders llmHttpClientBuilders() {
        var module = new CustomLlmModule();
        return module.llmHttpClientBuilders(CONFIG, module.llmHttpClient(CONFIG, module.providerBaseUrlGuard(CONFIG)));
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
