package com.comet.opik.infrastructure.bi;

import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UsageReportConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.annotations.On;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple4;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.comet.opik.infrastructure.bi.UsageReportService.UserCount;

@Slf4j
@On(value = "0 0 0 * * ?", timeZone = "UTC") // every day at midnight
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DailyUsageReport extends io.dropwizard.jobs.Job {

    public static final String STATISTICS_BE = "opik_os_statistics_be";

    private final @NonNull UsageReportService usageReportService;
    private final @NonNull @Config UsageReportConfig.ServerStatsConfig serverStats;
    private final @NonNull LockService lockService;
    private final @NonNull OpikConfiguration config;
    private final @NonNull Client client;
    private final @NonNull TraceService traceService;
    private final @NonNull ExperimentService experimentService;
    private final @NonNull DatasetService datasetService;

    @Override
    public void doJob(JobExecutionContext jobExecutionContext) {

        if (!serverStats.enabled()) {
            log.info("Server stats are disabled, skipping daily usage report");
            return;
        }

        var lock = new LockService.Lock("daily_usage_report");

        lockService.executeWithLock(lock, Mono.defer(this::generateReportInternal))
                .subscribe(
                        result -> log.info("Daily usage report generated"),
                        error -> log.error("Failed to generate daily usage report", error));
    }

    private Mono<Void> generateReportInternal() {
        if (!usageReportService.shouldSendDailyReport()) {
            log.info("Daily usage report already sent");
            return Mono.empty();
        }

        var anonymousId = usageReportService.getAnonymousId().orElseThrow();

        return reportEvent(anonymousId);
    }

    private Mono<Void> reportEvent(String anonymousId) {
        return fetchAllReportData()
                .map(results -> mapResults(anonymousId, results))
                .flatMap(this::sendEvent)
                .flatMap(this::processResponse)
                .then();
    }

    private Mono<Void> processResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL && response.hasEntity()) {

            var notificationEventResponse = response.readEntity(NotificationEventResponse.class);

            if (notificationEventResponse.success()) {
                usageReportService.markDailyReportAsSent();
                log.info("Event reported successfully: {}", notificationEventResponse.message());
            } else {
                log.warn("Failed to report event: {}", notificationEventResponse.message());
            }

            return Mono.empty();
        }

        log.warn("Failed to report event: {}", response.getStatusInfo());
        if (response.hasEntity()) {
            log.warn("Response: {}", response.readEntity(String.class));
        }

        log.info("Daily usage report not send");

        return Mono.empty();
    }

    private Mono<Tuple4<UserCount, Long, Long, Long>> fetchAllReportData() {
        return Mono.zip(
                usageReportService.getUserCount(),
                traceService.getDailyCreatedCount(),
                experimentService.getDailyCreatedCount(),
                Mono.fromCallable(datasetService::getDailyCreatedCount)
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private BiEvent mapResults(String anonymousId, Tuple4<UserCount, Long, Long, Long> results) {
        return new BiEvent(
                anonymousId,
                STATISTICS_BE,
                Map.of(
                        "opik_app_version", config.getMetadata().getVersion(),
                        "total_users", String.valueOf(results.getT1().allTimes()),
                        "daily_users", String.valueOf(results.getT1().daily()),
                        "daily_traces", String.valueOf(results.getT2()),
                        "daily_experiments", String.valueOf(results.getT3()),
                        "daily_datasets", String.valueOf(results.getT4())));
    }

    private Mono<Response> sendEvent(BiEvent biEvent) {

        if (hasNoDataToSubmit(biEvent)) {
            log.error("No data to process");
            return Mono.empty();
        }

        return Mono.fromFuture(
                () -> (CompletableFuture<Response>) client.target(URI.create(config.getUsageReport().getUrl()))
                        .request()
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .async()
                        .post(Entity.json(biEvent)));
    }

    private boolean hasNoDataToSubmit(BiEvent biEvent) {
        return biEvent.eventProperties().entrySet().stream().allMatch(e -> {
            if (!e.getKey().equals("opik_app_version") && !e.getKey().equals("total_users")) {
                return e.getValue().equals("0");
            }

            if (e.getKey().equals("total_users")) {
                // this will probably be 1 due to the migration to create the default project
                return e.getValue().equals("1") || e.getValue().equals("0");
            }

            return true;
        });
    }

}
