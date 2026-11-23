package com.comet.opik.domain;

import com.comet.opik.api.GuardrailExecutionScope;
import com.comet.opik.api.GuardrailModel;
import com.comet.opik.api.GuardrailPolicy;
import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.unit.UnitManager;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.api.GuardrailTrainingRunStatus.CANCELLED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.COMPLETED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.FAILED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.RUNNING;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Guardrail Model DAO")
class GuardrailModelDAOTest {

    private static final String USER = "user";
    private static final JsonNode RUN_METADATA = JsonUtils.getJsonNodeFromString("{\"files\": {\"layout\": \"v1\"}}");
    private static final TimeBasedEpochGenerator ID_GENERATOR = Generators.timeBasedEpochGenerator();

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private TransactionTemplate template;
    private GuardrailModelDAO models;
    private GuardrailTrainingRunDAO runs;
    private GuardrailPolicyDAO policies;

    @BeforeAll
    void setUp() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        var jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
        template = new TransactionTemplate(new UnitManager(jdbi));
        models = jdbi.onDemand(GuardrailModelDAO.class);
        runs = jdbi.onDemand(GuardrailTrainingRunDAO.class);
        policies = jdbi.onDemand(GuardrailPolicyDAO.class);
    }

    /** A new model and its first attempt, as the create request writes them: one transaction. */
    private GuardrailTrainingRun createModel(String workspaceId, String name) {
        var modelId = ID_GENERATOR.generate();
        var runId = ID_GENERATOR.generate();
        template.inTransaction(WRITE, handle -> {
            handle.attach(GuardrailModelDAO.class).save(modelId, workspaceId, name, "Flags " + name, USER);
            handle.attach(GuardrailTrainingRunDAO.class).insert(runId, modelId, workspaceId, 1, RUN_METADATA, USER);
            return null;
        });
        return runs.findById(runId, workspaceId).orElseThrow();
    }

    private GuardrailTrainingRun retrain(GuardrailTrainingRun anyRunOfTheModel) {
        var runId = ID_GENERATOR.generate();
        runs.insert(runId, anyRunOfTheModel.modelId(), anyRunOfTheModel.workspaceId(), 1, RUN_METADATA, USER);
        return runs.findById(runId, anyRunOfTheModel.workspaceId()).orElseThrow();
    }

    private GuardrailTrainingRun move(GuardrailTrainingRun run, GuardrailTrainingRunStatus newStatus) {
        assertThat(runs.updateStatus(run.id(), run.workspaceId(), run.status(), newStatus, null, null, USER))
                .isOne();
        return runs.findById(run.id(), run.workspaceId()).orElseThrow();
    }

    private GuardrailTrainingRun failAndRetry(GuardrailTrainingRun run) {
        var nextId = ID_GENERATOR.generate();
        assertThat(runs.failAndQueueRetry(run, "capacity", null, nextId, RUN_METADATA, USER)).isTrue();
        return runs.findById(nextId, run.workspaceId()).orElseThrow();
    }

    private GuardrailModel findModel(GuardrailTrainingRun anyRunOfTheModel) {
        return models.findById(anyRunOfTheModel.modelId(), anyRunOfTheModel.workspaceId()).orElseThrow();
    }

    private void createPolicy(String workspaceId, GuardrailType ruleType, UUID modelId) {
        var policy = GuardrailPolicy.builder()
                .id(ID_GENERATOR.generate())
                .name("policy-" + UUID.randomUUID())
                .executionScope(GuardrailExecutionScope.ON_REQUEST)
                .build();
        var config = JsonUtils.createObjectNode().put("model_id", modelId.toString());
        policies.save(policy, workspaceId, USER);
        policies.saveRules(List.of(ID_GENERATOR.generate()), policy.id(), List.of(ruleType), List.of(config), USER);
    }

    @Test
    @DisplayName("A saved model is found by id in its workspace, with no serving or latest run before it has runs")
    void saveAndFindById() {
        var workspaceId = UUID.randomUUID().toString();
        var modelId = ID_GENERATOR.generate();

        models.save(modelId, workspaceId, "Toxicity", "Flags toxic replies", USER);

        assertThat(models.findById(modelId, workspaceId).orElseThrow())
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "lastUpdatedAt")
                .isEqualTo(GuardrailModel.builder()
                        .id(modelId)
                        .name("Toxicity")
                        .description("Flags toxic replies")
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build());
        assertThat(models.findById(modelId, UUID.randomUUID().toString())).isEmpty();
    }

    /** A model's serving run id, null when none, and its latest run id. */
    private record RunIds(UUID servingRunId, UUID latestRunId) {
    }

    Stream<Arguments> servingAndLatestRunCases() {
        return Stream.of(
                Arguments.of("first attempt queued",
                        (Function<GuardrailTrainingRun, RunIds>) run -> new RunIds(null, run.id())),
                Arguments.of("first attempt completed",
                        (Function<GuardrailTrainingRun, RunIds>) run -> {
                            move(move(run, RUNNING), COMPLETED);
                            return new RunIds(run.id(), run.id());
                        }),
                Arguments.of("attempt failed, retry queued",
                        (Function<GuardrailTrainingRun, RunIds>) run -> new RunIds(null,
                                failAndRetry(move(run, RUNNING)).id())),
                Arguments.of("first attempt cancelled",
                        (Function<GuardrailTrainingRun, RunIds>) run -> new RunIds(null, move(run, CANCELLED).id())),
                Arguments.of("retrain running after a completed run",
                        (Function<GuardrailTrainingRun, RunIds>) run -> new RunIds(run.id(),
                                move(retrain(move(run, COMPLETED)), RUNNING).id())),
                Arguments.of("retrain failed after a completed run",
                        (Function<GuardrailTrainingRun, RunIds>) run -> new RunIds(run.id(),
                                move(retrain(move(run, COMPLETED)), FAILED).id())),
                Arguments.of("retrain completed after a completed run",
                        (Function<GuardrailTrainingRun, RunIds>) run -> {
                            var retrained = move(retrain(move(run, COMPLETED)), COMPLETED);
                            return new RunIds(retrained.id(), retrained.id());
                        }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servingAndLatestRunCases")
    @DisplayName("A model carries its most recent completed run as serving and its most recent run as latest")
    void servingAndLatestRuns(String scenario, Function<GuardrailTrainingRun, RunIds> steps) {
        var firstRun = createModel(UUID.randomUUID().toString(), "model");

        var expected = steps.apply(firstRun);

        var model = findModel(firstRun);
        assertThat(new RunIds(model.servingRunId(), model.latestRunId())).isEqualTo(expected);
        assertThat(models.findByIds(List.of(model.id()), firstRun.workspaceId())).containsExactly(model);
    }

    @Test
    @DisplayName("A name is unique per workspace ignoring case, and free in another workspace")
    void saveWhenNameTakenIgnoringCaseThrowsConstraintViolation() {
        var workspaceId = UUID.randomUUID().toString();
        models.save(ID_GENERATOR.generate(), workspaceId, "Toxicity", "first", USER);

        assertThatThrownBy(() -> models.save(ID_GENERATOR.generate(), workspaceId, "toxicity", "second", USER))
                .isInstanceOf(UnableToExecuteStatementException.class)
                .hasCauseInstanceOf(SQLIntegrityConstraintViolationException.class);

        models.save(ID_GENERATOR.generate(), UUID.randomUUID().toString(), "Toxicity", "third", USER);
        assertThat(models.count(workspaceId, null, false, null)).isOne();
    }

    @Test
    @DisplayName("The description is updated in the model's workspace only")
    void updateDescription() {
        var run = createModel(UUID.randomUUID().toString(), "Toxicity");
        var before = findModel(run);

        assertThat(models.updateDescription(run.modelId(), UUID.randomUUID().toString(), "intruder", "other"))
                .isZero();
        assertThat(findModel(run)).isEqualTo(before);

        assertThat(models.updateDescription(run.modelId(), run.workspaceId(), "Flags insults", "editor")).isOne();
        assertThat(findModel(run))
                .usingRecursiveComparison()
                .ignoringFields("lastUpdatedAt")
                .isEqualTo(before.toBuilder().description("Flags insults").lastUpdatedBy("editor").build());
    }

    @Test
    @DisplayName("The page filters by a usable model, by its latest run's status and by a partial name")
    void findPageIdsFilters() {
        var workspaceId = UUID.randomUUID().toString();
        var usableAndRetraining = retrain(move(createModel(workspaceId, "alpha"), COMPLETED));
        var training = createModel(workspaceId, "beta_1");
        var failed = move(createModel(workspaceId, "betaX1"), FAILED);
        createModel(UUID.randomUUID().toString(), "beta_1");

        assertThat(models.findPageIds(workspaceId, null, true, null, null, 10, 0))
                .containsExactly(usableAndRetraining.modelId());
        assertThat(models.count(workspaceId, null, true, null)).isOne();

        assertThat(models.findPageIds(workspaceId, null, false, GuardrailTrainingRunStatus.QUEUED, null, 10, 0))
                .containsExactly(training.modelId(), usableAndRetraining.modelId());
        assertThat(models.findPageIds(workspaceId, null, false, FAILED, null, 10, 0))
                .containsExactly(failed.modelId());
        assertThat(models.findPageIds(workspaceId, null, true, GuardrailTrainingRunStatus.QUEUED, null, 10, 0))
                .containsExactly(usableAndRetraining.modelId());

        var underscore = GuardrailPolicyDAO.escapeLike("_1");
        assertThat(models.findPageIds(workspaceId, underscore, false, null, null, 10, 0))
                .containsExactly(training.modelId());
        assertThat(models.count(workspaceId, underscore, false, null)).isOne();
        assertThat(models.count(workspaceId, null, false, null)).isEqualTo(3);
    }

    @Test
    @DisplayName("Pages come newest first by default, or in the order of the sort fields; bodies by id per workspace")
    void findPageIdsAndFindByIds() {
        var workspaceId = UUID.randomUUID().toString();
        var charlie = findModel(createModel(workspaceId, "charlie"));
        var alpha = findModel(createModel(workspaceId, "alpha"));
        var bravo = findModel(createModel(workspaceId, "bravo"));
        var otherWorkspace = findModel(createModel(UUID.randomUUID().toString(), "delta"));

        assertThat(models.findPageIds(workspaceId, null, false, null, null, 2, 0))
                .containsExactly(bravo.id(), alpha.id());
        assertThat(models.findPageIds(workspaceId, null, false, null, null, 2, 2)).containsExactly(charlie.id());
        assertThat(models.findPageIds(workspaceId, null, false, null, "name ASC", 10, 0))
                .containsExactly(alpha.id(), bravo.id(), charlie.id());
        assertThat(models.findByIds(List.of(alpha.id(), charlie.id(), otherWorkspace.id()), workspaceId))
                .containsExactlyInAnyOrder(alpha, charlie);
    }

    @Test
    @DisplayName("A model is referenced only by a CUSTOM_CLASSIFIER rule of a policy in its own workspace")
    void isReferencedByRule() {
        var workspaceId = UUID.randomUUID().toString();
        var referenced = createModel(workspaceId, "referenced").modelId();
        var namedByAnotherRuleType = createModel(workspaceId, "named by another rule type").modelId();
        var referencedFromAnotherWorkspace = createModel(workspaceId, "referenced from another workspace").modelId();
        var unreferenced = createModel(workspaceId, "unreferenced").modelId();
        createPolicy(workspaceId, GuardrailType.CUSTOM_CLASSIFIER, referenced);
        createPolicy(workspaceId, GuardrailType.TOPIC, namedByAnotherRuleType);
        createPolicy(UUID.randomUUID().toString(), GuardrailType.CUSTOM_CLASSIFIER, referencedFromAnotherWorkspace);

        assertThat(models.isReferencedByRule(referenced, workspaceId)).isTrue();
        assertThat(models.isReferencedByRule(namedByAnotherRuleType, workspaceId)).isFalse();
        assertThat(models.isReferencedByRule(referencedFromAnotherWorkspace, workspaceId)).isFalse();
        assertThat(models.isReferencedByRule(unreferenced, workspaceId)).isFalse();
    }

    @Test
    @DisplayName("A model is deleted in its own workspace only")
    void delete() {
        var run = createModel(UUID.randomUUID().toString(), "Toxicity");

        assertThat(models.delete(run.modelId(), UUID.randomUUID().toString())).isZero();
        assertThat(models.findById(run.modelId(), run.workspaceId())).isPresent();

        assertThat(models.delete(run.modelId(), run.workspaceId())).isOne();
        assertThat(models.findById(run.modelId(), run.workspaceId())).isEmpty();
    }
}
