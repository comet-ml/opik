package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import uk.co.jemos.podam.api.PodamFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Shared Token Cache Test")
class SharedTokenCacheTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final SharedTokenCache tokenCache = new SharedTokenCache(emptyRedis(),
            LlmTestClients.PASSTHROUGH_LOCK_SERVICE);

    /** Every read misses, so every call fetches. */
    @SuppressWarnings("unchecked")
    private static StringRedisClient emptyRedis() {
        var redisClient = mock(StringRedisClient.class);
        when(redisClient.getBucket(anyString())).thenReturn(mock(RBucket.class));
        return redisClient;
    }

    /**
     * The lock's lease isn't renewed, so a fetch must end inside it: an abandoned request still running would let
     * another pod's fetch overlap it, and repeated deadlines would pile requests up on a stalled endpoint.
     */
    @Test
    @DisplayName("a fetch past its deadline is cancelled and its connection closed")
    void fetchPastItsDeadlineIsCancelled() throws Exception {
        try (var stalledEndpoint = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var connectionClosed = CompletableFuture.supplyAsync(() -> {
                try (var connection = stalledEndpoint.accept(); InputStream request = connection.getInputStream()) {
                    // never replies; the read ends when the client closes its side
                    return request.readAllBytes().length > 0;
                } catch (IOException exception) {
                    return true;
                }
            });
            var fetchTimeout = Duration.ofMillis(500);

            var startNanos = System.nanoTime();
            var thrown = catchThrowable(() -> tokenCache.get(podamFactory.manufacturePojo(String.class),
                    Duration.ofSeconds(1), fetchTimeout,
                    () -> fetchFrom(stalledEndpoint.getLocalPort())));
            var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertThat(thrown).isInstanceOf(SharedTokenCache.FetchTimeoutException.class)
                    .hasMessage("Fetch took longer than 'PT0.5S'");
            assertThat(elapsedMillis).isLessThan(2_000);
            assertThat(connectionClosed.get(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static SharedTokenCache.Fetched fetchFrom(int port) {
        try {
            var connection = (HttpURLConnection) URI.create("http://127.0.0.1:%d/token".formatted(port)).toURL()
                    .openConnection();
            connection.setReadTimeout(60_000);
            return new SharedTokenCache.Fetched(String.valueOf(connection.getResponseCode()), Duration.ZERO);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
