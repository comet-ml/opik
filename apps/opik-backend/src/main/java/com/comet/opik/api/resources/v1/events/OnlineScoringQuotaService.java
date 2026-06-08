package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;

/**
 * Per-workspace producer quota for online scoring (OPIK-6813 B2). Caps how many scoring evals a
 * single workspace may enqueue per time window — fleet-wide, via the distributed
 * {@link RateLimitService} — so one workspace's automation rules can't flood the shared Redis
 * streams and starve other tenants. Applied at the producer, after the per-rule sampling rate, so
 * the over-quota remainder is shed before it ever reaches Redis. Disabled by default.
 */
@Singleton
@Slf4j
public class OnlineScoringQuotaService {

    private static final String BUCKET_NAME = "online_scoring_quota:%s";
    private static final String METER_NAME = "opik.online_scoring";
    private static final AttributeKey<String> EVALUATOR_TYPE_KEY = AttributeKey.stringKey("evaluator_type");
    private static final AttributeKey<String> WORKSPACE_ID_KEY = AttributeKey.stringKey("workspace_id");
    private static final AttributeKey<String> RULE_ID_KEY = AttributeKey.stringKey("rule_id");

    private final RateLimitService rateLimitService;
    private final OnlineScoringConfig.PerWorkspaceQuota quota;
    private final LongCounter admittedEvals;
    private final LongCounter droppedEvals;

    @Inject
    public OnlineScoringQuotaService(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
        this.quota = config.getPerWorkspaceQuota();
        var meter = GlobalOpenTelemetry.get().getMeter(METER_NAME);
        this.admittedEvals = meter
                .counterBuilder("online_scoring_quota_admitted")
                .setDescription("Online-scoring evals admitted under the per-workspace quota")
                .build();
        this.droppedEvals = meter
                .counterBuilder("online_scoring_quota_dropped")
                .setDescription("Online-scoring evals dropped because the per-workspace quota was exceeded")
                .build();
    }

    /**
     * Returns the sublist of {@code items} admitted under the workspace quota, dropping the
     * over-quota remainder (best-effort: shed at the source, never deferred or retried). When the
     * quota is disabled the input is returned unchanged. On any rate-limiter error it fails open
     * (admits all) so a limiter hiccup never stops scoring.
     */
    public <T> List<T> admit(@NonNull String workspaceId, @NonNull UUID ruleId, @NonNull String evaluatorType,
            @NonNull List<T> items) {
        if (items.isEmpty() || !quota.isEnabled() || quota.getLimit() <= 0) {
            return items;
        }
        int requested = items.size();
        int admitted = tryAdmit(workspaceId, requested);
        record(workspaceId, ruleId, evaluatorType, admitted, requested - admitted);
        return admitted >= requested ? items : items.subList(0, admitted);
    }

    private int tryAdmit(String workspaceId, int requested) {
        var bucket = BUCKET_NAME.formatted(workspaceId);
        var limitConfig = new LimitConfig("NA", "online_scoring_quota", quota.getLimit(),
                quota.getDurationInSeconds(), "");
        try {
            long available = rateLimitService.availableEvents(bucket, limitConfig).blockOptional().orElse(0L);
            int toAdmit = (int) Math.min(available, requested);
            if (toAdmit <= 0) {
                return 0;
            }
            boolean exceeded = Boolean.TRUE.equals(
                    rateLimitService.isLimitExceeded(toAdmit, bucket, limitConfig).block());
            return exceeded ? 0 : toAdmit;
        } catch (RuntimeException exception) {
            // Fail open: a limiter hiccup must never stop scoring (the trace is already ingested).
            log.warn("Online scoring quota check failed for workspaceId '{}', admitting all", workspaceId, exception);
            return requested;
        }
    }

    private void record(String workspaceId, UUID ruleId, String evaluatorType, int admitted, int dropped) {
        if (admitted > 0) {
            admittedEvals.add(admitted, Attributes.of(EVALUATOR_TYPE_KEY, evaluatorType));
        }
        if (dropped > 0) {
            // workspace_id + rule_id ride only on the DROPPED counter: it fires solely for over-quota
            // offenders (a small set), so the per-customer/per-rule labels stay within the metrics
            // cardinality budget while giving full drop visibility in Grafana.
            droppedEvals.add(dropped, Attributes.of(
                    EVALUATOR_TYPE_KEY, evaluatorType, WORKSPACE_ID_KEY, workspaceId, RULE_ID_KEY, ruleId.toString()));
            log.info("Online scoring quota dropped '{}' of '{}' evals for workspaceId '{}', ruleId '{}', type '{}'",
                    dropped, admitted + dropped, workspaceId, ruleId, evaluatorType);
        }
    }
}
