package com.comet.opik.infrastructure.auth;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architectural guard for {@link RequiredPermissions} coverage on mutating endpoints.
 * <p>
 * <b>An absent annotation is not a default-deny — it demands nothing.</b>
 * {@link RequiredPermissionsResolver#getRequiredPermissions} returns an empty list when the annotation is
 * missing, and {@code RemoteAuthService} serialises that field as {@code @JsonInclude(NON_EMPTY)}, so it is
 * dropped from the auth request entirely. The auth service is then asked "is this caller in the workspace?"
 * rather than "may this caller do this?". Forgetting the annotation therefore silently downgrades a write to
 * workspace-membership-only, and nothing at build time, at review time or at runtime says so.
 * <p>
 * That has shipped twice. {@code deleteAlertBatch} let any workspace member bulk-delete alerts they could not
 * create or modify, found by eye during unrelated review (OPIK-8383). {@code deleteEvaluators} let the READ and
 * ANNOTATE roles — which cannot even view an online-evaluation rule — permanently delete one, found by QA
 * automation months later (OPIK-8091). Both were single missing lines sitting beside correctly annotated
 * siblings.
 * <p>
 * Scope is mutating endpoints under {@code api.resources.v1.priv}: that is the class of bug both incidents
 * actually were, and every gap in it is closable today with an existing {@link WorkspaceUserPermission}. Reads
 * are deliberately out of scope — several would need permissions that do not exist yet (there is no
 * {@code ALERT_VIEW}), which would park entries on a cross-repo dependency and erode the list's meaning.
 * {@code v1/internal}, {@code v1/session} and {@code oauth/*} authenticate differently and are excluded at the
 * package level by that same scoping.
 * <p>
 * <h2>Two lists, two different claims</h2>
 * An endpoint passes by carrying the annotation or by appearing on one of two lists. They are kept apart
 * because they assert opposite things, and a single list would let the weaker claim hide inside the stronger
 * one.
 * <ul>
 * <li>{@link #PENDING_REVIEW} — "this still needs a permission, nobody has picked which." The 85 endpoints that
 * already existed when the rule was introduced and were not waived, seeded so it passes on {@code main} from
 * day one. This is <b>debt, not approval</b>. It is meant to shrink to zero, and it is the checklist for that
 * work rather than a manual re-grep each round. Nothing should ever be added here — a new endpoint predates
 * nothing.</li>
 * <li>{@link #DELIBERATELY_UNGATED} — "someone looked, and this endpoint genuinely should not demand a
 * workspace permission." Like the exclusion list in
 * {@link com.comet.opik.infrastructure.redaction.RedactionCoverageArchTest}, it is meant to be awkward to
 * extend: <b>adding an entry is a decision that any workspace member may call that endpoint</b>, and it needs
 * a comment saying why. It holds one entry, {@code checkAccess}, where demanding a permission would be
 * circular. All 85 others were reviewed once (see below) and none qualified.</li>
 * </ul>
 * Note that liveness and version endpoints ({@code /is-alive}, {@code /ping}, {@code /ver}) need neither list:
 * they live in {@code infrastructure.health}, outside this rule's package scope, and are reads besides.
 * <p>
 * <h2>What the first pass over the 86 found</h2>
 * All 86 seeded endpoints were read once to ask whether each belongs in {@link #DELIBERATELY_UNGATED} rather
 * than {@link #PENDING_REVIEW}. Only {@code checkAccess} qualified. The other 85 fall into three groups, none
 * of which is a reason to waive the permission:
 * <ul>
 * <li><b>Genuine writes (57)</b> — creating, updating and deleting datasets, experiments, feedback definitions,
 * attachments, agent configs, retention rules and so on. These are the burn-down work: each needs a per-endpoint
 * decision about which permission applies.</li>
 * <li><b>Read-shaped (12)</b> — {@code @POST} only to carry a request body too large for a query string:
 * {@code searchTraces}, {@code streamDatasetItems}, {@code getCost} and similar, marked inline below. Gating
 * these needs {@code *_VIEW} permissions that do not exist yet, so they are <b>not</b> closable on the same
 * terms as the writes and should not be treated as quick wins.</li>
 * <li><b>Machine callers (16)</b> — {@code LocalRunnersResource} job polling and heartbeats,
 * {@code OpenTelemetryResource} ingest, {@code PairingResource} activation. Tempting to wave through as "not a
 * user", but they still act inside a workspace and still mutate it, and the identity they run as is a
 * workspace identity. A runner that can poll jobs it should not see is the same class of bug as a user who can.
 * These need permissions that model machine callers, not an exemption.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.comet.opik.api.resources.v1.priv", importOptions = ImportOption.DoNotIncludeTests.class)
class RequiredPermissionsCoverageArchTest {

    private static final Set<Class<? extends Annotation>> MUTATING_VERBS = Set.of(POST.class, PUT.class,
            DELETE.class, PATCH.class);

    /**
     * Endpoints reviewed and found not to need a workspace permission, as {@code SimpleClassName.methodName}.
     * Every entry needs a comment saying why. See the class javadoc before adding to it — an entry here
     * asserts that any workspace member may make this call.
     */
    private static final Set<String> DELIBERATELY_UNGATED = Set.of(
            // Answers "does this caller have access to this workspace?" and writes nothing. The auth filter has
            // already decided that by the time the handler runs, so the body only echoes the outcome. Demanding
            // a permission to ask the question would be circular — and would change a 204 into a 403 that means
            // the opposite of what the caller asked.
            "AuthenticationResource.checkAccess");

    /**
     * Endpoints that predate the rule and still need a permission chosen, as {@code SimpleClassName.methodName}.
     * Seeded from {@code main}; only ever meant to shrink, never to grow. This is debt, not approval — see the
     * class javadoc, including why the {@code // read-shaped} entries are not quick wins.
     */
    private static final Set<String> PENDING_REVIEW = Set.of(
            "AgentConfigsResource.createAgentConfig",
            "AgentConfigsResource.createBlueprintFromMask",
            "AgentConfigsResource.createOrUpdateEnvs",
            "AgentConfigsResource.deleteEnv",
            "AgentConfigsResource.removeConfigKeys",
            "AgentConfigsResource.setEnvByBlueprintName",
            "AgentConfigsResource.updateAgentConfig",
            "AgentInsightsResource.reportIssues",
            "AgentInsightsResource.updateIssue",
            "AssertionResultsResource.storeAssertionsBatch",
            "AttachmentResource.completeMultiPartUpload",
            "AttachmentResource.deleteAttachments",
            "AttachmentResource.startMultiPartUpload",
            "AttachmentResource.uploadAttachment",
            "ChatCompletionsResource.create",
            "DatasetVersionsResource.createTag",
            "DatasetVersionsResource.deleteTag",
            "DatasetVersionsResource.restoreVersion",
            "DatasetVersionsResource.retrieveVersion", // read-shaped
            "DatasetVersionsResource.updateVersion",
            "DatasetsResource.applyDatasetItemChanges",
            "DatasetsResource.batchUpdate",
            "DatasetsResource.createDatasetItems",
            "DatasetsResource.createDatasetItemsFromCsv",
            "DatasetsResource.createDatasetItemsFromJson",
            "DatasetsResource.createDatasetItemsFromSpans",
            "DatasetsResource.createDatasetItemsFromTraces",
            "DatasetsResource.expandDataset",
            "DatasetsResource.getDatasetByIdentifier", // read-shaped
            "DatasetsResource.markDatasetExportJobViewed",
            "DatasetsResource.patchDatasetItem",
            "DatasetsResource.startDatasetExport",
            "DatasetsResource.streamDatasetItems", // read-shaped
            "EnvironmentsResource.create",
            "EnvironmentsResource.deleteEnvironmentsBatch",
            "EnvironmentsResource.update",
            "ExperimentsResource.batchUpdate",
            "ExperimentsResource.createExperimentItems",
            "ExperimentsResource.deleteExperimentItems",
            "ExperimentsResource.deleteExperimentsById",
            "ExperimentsResource.experimentItemsBulk",
            "ExperimentsResource.finishExperiments",
            "ExperimentsResource.streamExperimentItems", // read-shaped
            "ExperimentsResource.streamExperiments", // read-shaped
            "ExperimentsResource.update",
            "FeedbackDefinitionResource.create",
            "FeedbackDefinitionResource.deleteById",
            "FeedbackDefinitionResource.deleteFeedbackDefinitionsBatch",
            "FeedbackDefinitionResource.update",
            "GuardrailsResource.createGuardrails",
            "LocalRunnersResource.appendLogs",
            "LocalRunnersResource.cancelJob",
            "LocalRunnersResource.createBridgeCommand",
            "LocalRunnersResource.createJob",
            "LocalRunnersResource.disconnectRunner",
            "LocalRunnersResource.heartbeat",
            "LocalRunnersResource.nextBridgeCommands",
            "LocalRunnersResource.nextJob",
            "LocalRunnersResource.patchChecklist",
            "LocalRunnersResource.registerAgents",
            "LocalRunnersResource.reportBridgeResult",
            "LocalRunnersResource.reportResult",
            "OllamaResource.listModels", // read-shaped
            "OllamaResource.testConnection",
            "OllieStateResource.delete",
            "OllieStateResource.upload",
            "OpenTelemetryResource.receiveJsonTraces",
            "OpenTelemetryResource.receiveProtobufTraces",
            "PairingResource.activate",
            "PairingResource.createSession",
            "ProjectsResource.update",
            "ReportFailuresResource.create",
            "RetentionRulesResource.createRule",
            "RetentionRulesResource.deactivateRule",
            "SpansResource.deleteById",
            "SpansResource.searchSpans", // read-shaped
            "TracesResource.deleteTraceThreads",
            "TracesResource.getTraceThread", // read-shaped
            "TracesResource.searchTraceThreads", // read-shaped
            "TracesResource.searchTraces", // read-shaped
            "WelcomeWizardResource.submitWizard",
            "WorkspacesResource.costsSummary",
            "WorkspacesResource.getCost", // read-shaped
            "WorkspacesResource.getMetric", // read-shaped
            "WorkspacesResource.metricsSummary");

    @ArchTest
    static final ArchRule mutating_private_endpoints_must_declare_required_permissions = methods()
            .that(new DescribedMutatingEndpoint())
            .should(declareRequiredPermissionsOrBeListed())
            .because("""
                    a missing @RequiredPermissions is not a default-deny: the resolver returns an empty list, \
                    RemoteAuthService omits the field as NON_EMPTY, and the write is gated on workspace \
                    membership alone. Annotate the endpoint. Do not add it to PENDING_REVIEW, which is frozen \
                    debt — a new endpoint predates nothing. Use DELIBERATELY_UNGATED, with a reason, only when \
                    a workspace permission is genuinely the wrong question for this call
                    """);

    private static ArchCondition<JavaMethod> declareRequiredPermissionsOrBeListed() {
        return new ArchCondition<>("declare @RequiredPermissions, or be pending review, or be deliberately ungated") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String identifier = identifier(method);
                if (method.isAnnotatedWith(RequiredPermissions.class)
                        || PENDING_REVIEW.contains(identifier)
                        || DELIBERATELY_UNGATED.contains(identifier)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(method,
                        "%s is a mutating v1/private endpoint with no @RequiredPermissions"
                                .formatted(identifier(method))));
            }
        };
    }

    /**
     * Guards both lists against rot: an entry that no longer names an unannotated mutating endpoint has been
     * fixed, renamed or deleted, and a stale entry would silently exempt a future endpoint that reuses the
     * name. On {@link #PENDING_REVIEW} it also keeps the burn-down count honest, since a stale entry overstates
     * the debt remaining.
     */
    @ArchTest
    static final ArchRule listed_endpoints_must_not_be_stale = methods()
            .that(new DescribedMutatingEndpoint())
            .should(new ArchCondition<>("leave no stale PENDING_REVIEW or DELIBERATELY_UNGATED entry behind") {

                private final Set<String> unannotated = new java.util.HashSet<>();

                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    if (!method.isAnnotatedWith(RequiredPermissions.class)) {
                        unannotated.add(identifier(method));
                    }
                }

                @Override
                public void finish(ConditionEvents events) {
                    reportStale(events, "PENDING_REVIEW", PENDING_REVIEW);
                    reportStale(events, "DELIBERATELY_UNGATED", DELIBERATELY_UNGATED);
                }

                private void reportStale(ConditionEvents events, String listName, Set<String> list) {
                    List<String> stale = list.stream()
                            .filter(entry -> !unannotated.contains(entry))
                            .sorted()
                            .toList();
                    if (!stale.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(stale,
                                "%s entries no longer match an unannotated mutating endpoint, remove them: %s"
                                        .formatted(listName, String.join(", ", stale))));
                    }
                }
            })
            .because("an entry that no longer matches would silently exempt a future endpoint reusing that name");

    /**
     * The two lists make opposite claims, so an entry on both is a contradiction rather than a redundancy —
     * and would read as approved while actually being unreviewed.
     */
    @Test
    void the_two_lists_must_stay_disjoint() {
        assertThat(PENDING_REVIEW).doesNotContainAnyElementsOf(DELIBERATELY_UNGATED);
    }

    private static String identifier(JavaMethod method) {
        return "%s.%s".formatted(method.getOwner().getSimpleName(), method.getName());
    }

    private static class DescribedMutatingEndpoint
            extends
                com.tngtech.archunit.base.DescribedPredicate<JavaMethod> {

        DescribedMutatingEndpoint() {
            super("mutating JAX-RS endpoints under api.resources.v1.priv");
        }

        @Override
        public boolean test(JavaMethod method) {
            return MUTATING_VERBS.stream().anyMatch(method::isAnnotatedWith);
        }
    }
}
