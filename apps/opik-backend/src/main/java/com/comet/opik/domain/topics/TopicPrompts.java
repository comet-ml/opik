package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
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
import java.util.stream.Collectors;

/**
 * The Qwen prompts, built from files under {@code topics/prompts/} copied verbatim from the stand, with the stand's
 * substitutions and number formats, so the text is the stand's byte for byte.
 */
@UtilityClass
public class TopicPrompts {

    private static final String AUTHOR = load("author.md");
    private static final String REVISE = load("revise_v4.md");
    private static final String REVISE_REVIEW = load("revise_review_v4.md");
    private static final String VERIFY = load("verify_batch.md");
    private static final String NAMING = load("naming.md");

    /**
     * The revise answer's schema: {@code assign}, {@code new} and {@code other} required; {@code merge},
     * {@code retire} and {@code redefine} for review nights.
     */
    public static final JsonNode REVISE_SCHEMA = parse("""
            {"type": "object", "properties": {
              "assign": {"type": "array", "items": {"type": "object", "properties": {
                "cluster": {"type": "integer"}, "topic": {"type": "integer"}}, "required": ["cluster", "topic"]}},
              "new": {"type": "array", "items": {"type": "object", "properties": {
                "name": {"type": "string"}, "definition": {"type": "string"},
                "clusters": {"type": "array", "items": {"type": "integer"}}},
                "required": ["name", "definition", "clusters"]}},
              "other": {"type": "array", "items": {"type": "integer"}},
              "merge": {"type": "array", "items": {"type": "array", "items": {"type": "integer"}}},
              "retire": {"type": "array", "items": {"type": "integer"}},
              "redefine": {"type": "array", "items": {"type": "object", "properties": {
                "id": {"type": "integer"}, "definition": {"type": "string"}}, "required": ["id", "definition"]}}},
             "required": ["assign", "new", "other"]}
            """);

    /** The batched verify answer's schema: {@code {answers: [{pair, same}]}}. */
    public static final JsonNode VERIFY_SCHEMA = parse("""
            {"type": "object", "properties": {
              "answers": {"type": "array", "items": {"type": "object", "properties": {
                "pair": {"type": "integer"}, "same": {"type": "boolean"}}, "required": ["pair", "same"]}}},
             "required": ["answers"]}
            """);

    /** The naming answer's schema: {@code {topics: [{cluster, name, definition}]}}. */
    public static final JsonNode NAMING_SCHEMA = parse("""
            {"type": "object", "properties": {
              "topics": {"type": "array", "items": {"type": "object", "properties": {
                "cluster": {"type": "integer"}, "name": {"type": "string"}, "definition": {"type": "string"}},
                "required": ["cluster", "name", "definition"]}}},
             "required": ["topics"]}
            """);

    /** The author answer's schema: {@code {topics: [{name, definition, clusters: [int]}], other: [int]}}. */
    public static final JsonNode AUTHOR_SCHEMA = parse("""
            {"type": "object", "properties": {
              "topics": {"type": "array", "items": {"type": "object", "properties": {
                "name": {"type": "string"}, "definition": {"type": "string"},
                "clusters": {"type": "array", "items": {"type": "integer"}}},
                "required": ["name", "definition", "clusters"]}},
              "other": {"type": "array", "items": {"type": "integer"}}},
             "required": ["topics", "other"]}
            """);

    /** The groups largest first (ties by group id), empty ones left out. */
    public static String author(@NonNull List<GroupSummary> groups, int maxTopics) {
        var body = groups.stream()
                .filter(group -> group.size() > 0)
                .sorted(Comparator.comparingInt(group -> -group.size()))
                .map(TopicPrompts::groupBlock)
                .collect(Collectors.joining("\n"));
        return AUTHOR.replace("{max_topics}", String.valueOf(maxTopics)).replace("{clusters}", body);
    }

    /**
     * The stand's {@code revise_prompt} (v4): the topics in list order, the candidates largest first (ties by group
     * id), {@code "  (none)"} when there are none; {@code revise_review_v4.md} on review nights.
     */
    public static String revise(@NonNull List<PromptTopic> topics, @NonNull List<GroupSummary> candidates,
            boolean review, int maxTopics) {
        var topicBlock = topics.stream().map(TopicPrompts::topicBlock).collect(Collectors.joining("\n"));
        var candidateBlock = candidates.stream()
                .sorted(Comparator.comparingInt(group -> -group.size()))
                .map(TopicPrompts::groupBlock)
                .collect(Collectors.joining("\n"));
        return (review ? REVISE_REVIEW : REVISE)
                .replace("{max_topics}", String.valueOf(maxTopics))
                .replace("{topics}", topicBlock)
                .replace("{candidates}", candidateBlock.isEmpty() ? "  (none)" : candidateBlock);
    }

    /** The stand's {@code verify_batch_prompt}: per pair the topic's first 3 examples and the group's first 5. */
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

    /** The stand's {@code naming_prompt} for one group. */
    public static String naming(@NonNull GroupSummary group) {
        return NAMING.replace("{clusters}", groupBlock(group));
    }

    static String topicBlock(PromptTopic topic) {
        return "  topic " + topic.id() + ": " + topic.name() + " — " + topic.definition() + "\n    share "
                + TopicText.fixed(100 * topic.share(), 1) + "% (last 7 days "
                + TopicText.fixed(100 * topic.recentShare(), 1) + "%)\n" + bullets(topic.examples(), 4);
    }

    private static String bullets(List<String> examples, int max) {
        return examples.stream().limit(max).map(example -> "    - " + example).collect(Collectors.joining("\n"));
    }

    /**
     * A live topic as the revise and verify prompts show it: {@code id} is its 1-based position in the live list,
     * {@code topic} its index in the night's topic list; shares and examples from the assignment before recentre.
     */
    @Builder
    public record PromptTopic(int id, int topic, @NonNull String name, @NonNull String definition, double share,
            double recentShare, @NonNull List<String> examples) {
    }

    /** One verify pair: a live topic and a candidate group mapped to it. */
    public record VerifyPair(@NonNull PromptTopic topic, @NonNull GroupSummary group) {
    }

    static String groupBlock(GroupSummary group) {
        var examples = group.examples().stream().map(example -> "    - " + example)
                .collect(Collectors.joining("\n"));
        // Concatenation, not String.format: %d would follow the default locale's digits.
        return "  cluster " + group.group() + ": share " + TopicText.fixed(100 * group.share(), 1)
                + "% (last 7 days " + TopicText.fixed(100 * group.recentShare(), 1) + "%), tightness "
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
