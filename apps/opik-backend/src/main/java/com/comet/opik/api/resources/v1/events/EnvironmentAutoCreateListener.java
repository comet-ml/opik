package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.EnvironmentService;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.Set;
import java.util.stream.Collectors;

@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class EnvironmentAutoCreateListener {

    private final @NonNull EnvironmentService environmentService;

    @Subscribe
    public void onTracesCreated(@NonNull TracesCreated event) {
        Set<String> names = event.traces().stream()
                .map(Trace::environment)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

        if (names.isEmpty()) {
            return;
        }

        Mono.fromRunnable(() -> environmentService.bulkCreate(names, event.workspaceId(), event.userName()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> log.error(
                        "Failed to auto-create environments for workspace_id '{}'",
                        event.workspaceId(), error))
                .onErrorResume(error -> Mono.empty())
                .subscribe();
    }
}
