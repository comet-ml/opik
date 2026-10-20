package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.inference.Question;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSortedMap;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One task, as defined in its YAML file under {@code ai-labels/tasks/}: a decide task asks {@code questions}, a
 * generate task fills {@code prompt} and returns output matching {@code schema}. Only the current {@code version} is
 * submitted; {@code history} keeps the earlier versions' {@code applies_from} for {@link #required(Instant)}.
 */
@Builder(toBuilder = true)
public record AiLabelTask(
        @NonNull String name,
        @NonNull AiLabelEndpoint endpoint,
        boolean cache,
        int version,
        @NonNull Instant appliesFrom,
        List<Version> history,
        Map<String, Question> questions,
        Prompt prompt,
        JsonNode schema,
        Integer maxTokens,
        Double temperature) {

    public static final String STATE_PLACEHOLDER = "{{state}}";
    public static final String HISTORY_PLACEHOLDER = "{{history}}";
    public static final int DEFAULT_MAX_TOKENS = 512;
    public static final double DEFAULT_TEMPERATURE = 0.0;

    public AiLabelTask {
        history = history == null ? List.of() : List.copyOf(history);
        // Sorted, so items and their question order are deterministic.
        questions = questions == null ? null : ImmutableSortedMap.copyOf(questions);
    }

    @Builder(toBuilder = true)
    public record Version(int version, @NonNull Instant appliesFrom) {
    }

    @Builder(toBuilder = true)
    public record Prompt(String system, @NonNull String user) {
    }

    /**
     * {@link #required(Instant)} as two parallel arrays, ready to bind for the sweep's SQL:
     * {@code arrayMax(arrayFilter((v, a) -> a <= created_at, versions, applies_from))}, with {@code applies_from}
     * parsed by {@code parseDateTime64BestEffort}, from {@link #versions()}.
     */
    @Builder(toBuilder = true)
    public record SqlVersions(@NonNull Integer[] versions, @NonNull String[] appliesFrom) {
    }

    /** The current version and its history, newest version first. */
    public List<Version> versions() {
        return Stream.concat(Stream.of(new Version(version, appliesFrom)), history.stream())
                .sorted(Comparator.comparingInt(Version::version).reversed())
                .toList();
    }

    /**
     * The highest version whose {@code applies_from} is at or before {@code createdAt}, or 0 if none is. Called per
     * trace, so it scans in place rather than building {@link #versions()}.
     */
    public int required(@NonNull Instant createdAt) {
        var required = appliesFrom.isAfter(createdAt) ? 0 : version;
        for (var candidate : history) {
            if (!candidate.appliesFrom().isAfter(createdAt)) {
                required = Math.max(required, candidate.version());
            }
        }
        return required;
    }

    public SqlVersions sqlVersions() {
        var versions = versions();
        return SqlVersions.builder()
                .versions(versions.stream().map(Version::version).toArray(Integer[]::new))
                .appliesFrom(versions.stream().map(candidate -> candidate.appliesFrom().toString())
                        .toArray(String[]::new))
                .build();
    }

    /** Whether the task is given the trace's earlier turns: its prompt has {@link #HISTORY_PLACEHOLDER}. */
    public boolean usesHistory() {
        return prompt != null && prompt.user().contains(HISTORY_PLACEHOLDER);
    }

    /** A decide task's question id on the wire: {@code <task>.<question>}. */
    public String questionId(@NonNull String question) {
        return name + "." + question;
    }

    public int questionCount() {
        return questions == null ? 0 : questions.size();
    }

    public int effectiveMaxTokens() {
        return maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens;
    }

    public double effectiveTemperature() {
        return temperature == null ? DEFAULT_TEMPERATURE : temperature;
    }
}
