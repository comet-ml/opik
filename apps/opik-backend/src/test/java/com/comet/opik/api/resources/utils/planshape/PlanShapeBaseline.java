package com.comet.opik.api.resources.utils.planshape;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The checked-in allowlist of plan-shape violations that already exist in the codebase. The gate blocks only on
 * <b>net-new</b> violations vs. this baseline (see {@link #netNew(List)}); the allowlist is ratcheted down as legacy
 * offenders are fixed. Each entry is a {@link PlanShapeViolation#fingerprint()} plus a human note explaining why it is
 * tolerated / which ticket tracks its removal.
 */
@RequiredArgsConstructor
public class PlanShapeBaseline {

    private static final TypeReference<List<Entry>> ENTRIES_TYPE = new TypeReference<>() {
    };

    @Builder(toBuilder = true)
    public record Entry(String fingerprint, String note) {
    }

    private final Set<String> allowedFingerprints;

    public static PlanShapeBaseline loadFromClasspath(String resourcePath) {
        try (InputStream is = PlanShapeBaseline.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Plan-shape baseline resource not found: '%s'".formatted(resourcePath));
            }
            List<Entry> entries = JsonUtils.readValue(is, ENTRIES_TYPE);
            return new PlanShapeBaseline(entries.stream().map(Entry::fingerprint).collect(Collectors.toSet()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the violations that are not present in the baseline — i.e. the ones that must fail the build.
     */
    public List<PlanShapeViolation> netNew(List<PlanShapeViolation> violations) {
        return violations.stream()
                .filter(violation -> !allowedFingerprints.contains(violation.fingerprint()))
                .toList();
    }
}
