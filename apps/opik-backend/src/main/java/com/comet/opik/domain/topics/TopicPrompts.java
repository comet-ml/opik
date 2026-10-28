package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
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
