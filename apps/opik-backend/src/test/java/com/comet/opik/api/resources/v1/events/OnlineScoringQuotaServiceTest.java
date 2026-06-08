package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnlineScoringQuotaService")
class OnlineScoringQuotaServiceTest {

    private static final String WORKSPACE_ID = "workspace-x";
    private static final String EVALUATOR_TYPE = "llm_as_judge";
    private final UUID ruleId = UUID.randomUUID();

    @Mock
    private RateLimitService rateLimitService;

    private OnlineScoringQuotaService service(boolean enabled, long limit) {
        var config = new OnlineScoringConfig();
        config.setPerWorkspaceQuota(OnlineScoringConfig.PerWorkspaceQuota.builder()
                .enabled(enabled)
                .limit(limit)
                .durationInSeconds(60)
                .build());
        return new OnlineScoringQuotaService(config, rateLimitService);
    }

    @Test
    void admitsAllWhenDisabled() {
        var items = List.of("a", "b", "c");

        var admitted = service(false, 0).admit(WORKSPACE_ID, ruleId, EVALUATOR_TYPE, items);

        assertThat(admitted).isEqualTo(items);
        verifyNoInteractions(rateLimitService);
    }

    @Test
    void admitsAllWhenEnabledButLimitZero() {
        // enabled with limit=0 (the default) is a no-op quota — fail open, never touch the limiter.
        var items = List.of("a", "b", "c");

        var admitted = service(true, 0).admit(WORKSPACE_ID, ruleId, EVALUATOR_TYPE, items);

        assertThat(admitted).isEqualTo(items);
        verifyNoInteractions(rateLimitService);
    }

    @Test
    void admitsEmptyInputWithoutCallingLimiter() {
        var admitted = service(true, 100).admit(WORKSPACE_ID, ruleId, EVALUATOR_TYPE, List.of());

        assertThat(admitted).isEmpty();
        verifyNoInteractions(rateLimitService);
    }

    @Test
    void admitsAllWhenUnderLimit() {
        when(rateLimitService.availableEvents(any(), any())).thenReturn(Mono.just(100L));
        when(rateLimitService.isLimitExceeded(eq(3L), any(), any())).thenReturn(Mono.just(false));
        var items = List.of("a", "b", "c");

        var admitted = service(true, 100).admit(WORKSPACE_ID, ruleId, EVALUATOR_TYPE, items);

        assertThat(admitted).isEqualTo(items);
    }

    @Test
    void downSamplesToAvailableWhenOverLimit() {
        // Only 2 permits remain in the window for 5 requested → admit the first 2, drop the rest.
        when(rateLimitService.availableEvents(any(), any())).thenReturn(Mono.just(2L));
        when(rateLimitService.isLimitExceeded(eq(2L), any(), any())).thenReturn(Mono.just(false));
        var items = List.of("a", "b", "c", "d", "e");

        var admitted = service(true, 100).admit(WORKSPACE_ID, ruleId, EVALUATOR_TYPE, items);

        assertThat(admitted).containsExactly("a", "b");
    }

    @Test
    void dropsAllWhenNoPermitsAvailable() {
        when(rateLimitService.availableEvents(any(), any())).thenReturn(Mono.just(0L));
        var items = List.of("a", "b", "c");

        var admitted = service(true, 100).admit(WORKSPACE_ID, ruleId, EVALUATOR_TYPE, items);

        assertThat(admitted).isEmpty();
        // No point trying to consume when nothing is available.
        verify(rateLimitService, never()).isLimitExceeded(anyLong(), any(), any());
    }

    @Test
    void dropsAllWhenAcquireRaceLost() {
        // availableEvents saw room, but another pod consumed it before we acquired.
        when(rateLimitService.availableEvents(any(), any())).thenReturn(Mono.just(3L));
        when(rateLimitService.isLimitExceeded(eq(3L), any(), any())).thenReturn(Mono.just(true));
        var items = List.of("a", "b", "c");

        var admitted = service(true, 100).admit(WORKSPACE_ID, ruleId, EVALUATOR_TYPE, items);

        assertThat(admitted).isEmpty();
    }

    @Test
    void failsOpenWhenLimiterErrors() {
        // A limiter hiccup must never stop scoring — admit everything.
        when(rateLimitService.availableEvents(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("redis unavailable")));
        var items = List.of("a", "b", "c");

        var admitted = service(true, 100).admit(WORKSPACE_ID, ruleId, EVALUATOR_TYPE, items);

        assertThat(admitted).isEqualTo(items);
    }

    @Test
    void usesPerWorkspaceBucket() {
        when(rateLimitService.availableEvents(eq("online_scoring_quota:%s".formatted(WORKSPACE_ID)),
                any(LimitConfig.class))).thenReturn(Mono.just(100L));
        when(rateLimitService.isLimitExceeded(eq(1L),
                eq("online_scoring_quota:%s".formatted(WORKSPACE_ID)), any(LimitConfig.class)))
                .thenReturn(Mono.just(false));

        var admitted = service(true, 100).admit(WORKSPACE_ID, ruleId, EVALUATOR_TYPE, List.of("a"));

        assertThat(admitted).containsExactly("a");
    }
}
