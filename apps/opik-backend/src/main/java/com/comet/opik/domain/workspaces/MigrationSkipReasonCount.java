package com.comet.opik.domain.workspaces;

// Result of grouping trapped workspaces by skip reason (deleted_project / all_ambiguous /
// default_project_missing). Drives the per-reason tagging on the trapped-workspaces gauge.
public record MigrationSkipReasonCount(String reason, long count) {
}
