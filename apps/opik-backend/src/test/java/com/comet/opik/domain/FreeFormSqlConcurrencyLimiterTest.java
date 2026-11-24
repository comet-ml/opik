package com.comet.opik.domain;

import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    private final FreeFormSqlConcurrencyLimiter limiter = newLimiter();

    private FreeFormSqlConcurrencyLimiter newLimiter() {
        var customCharts = new CustomChartsConfig();
        customCharts.setMaxConcurrentQueriesPerWorkspace(LIMIT);
        when(lockService.releaseSlot(any(), any())).thenReturn(Mono.just(true));
        return new FreeFormSqlConcurrencyLimiter(lockService, customCharts);
    }

    @Test
    @DisplayName("a free slot runs the query and is released once it completes")
    void run__whenSlotFree__thenRunsAndReleases() {
        when(lockService.tryAcquireSlot(eq(lock), eq(LIMIT), any())).thenReturn(Mono.just(permitId));
        var result = UUID.randomUUID().toString();

        assertThat(limiter.run(workspaceId, () -> CompletableFuture.completedFuture(result)).join())
                .isEqualTo(result);
        verify(lockService).releaseSlot(lock, permitId);
    }

    @Test
    @DisplayName("a failed query still releases its slot")
    void run__whenQueryFails__thenReleases() {
        when(lockService.tryAcquireSlot(eq(lock), eq(LIMIT), any())).thenReturn(Mono.just(permitId));
        var failure = new IllegalStateException(UUID.randomUUID().toString());

        assertThatThrownBy(() -> limiter.run(workspaceId, () -> CompletableFuture.failedFuture(failure)).join())
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
        verify(lockService).releaseSlot(lock, permitId);
    }

    @Test
    @DisplayName("every slot taken returns 429 with Retry-After without running the query")
    void run__whenSlotsFull__thenTooManyRequests() {
        when(lockService.tryAcquireSlot(eq(lock), eq(LIMIT), any())).thenReturn(Mono.empty());
        var ran = new AtomicBoolean();

        assertThatThrownBy(() -> limiter.run(workspaceId, () -> {
            ran.set(true);
            return CompletableFuture.completedFuture("unused");
        }).join())
                .cause()
                .isInstanceOfSatisfying(ClientErrorException.class, error -> {
                    assertThat(error.getResponse().getStatus()).isEqualTo(429);
                    assertThat(error.getResponse().getHeaderString(HttpHeaders.RETRY_AFTER))
                            .isEqualTo(FreeFormSqlQueryService.RETRY_AFTER_SECONDS);
                });
        assertThat(ran).isFalse();
        verify(lockService, never()).releaseSlot(any(), any());
    }

    @Test
    @DisplayName("an unreachable Redis runs the query without the limit")
    void run__whenRedisFails__thenRunsWithoutLimit() {
        when(lockService.tryAcquireSlot(any(), anyInt(), any()))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));
        var result = UUID.randomUUID().toString();

        assertThat(limiter.run(workspaceId, () -> CompletableFuture.completedFuture(result)).join())
                .isEqualTo(result);
        verify(lockService, never()).releaseSlot(any(), any());
    }
}
