package com.comet.opik.domain.retention;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.retention.RetentionPeriod;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.infrastructure.RetentionConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.domain.retention.RetentionUtils.extractInstant;
import static com.comet.opik.domain.retention.RetentionUtils.isTooManyRowsException;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Estimates workspace velocity and computes catch-up cursors for retention rules.
 * Called by RetentionEstimationJob as a background task, NOT during HTTP request handling.
 *
 * Estimation flow:
 * 1. Query ClickHouse for uniq(id)/weeks → velocity + oldest span time
 * 2. If query succeeds → velocity + oldest span as cursor start
 * 3. If query fails with TOO_MANY_ROWS → default velocity + scout month-by-month for first data
 * 4. If scouting finds nothing → mark catch-up done (no historical data)
 */
@Slf4j
@Singleton
public class RetentionEstimationService {

    private final TransactionTemplate template;
    private final SpanDAO spanDAO;
    private final TraceDAO traceDAO;
    private final InstantToUUIDMapper uuidMapper;
    private final RetentionConfig config;

    private final LongCounter rulesEstimated;
    private final LongHistogram velocityValues;

    @Inject
    public RetentionEstimationService(
            @NonNull TransactionTemplate template,
            @NonNull SpanDAO spanDAO,
            @NonNull TraceDAO traceDAO,
            @NonNull InstantToUUIDMapper uuidMapper,
            @NonNull @Config("retention") RetentionConfig config) {
        this.template = template;
        this.spanDAO = spanDAO;
        this.traceDAO = traceDAO;
        this.uuidMapper = uuidMapper;
        this.config = config;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.retention");

        this.rulesEstimated = meter
                .counterBuilder("opik.retention.estimation.rules_estimated")
                .setDescription("Number of rules that completed velocity estimation")
                .build();

        this.velocityValues = meter
                .histogramBuilder("opik.retention.estimation.velocity")
                .setDescription("Estimated velocity (spans/week) for rules")
                .setUnit("spans/week")
                .ofLongs()
                .build();
    }

    /**
     * Find all rules pending estimation and estimate their velocity + cursor.
     * Called by the RetentionEstimationJob on each cycle.
     */
    public void estimatePendingRules() {
        // Limit rules per cycle to avoid unbounded work — each estimation can trigger
        // ClickHouse queries (velocity scan + month-by-month scouting in worst case)
        List<RetentionRule> pending = template.inTransaction(READ_ONLY,
                handle -> handle.attach(RetentionRuleDAO.class).findUnestimatedCatchUpRules(10));

        if (pending.isEmpty()) {
            log.debug("No rules pending velocity estimation");
            return;
        }

        log.info("Estimating velocity for '{}' pending rules", pending.size());

        for (var rule : pending) {
            try {
                estimateAndUpdate(rule);
            } catch (Exception e) {
                log.error("Failed to estimate velocity for rule '{}' in workspace '{}'",
                        rule.id(), rule.workspaceId(), e);
            }
        }
    }

    private void estimateAndUpdate(RetentionRule rule) {
        Instant now = Instant.now();
        var estimation = estimateVelocity(rule.workspaceId(), rule.retention(), now);

        if (estimation.startCursor() == null) {
            // No data found — mark catch-up done immediately
            log.info("No historical data for rule '{}' in workspace '{}', marking catch-up done",
                    rule.id(), rule.workspaceId());
            template.inTransaction(WRITE, handle -> {
                handle.attach(RetentionRuleDAO.class).markCatchUpDone(rule.id());
                return null;
            });
        } else {
            log.info("Estimated velocity for rule '{}' in workspace '{}': '{}' spans/week, cursor='{}'",
                    rule.id(), rule.workspaceId(), estimation.velocity(), estimation.startCursor());
            template.inTransaction(WRITE, handle -> {
                handle.attach(RetentionRuleDAO.class).updateVelocityAndCursor(
                        rule.id(), estimation.velocity(), estimation.startCursor());
                return null;
            });
            velocityValues.record(estimation.velocity());
        }
        rulesEstimated.add(1);
    }

    /**
     * Estimate the span velocity for a workspace and compute the catch-up start cursor.
     * On success, uses the oldest span timestamp as cursor start.
     * If the estimation query fails with TOO_MANY_ROWS, falls back to scouting.
     */
    // Package-visible for unit testing (TOO_MANY_ROWS path requires mocked DAOs since
    // ClickHouse's max_rows_to_read profile setting also blocks normal INSERT/SELECT operations,
    // making it impossible to test via integration tests with a real ClickHouse container)
    VelocityEstimation estimateVelocity(String workspaceId, RetentionPeriod period, Instant now) {
        var catchUpConfig = config.getCatchUp();

        Instant cutoff = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .minusDays(period.getDays())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        UUID cutoffId = uuidMapper.toLowerBound(cutoff);

        UUID fallbackCursor = uuidMapper.toLowerBound(
                catchUpConfig.getServiceStartDate().atStartOfDay(ZoneOffset.UTC).toInstant());

        try {
            var estimate = spanDAO.estimateVelocityForRetention(workspaceId, cutoffId).block();
            if (estimate == null || estimate.spansPerWeek() == 0) {
                log.info("Retention velocity estimated for workspace '{}': no data found", workspaceId);
                return new VelocityEstimation(0L, null);
            }

            UUID startCursor = estimate.oldestSpanTime() != null
                    ? uuidMapper.toLowerBound(estimate.oldestSpanTime())
                    : fallbackCursor;

            log.info("Retention velocity estimated for workspace '{}': '{}' spans/week, oldest='{}'",
                    workspaceId, estimate.spansPerWeek(), estimate.oldestSpanTime());
            return new VelocityEstimation(estimate.spansPerWeek(), startCursor);
        } catch (Exception e) {
            if (isTooManyRowsException(e)) {
                log.info("Retention velocity estimation hit row limit for workspace '{}', scouting for first data",
                        workspaceId);
                UUID scoutedCursor = scoutFirstDataCursor(workspaceId, fallbackCursor, cutoffId);
                if (scoutedCursor == null) {
                    return new VelocityEstimation(0L, null);
                }
                return new VelocityEstimation(catchUpConfig.getDefaultVelocity(), scoutedCursor);
            }
            throw e;
        }
    }

    /**
     * For huge workspaces where the full estimation query failed, scan month by month
     * from the service start date to find the first day with actual trace data.
     * If a monthly scan also hits TOO_MANY_ROWS, the start of that month is the cursor.
     */
    // Package-visible for unit testing
    UUID scoutFirstDataCursor(String workspaceId, UUID serviceStartCursor, UUID cutoffId) {
        Instant serviceStart = extractInstant(serviceStartCursor);
        Instant cutoff = extractInstant(cutoffId);
        Instant monthStart = serviceStart;

        while (monthStart.isBefore(cutoff)) {
            Instant monthEnd = monthStart.plus(30, ChronoUnit.DAYS);
            if (monthEnd.isAfter(cutoff)) {
                monthEnd = cutoff;
            }

            UUID rangeStart = uuidMapper.toLowerBound(monthStart);
            UUID rangeEnd = uuidMapper.toLowerBound(monthEnd);

            try {
                Instant firstDay = traceDAO.scoutFirstDayWithData(workspaceId, rangeStart, rangeEnd).block();
                if (firstDay != null && !firstDay.equals(Instant.MAX)) {
                    log.info("Scouting found first data for workspace '{}' at '{}'", workspaceId, firstDay);
                    return uuidMapper.toLowerBound(firstDay);
                }
                log.debug("Scouting: no data in range ['{}', '{}') for workspace '{}'",
                        monthStart, monthEnd, workspaceId);
            } catch (Exception ex) {
                if (isTooManyRowsException(ex)) {
                    log.info("Scouting hit row limit for workspace '{}' at '{}', using as cursor",
                            workspaceId, monthStart);
                    return uuidMapper.toLowerBound(monthStart);
                }
                throw ex;
            }

            monthStart = monthEnd;
        }

        log.info("Scouting found no data for workspace '{}', no catch-up needed", workspaceId);
        return null;
    }

    record VelocityEstimation(long velocity, UUID startCursor) {
    }
}
