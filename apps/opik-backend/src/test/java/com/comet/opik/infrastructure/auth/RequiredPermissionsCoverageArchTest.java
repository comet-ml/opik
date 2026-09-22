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

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

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
 * {@link #BASELINE} is the 86 endpoints that predate this rule, seeded so it passes on {@code main} from day
 * one. Burning it down is separate, prioritisable work; the list is that work's checklist rather than a manual
 * re-grep each round. Like the exclusion list in
 * {@link com.comet.opik.infrastructure.redaction.RedactionCoverageArchTest}, it is meant to be awkward to
 * extend: <b>adding an entry is a decision that the endpoint may be called by any workspace member.</b> New and
 * relocated endpoints must carry the annotation instead.
 */
@AnalyzeClasses(packages = "com.comet.opik.api.resources.v1.priv", importOptions = ImportOption.DoNotIncludeTests.class)
class RequiredPermissionsCoverageArchTest {

    private static final Set<Class<? extends Annotation>> MUTATING_VERBS = Set.of(POST.class, PUT.class,
            DELETE.class, PATCH.class);

    /**
     * Endpoints predating the rule, as {@code SimpleClassName.methodName}. Seeded from {@code main}; only ever
     * meant to shrink. See the class javadoc before adding to it.
     */
    private static final Set<String> BASELINE = Set.of(
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
            "AuthenticationResource.checkAccess",
            "ChatCompletionsResource.create",
            "DatasetVersionsResource.createTag",
            "DatasetVersionsResource.deleteTag",
            "DatasetVersionsResource.restoreVersion",
            "DatasetVersionsResource.retrieveVersion",
            "DatasetVersionsResource.updateVersion",
            "DatasetsResource.applyDatasetItemChanges",
            "DatasetsResource.batchUpdate",
            "DatasetsResource.createDatasetItems",
            "DatasetsResource.createDatasetItemsFromCsv",
            "DatasetsResource.createDatasetItemsFromJson",
            "DatasetsResource.createDatasetItemsFromSpans",
            "DatasetsResource.createDatasetItemsFromTraces",
            "DatasetsResource.expandDataset",
            "DatasetsResource.getDatasetByIdentifier",
            "DatasetsResource.markDatasetExportJobViewed",
            "DatasetsResource.patchDatasetItem",
            "DatasetsResource.startDatasetExport",
            "DatasetsResource.streamDatasetItems",
            "EnvironmentsResource.create",
            "EnvironmentsResource.deleteEnvironmentsBatch",
            "EnvironmentsResource.update",
            "ExperimentsResource.batchUpdate",
            "ExperimentsResource.createExperimentItems",
            "ExperimentsResource.deleteExperimentItems",
            "ExperimentsResource.deleteExperimentsById",
            "ExperimentsResource.experimentItemsBulk",
            "ExperimentsResource.finishExperiments",
            "ExperimentsResource.streamExperimentItems",
            "ExperimentsResource.streamExperiments",
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
            "OllamaResource.listModels",
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
            "SpansResource.searchSpans",
            "TracesResource.deleteTraceThreads",
            "TracesResource.getTraceThread",
            "TracesResource.searchTraceThreads",
            "TracesResource.searchTraces",
            "WelcomeWizardResource.submitWizard",
            "WorkspacesResource.costsSummary",
            "WorkspacesResource.getCost",
            "WorkspacesResource.getMetric",
            "WorkspacesResource.metricsSummary");

    @ArchTest
    static final ArchRule mutating_private_endpoints_must_declare_required_permissions = methods()
            .that(new DescribedMutatingEndpoint())
            .should(declareRequiredPermissionsOrBeBaselined())
            .because("""
                    a missing @RequiredPermissions is not a default-deny: the resolver returns an empty list, \
                    RemoteAuthService omits the field as NON_EMPTY, and the write is gated on workspace \
                    membership alone. Annotate the endpoint, or — only as a deliberate decision that any \
                    workspace member may call it — add it to BASELINE
                    """);

    private static ArchCondition<JavaMethod> declareRequiredPermissionsOrBeBaselined() {
        return new ArchCondition<>("declare @RequiredPermissions or be a seeded baseline entry") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.isAnnotatedWith(RequiredPermissions.class) || BASELINE.contains(identifier(method))) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(method,
                        "%s is a mutating v1/private endpoint with no @RequiredPermissions"
                                .formatted(identifier(method))));
            }
        };
    }

    /**
     * Guards the baseline against rot: an entry that no longer names an unannotated mutating endpoint has been
     * fixed, renamed or deleted, and a stale entry would silently re-exempt a future endpoint that reuses the
     * name.
     */
    @ArchTest
    static final ArchRule baseline_must_not_contain_stale_entries = methods()
            .that(new DescribedMutatingEndpoint())
            .should(new ArchCondition<>("leave no stale BASELINE entry behind") {

                private final Set<String> unannotated = new java.util.HashSet<>();

                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    if (!method.isAnnotatedWith(RequiredPermissions.class)) {
                        unannotated.add(identifier(method));
                    }
                }

                @Override
                public void finish(ConditionEvents events) {
                    List<String> stale = BASELINE.stream()
                            .filter(entry -> !unannotated.contains(entry))
                            .sorted()
                            .toList();
                    if (!stale.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(stale,
                                "BASELINE entries no longer match an unannotated mutating endpoint, remove them: "
                                        + String.join(", ", stale)));
                    }
                }
            })
            .because(
                    "a baseline entry that no longer matches would silently exempt a future endpoint reusing that name");

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
