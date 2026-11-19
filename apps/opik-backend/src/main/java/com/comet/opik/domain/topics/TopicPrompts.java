package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fills the placeholders of the {@link TopicTasks} prompts. Numbers use the fixed formats of {@link TopicText}, so the
 * same groups always give the same prompt text.
 */
@UtilityClass
public class TopicPrompts {

    /**
     * The author prompt: the groups largest first (ties by group id), empty ones left out, each with its share as a
     * whole percent and its examples.
     *
     * @param task     {@link TopicTasks#author()}
     * @param examples per group index, {@link GroupSummaries#authorExamples}
     */
    public static String author(@NonNull AiLabelTask task, @NonNull List<GroupSummary> groups, int maxTopics,
            @NonNull List<List<String>> examples) {
        var body = groups.stream()
                .filter(group -> group.size() > 0)
                .sorted(Comparator.comparingInt(group -> -group.size()))
                .map(group -> "  cluster " + group.group() + ": " + Math.round(100 * group.share()) + "% of traffic\n"
                        + examples.get(group.group()).stream().map(example -> "    - " + example)
                                .collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n"));
        return task.prompt().user()
                .replace(TopicTasks.MAX_TOPICS_PLACEHOLDER, String.valueOf(maxTopics))
                .replace(TopicTasks.CLUSTERS_PLACEHOLDER, body);
    }
}
