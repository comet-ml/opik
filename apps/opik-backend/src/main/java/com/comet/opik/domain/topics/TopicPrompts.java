package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fills the placeholders of the {@link TopicTasks} prompts and of the nightly update's prompts in
 * {@code topics/prompts}. Numbers use the fixed formats of {@link TopicText}, so the
 * same groups always give the same prompt text.
 */
@UtilityClass
public class TopicPrompts {

    private static final String VERIFY = load("verify_batch.md");
    private static final String NAMING_GROUP = load("naming_group.md");
    private static final String NAMING_GROUP_NEW = load("naming_group_new.md");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    public static final JsonNode VERIFY_SCHEMA = parse("""
            {"type": "object", "properties": {
              "answers": {"type": "array", "items": {"type": "object", "properties": {
                "pair": {"type": "integer"}, "same": {"type": "boolean"}}, "required": ["pair", "same"]}}},
             "required": ["answers"]}
            """);

    /** The grouped naming answer's schema; keep its keys in this order. */
    public static final JsonNode NAMING_GROUP_SCHEMA = parse("""
            {"type": "object", "properties": {
              "assign": {"type": "array", "items": {"type": "object", "properties": {
                "cluster": {"type": "integer"}, "topic": {"type": "integer"}}, "required": ["cluster", "topic"]}},
              "new": {"type": "array", "items": {"type": "object", "properties": {
                "name": {"type": "string"}, "definition": {"type": "string"},
                "clusters": {"type": "array", "items": {"type": "integer"}}},
                "required": ["name", "definition", "clusters"]}},
              "other": {"type": "array", "items": {"type": "integer"}}},
             "required": ["assign", "new", "other"]}
            """);

    /** The second naming call's schema: {@link #NAMING_GROUP_SCHEMA} without {@code assign}. */
    public static final JsonNode NAMING_GROUP_NEW_SCHEMA = parse("""
            {"type": "object", "properties": {
              "new": {"type": "array", "items": {"type": "object", "properties": {
                "name": {"type": "string"}, "definition": {"type": "string"},
                "clusters": {"type": "array", "items": {"type": "integer"}}},
                "required": ["name", "definition", "clusters"]}},
              "other": {"type": "array", "items": {"type": "integer"}}},
             "required": ["new", "other"]}
            """);

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

    /** The batched verify prompt: per pair the topic's first 3 examples and the group's first 5. */
    public static String verify(@NonNull List<VerifyPair> pairs) {
        var body = new StringBuilder();
        for (int n = 0; n < pairs.size(); n++) {
            var topic = pairs.get(n).topic();
            var group = pairs.get(n).group();
            if (n > 0) {
                body.append("\n\n");
            }
            body.append("PAIR ").append(n).append("\n  TOPIC: ").append(topic.name()).append(" — ")
                    .append(topic.definition()).append("\n  typical requests:\n")
                    .append(bullets(topic.examples(), 3)).append("\n  NEW CLUSTER (share ")
                    .append(TopicText.fixed(100 * Math.max(group.share(), group.recentShare()), 1)).append("%):\n")
                    .append(bullets(group.examples(), 5));
        }
        return VERIFY.replace("{pairs}", body.toString());
    }

    /** The grouped naming prompt. With {@code allowAssign} false, the second call's prompt: new and other only. */
    public static String namingGroup(@NonNull List<PromptTopic> topics, @NonNull List<NightGroup> groups,
            boolean allowAssign, int recentDays) {
        var topicBlock = topics.stream().map(topic -> topicBlock(topic, recentDays)).collect(Collectors.joining("\n"));
        var groupBlock = groups.stream()
                .sorted(Comparator.comparingInt(group -> -group.size()))
                .map(group -> groupBlock(group, recentDays))
                .collect(Collectors.joining("\n"));
        return fill(allowAssign ? NAMING_GROUP : NAMING_GROUP_NEW, Map.of(
                "topics", topicBlock.isEmpty() ? "  (none)" : topicBlock,
                "clusters", groupBlock));
    }

    /**
     * Puts each {@code {name}} of {@code template} in one pass over the template, so a value that holds a placeholder
     * (trace text such as "{clusters}") stays as it is. Other braces, such as JSON examples, stay too.
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

    static String topicBlock(PromptTopic topic, int recentDays) {
        return "  topic " + topic.id() + ": " + topic.name() + " — " + topic.definition() + "\n    share "
                + TopicText.fixed(100 * topic.share(), 1) + "% (last " + recentDays + " days "
                + TopicText.fixed(100 * topic.recentShare(), 1) + "%)\n" + bullets(topic.examples(), 4);
    }

    private static String bullets(List<String> examples, int max) {
        return examples.stream().limit(max).map(example -> "    - " + example).collect(Collectors.joining("\n"));
    }

    /**
     * A live topic as the naming and verify prompts show it: {@code id} is its 1-based position in the live list,
     * {@code topic} its index in the night's topic list; shares and examples from the night's assignment.
     */
    @Builder(toBuilder = true)
    public record PromptTopic(int id, int topic, @NonNull String name, @NonNull String definition, double share,
            double recentShare, @NonNull List<String> examples) {
    }

    /** One verify pair: a live topic and a candidate group given to it. */
    @Builder(toBuilder = true)
    public record VerifyPair(@NonNull PromptTopic topic, @NonNull NightGroup group) {
    }

    static String groupBlock(NightGroup group, int recentDays) {
        var examples = group.examples().stream().map(example -> "    - " + example)
                .collect(Collectors.joining("\n"));
        // Concatenation, not String.format: %d would follow the default locale's digits.
        return "  cluster " + group.group() + ": share " + TopicText.fixed(100 * group.share(), 1)
                + "% (last " + recentDays + " days " + TopicText.fixed(100 * group.recentShare(), 1) + "%), tightness "
                + TopicText.fixed(group.tightness(), 2) + "\n" + examples;
    }

    private static String load(String name) {
        try (var stream = TopicPrompts.class.getResourceAsStream("/topics/prompts/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing prompt resource 'topics/prompts/%s'".formatted(name));
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static JsonNode parse(String json) {
        try {
            return InferenceJson.MAPPER.readTree(json);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
