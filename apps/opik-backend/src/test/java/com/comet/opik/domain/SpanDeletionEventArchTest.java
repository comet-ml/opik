package com.comet.opik.domain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural guard for the span deletion event capturing. Spans have no standalone user delete
 * ({@code DELETE /spans/{id}} returns 501); the only span-deletion path is the trace-delete cascade in
 * {@link SpanService}, which records the deleted ids in {@code deletion_events_local} after the delete so they
 * survive the data-model migration's table copy. A new caller of {@code SpanDAO.deleteByIds(Set, UUID)} would issue
 * the lightweight delete without that capture, silently bypassing the bridge, so this rule fails the build if one
 * appears. Retention paths ({@code deleteForRetention*}) are intentionally not captured and are separate methods, so
 * they are not matched.
 */
@AnalyzeClasses(packages = "com.comet.opik", importOptions = ImportOption.DoNotIncludeTests.class)
class SpanDeletionEventArchTest {

    /**
     * allowEmptyShould: this is a negative rule; with zero violations (the healthy state) its should-clause
     * matches nothing, which ArchUnit otherwise rejects. It still fails if a bypassing caller is introduced.
     */
    @ArchTest
    static final ArchRule span_deletes_must_route_through_the_capturing_service = noClasses()
            .that().doNotBelongToAnyOf(SpanService.class)
            .should().callMethod(SpanDAO.class, "deleteByIds", Set.class, UUID.class)
            .because("""
                    span deletes must go through SpanService so the deletion-events bridge captures every
                    deleted id; a new caller here would silently bypass the bridge
                    """)
            .allowEmptyShould(true);
}
