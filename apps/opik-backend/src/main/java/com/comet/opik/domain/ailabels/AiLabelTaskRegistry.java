package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.AiLabelsConfig;
import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The tasks to label with. They are loaded and validated at startup when ai-labels is enabled, so an invalid task stops
 * the process instead of failing every job it would be in.
 */
@Slf4j
@EagerSingleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AiLabelTaskRegistry implements Managed {

    private final @NonNull @Config("aiLabels") AiLabelsConfig config;

    private volatile List<AiLabelTask> tasks;

    @Override
    public void start() {
        if (config.isEnabled()) {
            tasks();
        }
    }

    /** All tasks, by name. */
    public List<AiLabelTask> tasks() {
        var loaded = tasks;
        if (loaded == null) {
            synchronized (this) {
                loaded = tasks;
                if (loaded == null) {
                    loaded = AiLabelTaskLoader.load(config).stream()
                            .sorted(Comparator.comparing(AiLabelTask::name))
                            .toList();
                    tasks = loaded;
                }
            }
        }
        return loaded;
    }

    public Optional<AiLabelTask> find(@NonNull String name) {
        return tasks().stream().filter(task -> task.name().equals(name)).findFirst();
    }

    public AiLabelTask get(@NonNull String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException(
                "unknown ai-labels task, name '%s'".formatted(name)));
    }
}
