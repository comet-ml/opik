package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskLoader;
import com.comet.opik.domain.ailabels.TaskPlaceholders;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The LLM tasks of Topics: each one's prompt, schema, max tokens and version, in its file under
 * {@code topics/tasks}, in the ai-labels task format. Loaded and checked at startup, so an invalid file stops
 * the process. Not ai-labels tasks: the ai-labels tick never runs them, and each fills its own placeholders.
 */
@Singleton
@EagerSingleton
public class TopicTasks {

    /** Names topics over a discovery run's groups: {@link TopicPrompts#author}. */
    public static final String AUTHOR = LlmCall.AUTHOR.getValue();
    public static final String MAX_TOPICS_PLACEHOLDER = "{{max_topics}}";
    public static final String CLUSTERS_PLACEHOLDER = "{{clusters}}";

    static final Map<String, TaskPlaceholders> PLACEHOLDERS = Map.of(
            AUTHOR, new TaskPlaceholders(Set.of(CLUSTERS_PLACEHOLDER), Set.of(), Set.of(MAX_TOPICS_PLACEHOLDER)));

    private final Map<String, AiLabelTask> tasks;

    public TopicTasks() {
        this(AiLabelTaskLoader.loadBundled("topics", PLACEHOLDERS));
    }

    TopicTasks(@NonNull List<AiLabelTask> loaded) {
        var byName = new HashMap<String, AiLabelTask>();
        loaded.forEach(task -> byName.put(task.name(), task));
        var missing = PLACEHOLDERS.keySet().stream().filter(name -> !byName.containsKey(name)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("missing topics tasks, names '%s'".formatted(missing));
        }
        this.tasks = Map.copyOf(byName);
    }

    public AiLabelTask author() {
        return tasks.get(AUTHOR);
    }
}
