package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;

/**
 * Reads a run's window: all of it, or an even sample of {@code topics.run.maxVectors} traces when it is larger. Rows
 * with no text after stripping, or a vector of the wrong size, are skipped. Any failure is {@code window_read}. The
 * caller counts the window: the discovery job counts it to pick the project. The window is handed off the ClickHouse
 * driver's thread, to {@code boundedElastic}, so the caller's CPU steps don't run on the thread the API's reads share.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicWindowReader {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicWindowDAO windowDAO;

    /** {@code topics.run.readTimeout}, or the project timeout when shorter. */
    static Duration readTimeout(@NonNull TopicsConfig.RunConfig runConfig) {
        var read = runConfig.getReadTimeout().toJavaDuration();
        var project = runConfig.getProjectTimeout().toJavaDuration();
        return read.compareTo(project) < 0 ? read : project;
    }

    /**
     * The window, or its sample. {@code windowTraces} is the caller's count of the window: the stats keep it, and it
     * sizes the arrays the rows are copied into as they arrive, so the window is held once.
     */
    Mono<RunWindow> read(@NonNull TopicRun run, long windowTraces) {
        int limit = config.getRun().getMaxVectors();
        var readTimeout = readTimeout(config.getRun());
        int capacity = (int) Math.max(1, Math.min(windowTraces, limit));
        return windowDAO.readWindow(run.workspaceId(), run.projectId(), run.from(), run.to(), limit,
                readTimeout.toSeconds())
                .collect(() -> new TopicWindowBuilder(capacity), TopicWindowBuilder::add)
                .publishOn(Schedulers.boundedElastic())
                .map(builder -> builder.build(windowTraces, config.getAlgorithm().getProvisionalBelow()))
                .timeout(readTimeout)
                .onErrorMap(error -> !(error instanceof TopicsRunException),
                        error -> new TopicsRunException(RunError.WINDOW_READ, "the window read failed", error));
    }
}
