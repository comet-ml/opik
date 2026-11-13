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
 * The Qwen prompts: the templates under {@code topics/prompts/} with their placeholders filled in. Numbers use the
 * fixed formats of {@link TopicText}, so the same groups always give the same prompt text.
 */
@UtilityClass
public class TopicPrompts {

    private static final String AUTHOR = load("author_v2.md");

    /**
     * The author answer's schema: {@code {topics: [{name, definition}], assignment: [{cluster, topic}]}},
     * {@code topic} the topic's index or -1 for Other.
     */
    public static final JsonNode AUTHOR_SCHEMA = parse("""
            {"type": "object", "properties": {
              "topics": {"type": "array", "items": {"type": "object", "properties": {
                "name": {"type": "string"}, "definition": {"type": "string"}},
                "required": ["name", "definition"]}},
              "assignment": {"type": "array", "items": {"type": "object", "properties": {
                "cluster": {"type": "integer"}, "topic": {"type": "integer"}},
                "required": ["cluster", "topic"]}}},
             "required": ["topics", "assignment"]}
            """);

    /**
     * The author prompt: the groups largest first (ties by group id), empty ones left out, each with its share as a
     * whole percent and its examples.
     *
     * @param examples per group index, {@link GroupSummaries#authorExamples}
     */
    public static String author(@NonNull List<GroupSummary> groups, int maxTopics,
            @NonNull List<List<String>> examples) {
        var body = groups.stream()
                .filter(group -> group.size() > 0)
                .sorted(Comparator.comparingInt(group -> -group.size()))
                .map(group -> "  cluster " + group.group() + ": " + Math.round(100 * group.share()) + "% of traffic\n"
                        + examples.get(group.group()).stream().map(example -> "    - " + example)
                                .collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n"));
        return AUTHOR.replace("{max_topics}", String.valueOf(maxTopics)).replace("{clusters}", body);
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
