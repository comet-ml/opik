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
    /** A nightly update's first naming call: {@link TopicPrompts#naming}. */
    static final String NAMING = "naming";
    /** A nightly update's second naming call, over the groups the verify rejected. */
    static final String NAMING_NEW = "naming_new";
    /** A nightly update's verify of the first naming call's assigns: {@link TopicPrompts#verify}. */
    static final String VERIFY = LlmCall.VERIFY.getValue();
    /** Names the topics a run merged: {@link TopicPrompts#merge}. */
    static final String MERGE = "merge";
    public static final String MAX_TOPICS_PLACEHOLDER = "{{max_topics}}";
    public static final String CLUSTERS_PLACEHOLDER = "{{clusters}}";
    static final String TOPICS_PLACEHOLDER = "{{topics}}";
    static final String PAIRS_PLACEHOLDER = "{{pairs}}";

    private static final TaskPlaceholders NAMING_PLACEHOLDERS = new TaskPlaceholders(
            Set.of(TOPICS_PLACEHOLDER, CLUSTERS_PLACEHOLDER), Set.of(), Set.of());

    static final Map<String, TaskPlaceholders> PLACEHOLDERS = Map.of(
            AUTHOR, new TaskPlaceholders(Set.of(CLUSTERS_PLACEHOLDER), Set.of(), Set.of(MAX_TOPICS_PLACEHOLDER)),
            NAMING, NAMING_PLACEHOLDERS,
            NAMING_NEW, NAMING_PLACEHOLDERS,
            VERIFY, new TaskPlaceholders(Set.of(PAIRS_PLACEHOLDER), Set.of(), Set.of()),
            MERGE, new TaskPlaceholders(Set.of(TOPICS_PLACEHOLDER), Set.of(), Set.of()));

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

    AiLabelTask naming() {
        return tasks.get(NAMING);
    }

    AiLabelTask namingNew() {
        return tasks.get(NAMING_NEW);
    }

    AiLabelTask verify() {
        return tasks.get(VERIFY);
    }

    AiLabelTask merge() {
        return tasks.get(MERGE);
    }
}
