package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fills the placeholders of the {@link TopicTasks} prompts. Numbers use the fixed formats of {@link TopicText}, so the
 * same groups always give the same prompt text. In the nightly update's prompts every trace text, name and definition
 * is put on one line and capped ({@link TopicText#oneLine}), so none can write a line of the prompt's own.
 */
@UtilityClass
class TopicPrompts {

    /** The most characters a definition keeps in a prompt; the naming prompt asks for at most 25 words. */
    static final int MAX_DEFINITION_LENGTH = 500;
    /** The most characters a trace text keeps in a prompt; a trace summary is about 10 words. */
    static final int MAX_EXAMPLE_LENGTH = 300;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z_]+)}}");

    /**
     * The author prompt: the groups largest first (ties by group id), empty ones left out, each with its share as a
     * whole percent and its examples.
     *
     * @param task     {@link TopicTasks#author()}
     * @param examples per group index, {@link GroupSummaries#authorExamples}
     */
    static String author(@NonNull AiLabelTask task, @NonNull List<GroupSummary> groups, int maxTopics,
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

    /**
     * The batched verify prompt: per pair the topic's first {@code topicExamples} texts and the group's first
     * {@code groupExamples}.
     *
     * @param task {@link TopicTasks#verify()}
     */
    static String verify(@NonNull AiLabelTask task, @NonNull List<VerifyPair> pairs, int topicExamples,
            int groupExamples) {
        var body = new StringBuilder();
        for (int n = 0; n < pairs.size(); n++) {
            var topic = pairs.get(n).topic();
            var group = pairs.get(n).group();
            if (n > 0) {
                body.append("\n\n");
            }
            body.append("PAIR ").append(n).append("\n  TOPIC: ").append(name(topic.name())).append(" — ")
                    .append(definition(topic.definition())).append("\n  typical requests:\n")
                    .append(bullets(topic.examples(), topicExamples)).append("\n  NEW CLUSTER (share ")
                    .append(TopicText.fixed(100 * Math.max(group.share(), group.recentShare()), 1)).append("%):\n")
                    .append(bullets(group.examples(), groupExamples));
        }
        return fill(task.prompt().user(), Map.of("pairs", body.toString()));
    }

    /**
     * A naming prompt: the live topics numbered from 1 in the given order, then the groups largest first. The task
     * decides the call: {@link TopicTasks#naming()} offers assign, new and other, {@link TopicTasks#namingNew()} new and
     * other only.
     */
    static NamingPrompt naming(@NonNull AiLabelTask task, @NonNull List<LiveTopic> topics,
            @NonNull List<NightGroup> groups, int recentDays, int topicExamples) {
        var topicOfId = new LinkedHashMap<Integer, Integer>();
        var topicBlock = new StringJoiner("\n");
        for (var topic : topics) {
            int id = topicOfId.size() + 1;
            topicOfId.put(id, topic.topic());
            topicBlock.add(topicBlock(id, topic, recentDays, topicExamples));
        }
        var groupBlock = groups.stream()
                .sorted(Comparator.comparingInt(group -> -group.size()))
                .map(group -> groupBlock(group, recentDays))
                .collect(Collectors.joining("\n"));
        var text = fill(task.prompt().user(), Map.of(
                "topics", topics.isEmpty() ? "  (none)" : topicBlock.toString(),
                "clusters", groupBlock));
        return new NamingPrompt(text, Collections.unmodifiableMap(topicOfId));
    }

    /**
     * The merge prompt: per merged topic its id, the former names and definitions, its share and examples.
     *
     * @param task {@link TopicTasks#merge()}
     */
    static String merge(@NonNull AiLabelTask task, @NonNull List<TopicMerger.Merged> merged) {
        var body = new StringJoiner("\n");
        for (int id = 0; id < merged.size(); id++) {
            var topic = merged.get(id);
            var block = new StringBuilder("  merged topic ").append(id).append(": share ")
                    .append(TopicText.fixed(100 * topic.share(), 1)).append("%\n    former topics:\n");
            for (int f = 0; f < topic.names().size(); f++) {
                block.append("      - ").append(name(topic.names().get(f))).append(" — ")
                        .append(definition(topic.definitions().get(f))).append("\n");
            }
            block.append("    example requests:\n").append(bullets(topic.examples(), topic.examples().size()));
            body.add(block);
        }
        return fill(task.prompt().user(), Map.of("topics", body.toString()));
    }

    /**
     * Puts each {@code {{name}}} of {@code template} in one pass over the template, so a value that holds a placeholder
     * (trace text such as "{{clusters}}") stays as it is. Other braces, such as JSON examples, stay too.
     */
    static String fill(String template, Map<String, String> values) {
        var matcher = PLACEHOLDER.matcher(template);
        var out = new StringBuilder();
        while (matcher.find()) {
            var value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String topicBlock(int id, LiveTopic topic, int recentDays, int examples) {
        return "  topic " + id + ": " + name(topic.name()) + " — " + definition(topic.definition()) + "\n    share "
                + TopicText.fixed(100 * topic.share(), 1) + "% (last " + recentDays + " days "
                + TopicText.fixed(100 * topic.recentShare(), 1) + "%)\n" + bullets(topic.examples(), examples);
    }

    private static String groupBlock(NightGroup group, int recentDays) {
        // Concatenation, not String.format: %d would follow the default locale's digits.
        return "  cluster " + group.group() + ": share " + TopicText.fixed(100 * group.share(), 1)
                + "% (last " + recentDays + " days " + TopicText.fixed(100 * group.recentShare(), 1) + "%), tightness "
                + TopicText.fixed(group.tightness(), 2) + "\n" + bullets(group.examples(), group.examples().size());
    }

    private static String bullets(List<String> examples, int max) {
        return examples.stream().limit(max).map(example -> "    - " + TopicText.oneLine(example, MAX_EXAMPLE_LENGTH))
                .collect(Collectors.joining("\n"));
    }

    private static String name(String name) {
        return TopicText.oneLine(name, TopicText.MAX_NAME_LENGTH);
    }

    private static String definition(String definition) {
        return TopicText.oneLine(definition, MAX_DEFINITION_LENGTH);
    }

    /** A naming prompt's text, and the live topic index of each topic id it shows. */
    record NamingPrompt(@NonNull String text, @NonNull Map<Integer, Integer> topicOfId) {
    }

    /** One verify pair: a live topic and a candidate group given to it. */
    @Builder(toBuilder = true)
    record VerifyPair(@NonNull LiveTopic topic, @NonNull NightGroup group) {
    }
}
