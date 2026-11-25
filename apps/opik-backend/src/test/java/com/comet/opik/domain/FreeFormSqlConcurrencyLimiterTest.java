package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.DatabaseAnalyticsReadOnlyFreeFormSqlConfig;
import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.client.RedisConnectionException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FreeFormSqlConcurrencyLimiterTest {

    private static final int LIMIT = 3;

    private final LockService lockService = mock(LockService.class);
    private final String workspaceId = UUID.randomUUID().toString();
    private final String permitId = UUID.randomUUID().toString();
    private final LockService.Lock lock = FreeFormSqlConcurrencyLimiter.slotLock(workspaceId, LIMIT);

    private final FreeFormSqlConcurrencyLimiter limiter = newLimiter(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);

    /** Socket timeout 200s; enforced log reads 3 x 10s. */
    private FreeFormSqlConcurrencyLimiter newLimiter(FreeFormSqlPostRunCheckConfig.Mode mode) {
        var customCharts = new CustomChartsConfig();
        customCharts.setMaxConcurrentPublicApiQueriesPerWorkspace(LIMIT);
        var extended = new DatabaseAnalyticsReadOnlyFreeFormSqlConfig();
        extended.setSocketTimeout(io.dropwizard.util.Duration.seconds(200));
        var postRunCheck = new FreeFormSqlPostRunCheckConfig();
        postRunCheck.setMode(mode);
        postRunCheck.setLogReadDelay(io.dropwizard.util.Duration.seconds(10));
        postRunCheck.setMaxLogReadAttempts(3);
        when(lockService.releaseSlot(any(), any())).thenReturn(Mono.just(true));
        return new FreeFormSqlConcurrencyLimiter(lockService, customCharts, extended, postRunCheck);
    }

    @ParameterizedTest
    @CsvSource({"OFF, 260", "AUDIT, 260", "ENFORCE, 290"})
    @DisplayName("the slot lease covers the socket timeout, the enforced log reads and a 60s margin")
    void run__whenAcquiring__thenLeaseDerivedFromTimeouts(FreeFormSqlPostRunCheckConfig.Mode mode,
            long expectedLeaseSeconds) {
        when(lockService.tryAcquireSlot(eq(lock), eq(LIMIT), eq(Duration.ofSeconds(expectedLeaseSeconds))))
                .thenReturn(Mono.just(permitId));
        var result = UUID.randomUUID().toString();

        assertThat(newLimiter(mode).run(workspaceId, () -> CompletableFuture.completedFuture(result)).join())
                .isEqualTo(result);
        verify(lockService).releaseSlot(lock, permitId);
    }

    @Test
    @DisplayName("a free slot runs the query and is released only once it completes")
    void run__whenSlotFree__thenRunsAndReleasesOnCompletion() {
        when(lockService.tryAcquireSlot(eq(lock), eq(LIMIT), any())).thenReturn(Mono.just(permitId));
        var query = new CompletableFuture<String>();
        var result = UUID.randomUUID().toString();

        var actual = limiter.run(workspaceId, () -> query);
        verify(lockService, never()).releaseSlot(any(), any());

        query.complete(result);
        assertThat(actual.join()).isEqualTo(result);
        verify(lockService).releaseSlot(lock, permitId);
    }

    @Test
    @DisplayName("a failed query releases its slot only once it fails")
    void run__whenQueryFails__thenReleasesOnFailure() {
        when(lockService.tryAcquireSlot(eq(lock), eq(LIMIT), any())).thenReturn(Mono.just(permitId));
        var query = new CompletableFuture<String>();
        var failure = new IllegalStateException(UUID.randomUUID().toString());

        var actual = limiter.run(workspaceId, () -> query);
        verify(lockService, never()).releaseSlot(any(), any());

        query.completeExceptionally(failure);
        assertThatThrownBy(actual::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
        verify(lockService).releaseSlot(lock, permitId);
    }

    @Test
    @DisplayName("every slot taken returns 429 with Retry-After and the limiter's message, without running the query")
    void run__whenSlotsFull__thenTooManyRequests() {
        when(lockService.tryAcquireSlot(eq(lock), eq(LIMIT), any())).thenReturn(Mono.empty());
        var ran = new AtomicBoolean();

        assertThatThrownBy(() -> limiter.run(workspaceId, () -> {
            ran.set(true);
            return CompletableFuture.completedFuture(UUID.randomUUID().toString());
        }).join())
                .cause()
                .isInstanceOfSatisfying(ClientErrorException.class, error -> {
                    assertThat(error.getResponse().getStatus())
                            .isEqualTo(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
                    assertThat(error.getResponse().getHeaderString(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
                    assertThat(error.getResponse().getEntity()).isEqualTo(
                            new ErrorMessage(List.of(FreeFormSqlConcurrencyLimiter.TOO_MANY_QUERIES_MESSAGE)));
                });
        assertThat(ran).isFalse();
        verify(lockService, never()).releaseSlot(any(), any());
    }

    @Test
    @DisplayName("a Redis failure runs the query without the limit")
    void run__whenRedisFails__thenRunsWithoutLimit() {
        when(lockService.tryAcquireSlot(eq(lock), eq(LIMIT), any()))
                .thenReturn(Mono.error(new RedisConnectionException(UUID.randomUUID().toString())));
        var result = UUID.randomUUID().toString();

        assertThat(limiter.run(workspaceId, () -> CompletableFuture.completedFuture(result)).join())
                .isEqualTo(result);
        verify(lockService, never()).releaseSlot(any(), any());
    }

    @Test
    @DisplayName("an error other than a Redis failure fails the request instead of dropping the limit")
    void run__whenNonRedisError__thenFails() {
        var failure = new IllegalArgumentException(UUID.randomUUID().toString());
        when(lockService.tryAcquireSlot(eq(lock), eq(LIMIT), any())).thenReturn(Mono.error(failure));
        var ran = new AtomicBoolean();

        assertThatThrownBy(() -> limiter.run(workspaceId, () -> {
            ran.set(true);
            return CompletableFuture.completedFuture(UUID.randomUUID().toString());
        }).join())
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
        assertThat(ran).isFalse();
    }
}
