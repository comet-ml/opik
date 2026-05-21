package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared prompt-row test assertions. Sister to {@link ExperimentTestAssertions}.
 *
 * <p>{@link #PROMPT_IGNORED_FIELDS} is the canonical recursive-comparison ignore list, kept as
 * a single source of truth across the prompt tests so each one opts out of the same response-side
 * absences (write-only fields, server-resolved {@code projectName}) and complex subobjects
 * ({@code latestVersion}, {@code requestedVersion}). {@link #assertPromptEqual} then re-asserts
 * every ignored field individually so the helper doesn't silently let drift slip through —
 * scenario-specific timestamps ({@code lastUpdatedAt}) are still the caller's responsibility.
 */
public class PromptTestAssertions {

    public static final String[] PROMPT_IGNORED_FIELDS = {
            "latestVersion",
            "requestedVersion",
            "template",
            "metadata",
            "changeDescription",
            "type",
            "projectName"};

    /**
     * Asserts that {@code actual} matches {@code expected} via recursive comparison ignoring
     * {@link #PROMPT_IGNORED_FIELDS}, plus per-field equality on every one of those ignored
     * fields so coverage stays tight. {@code lastUpdatedAt} stays inside the recursive
     * comparison: callers that need {@code isAfter} semantics (e.g. migration tests) should
     * patch {@code expected.lastUpdatedAt} to {@code actual.lastUpdatedAt} before calling and
     * assert the timestamp progression themselves on top of this baseline.
     */
    public static void assertPromptEqual(Prompt actual, Prompt expected) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(PROMPT_IGNORED_FIELDS)
                .isEqualTo(expected);

        assertThat(actual.latestVersion()).isEqualTo(expected.latestVersion());
        assertThat(actual.requestedVersion()).isEqualTo(expected.requestedVersion());
        assertThat(actual.template()).isEqualTo(expected.template());
        assertThat(actual.metadata()).isEqualTo(expected.metadata());
        assertThat(actual.changeDescription()).isEqualTo(expected.changeDescription());
        assertThat(actual.type()).isEqualTo(expected.type());
        assertThat(actual.projectName()).isEqualTo(expected.projectName());
    }
}
