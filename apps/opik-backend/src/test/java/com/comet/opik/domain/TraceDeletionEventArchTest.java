package com.comet.opik.domain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.r2dbc.spi.Connection;

import java.util.Set;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural guard for the trace deletion event capturing. User-initiated trace deletes must flow through
 * {@link TraceServiceImpl}, which records the deleted ids in {@code deletion_events_local} after the delete so
 * they survive the data-model migration's table copy. A new caller of
 * {@code TraceDAO.delete(Set, UUID, Connection)} would issue the lightweight delete without that capture,
 * silently bypassing the capturing, so this rule fails the build if one appears. Retention paths
 * ({@code deleteForRetention*}) are intentionally not captured and are separate methods, so they are not matched.
 */
@AnalyzeClasses(packages = "com.comet.opik", importOptions = ImportOption.DoNotIncludeTests.class)
class TraceDeletionEventArchTest {

    /**
     * allowEmptyShould: this is a negative rule; with zero violations (the healthy state) its should-clause
     * matches nothing, which ArchUnit otherwise rejects. It still fails if a bypassing caller is introduced.
     */
    @ArchTest
    static final ArchRule trace_deletes_must_route_through_the_capturing_service = noClasses()
            .that().doNotBelongToAnyOf(TraceServiceImpl.class)
            .should().callMethod(TraceDAO.class, "delete", Set.class, UUID.class, Connection.class)
            .because("""
                    trace deletes must go through TraceServiceImpl so the deletion-events bridge captures every
                    deleted id; a new caller here would silently bypass the bridge
                    """)
            .allowEmptyShould(true);
}
