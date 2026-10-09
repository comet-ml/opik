package com.comet.opik.domain;

import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.DashboardScope;
import com.comet.opik.api.DashboardWidgetQueryRequest;
import com.comet.opik.api.validation.InRangeValidator;
import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.redaction.RedactionGuard;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.google.common.base.Throwables;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Runs the query saved on an Ollie chart widget, so the browser asks for a widget's rows by reference instead of
 * sending SQL. The free-form SQL endpoint stays cluster-internal, for Ollie.
 */
@ImplementedBy(DashboardWidgetQueryServiceImpl.class)
public interface DashboardWidgetQueryService {

    /**
     * Runs the saved query of widget {@code widgetId} on dashboard {@code dashboardId} over the requested date range,
     * with the same gates, account and checks as Custom Charts free-form SQL.
     */
    AnalyticsQueryResponse runSavedQuery(UUID dashboardId, DashboardScope scope, String widgetId,
            DashboardWidgetQueryRequest request);
}

@Slf4j
@Singleton
class DashboardWidgetQueryServiceImpl implements DashboardWidgetQueryService {

    // The placeholders Ollie writes into a pinned query for the dashboard date range.
    static final String WINDOW_START_PLACEHOLDER = "{{window_start}}";
    static final String WINDOW_END_PLACEHOLDER = "{{window_end}}";

    // The range trace and span timestamps are validated against; DateTime64(9) ends at the exclusive maximum, and
    // ClickHouse would clamp a bound outside it silently rather than reject it.
    static final Instant MIN_BOUND = Instant.parse(InRangeValidator.MIN_ANALYTICS_DB);
    static final Instant MAX_BOUND_EXCLUSIVE = Instant.parse(InRangeValidator.MAX_ANALYTICS_DB_PRECISION_9);

    private final DashboardService dashboardService;
    private final FreeFormSqlQueryService freeFormSqlQueryService;
    private final Provider<RequestContext> requestContext;
    private final ServiceTogglesConfig serviceToggles;
    private final CustomChartsConfig customCharts;

    // Hand-written rather than Lombok's: image builds do not read lombok.config, so a generated constructor loses the
    // @Config qualifiers and customCharts is injected as an empty default instance (OPIK-8548).
    @Inject
    DashboardWidgetQueryServiceImpl(
            @NonNull DashboardService dashboardService,
            @NonNull FreeFormSqlQueryService freeFormSqlQueryService,
            @NonNull Provider<RequestContext> requestContext,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull @Config("customCharts") CustomChartsConfig customCharts) {
        this.dashboardService = dashboardService;
        this.freeFormSqlQueryService = freeFormSqlQueryService;
        this.requestContext = requestContext;
        this.serviceToggles = serviceToggles;
        this.customCharts = customCharts;
    }

    @Override
    public AnalyticsQueryResponse runSavedQuery(@NonNull UUID dashboardId, @NonNull DashboardScope scope,
            @NonNull String widgetId, @NonNull DashboardWidgetQueryRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        if (!serviceToggles.isOllieEnabled() || !customCharts.enabledWorkspaceIds().contains(workspaceId)) {
            throw new ServerErrorException(Response.Status.NOT_IMPLEMENTED);
        }

        RedactionGuard.rejectUnmaskable(requestContext.get().isRedactResponse(), "Custom Charts free-form SQL");

        Instant start = Optional.ofNullable(request.intervalStart()).orElse(Instant.EPOCH);
        Instant end = Optional.ofNullable(request.intervalEnd()).orElseGet(Instant::now);
        if (start.isAfter(end)) {
            throw new BadRequestException("interval_start must not be after interval_end");
        }
        if (start.isBefore(MIN_BOUND) || !end.isBefore(MAX_BOUND_EXCLUSIVE)) {
            throw new BadRequestException("interval_start must be at or after %s and interval_end before %s"
                    .formatted(MIN_BOUND, MAX_BOUND_EXCLUSIVE));
        }

        var savedQuery = OllieChartWidgets.findQuery(dashboardService.findById(dashboardId, scope).config(), widgetId)
                .orElseThrow(() -> new NotFoundException("No saved query for widget '%s'".formatted(widgetId)));

        log.info("Running saved query of widget '{}' on dashboard '{}' in workspace '{}', project '{}'", widgetId,
                dashboardId, workspaceId, savedQuery.projectId() == null ? "all" : savedQuery.projectId());

        String sql = bindWindow(savedQuery.sql(), start, end);
        try {
            return freeFormSqlQueryService
                    .executeQuery(FreeFormSqlAccount.EXTENDED, workspaceId, savedQuery.projectId(), sql)
                    .join();
        } catch (CompletionException e) {
            // Unwrap so the mapped WebApplicationException, and its HTTP status, reaches the JAX-RS handling.
            Throwables.throwIfUnchecked(e.getCause());
            throw e;
        }
    }

    /**
     * Substitutes the date range into the saved query. The caller's input is safe here because it arrives as a parsed
     * {@link Instant} and the text spliced in is formatted by the server, digits and separators only; the
     * {@code toDateTime64} wrapper only gives the value the {@code DateTime64(9)} type the placeholders stand for.
     */
    static String bindWindow(String sql, Instant start, Instant end) {
        return sql.replace(WINDOW_START_PLACEHOLDER, dateTimeExpression(start))
                .replace(WINDOW_END_PLACEHOLDER, dateTimeExpression(end));
    }

    private static String dateTimeExpression(Instant instant) {
        return "toDateTime64('%s', 9, 'UTC')".formatted(ClickHouseDateTimeFormat.formatNanos(instant));
    }
}
