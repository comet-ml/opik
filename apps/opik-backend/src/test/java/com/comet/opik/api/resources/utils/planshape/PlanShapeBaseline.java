package com.comet.opik.api.resources.utils.planshape;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Builder;
import lombok.NonNull;

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
public class PlanShapeBaseline {

    @Builder(toBuilder = true)
    public record Entry(@NonNull String fingerprint, String note) {
    }

    private final Set<String> allowedFingerprints;

    private PlanShapeBaseline(Set<String> allowedFingerprints) {
        this.allowedFingerprints = allowedFingerprints;
    }

    public static PlanShapeBaseline loadFromClasspath(@NonNull String resourcePath) {
        try (InputStream is = PlanShapeBaseline.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Plan-shape baseline resource not found: " + resourcePath);
            }
            List<Entry> entries = JsonUtils.getMapper().readValue(is, new TypeReference<>() {
            });
            return new PlanShapeBaseline(entries.stream().map(Entry::fingerprint).collect(Collectors.toSet()));
        } catch (UncheckedIOException e) {
            throw e;
        } catch (Exception e) {
            throw new UncheckedIOException("Failed to load plan-shape baseline: " + resourcePath,
                    new java.io.IOException(e));
        }
    }

    /**
     * @return the violations that are not present in the baseline — i.e. the ones that must fail the build.
     */
    public List<PlanShapeViolation> netNew(@NonNull List<PlanShapeViolation> violations) {
        return violations.stream()
                .filter(violation -> !allowedFingerprints.contains(violation.fingerprint()))
                .toList();
    }
}
