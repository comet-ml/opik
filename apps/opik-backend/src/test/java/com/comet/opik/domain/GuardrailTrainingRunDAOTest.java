package com.comet.opik.domain;

import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRunStatus;
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
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.unit.UnitManager;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.GuardrailTrainingRunStatus.CANCELLED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.COMPLETED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.FAILED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.QUEUED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.RUNNING;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Guardrail Training Run DAO")
class GuardrailTrainingRunDAOTest {

    private static final String USER = "user";
    private static final String WORKER = "worker";
    private static final JsonNode CREATED_METADATA = json("{\"files\": {\"layout\": \"v1\"}}");
    private static final TimeBasedEpochGenerator ID_GENERATOR = Generators.timeBasedEpochGenerator();

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private TransactionTemplate template;
    private GuardrailTrainingRunDAO runs;

    @BeforeAll
    void setUp() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        var jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
        template = new TransactionTemplate(new UnitManager(jdbi));
        runs = jdbi.onDemand(GuardrailTrainingRunDAO.class);
    }

    private static JsonNode json(String value) {
        return JsonUtils.getJsonNodeFromString(value);
    }

    /** A new model and its first attempt, as the create request writes them: one transaction. */
    private GuardrailTrainingRun createModel(String workspaceId) {
        var modelId = ID_GENERATOR.generate();
        var runId = ID_GENERATOR.generate();
        template.inTransaction(WRITE, handle -> {
            handle.attach(GuardrailModelDAO.class).save(modelId, workspaceId, "model-" + modelId, "Flags it", USER);
            handle.attach(GuardrailTrainingRunDAO.class).insert(runId, modelId, workspaceId, 1, CREATED_METADATA,
                    USER);
            return null;
        });
        return find(runId, workspaceId);
    }

    private GuardrailTrainingRun retrain(GuardrailTrainingRun anyRunOfTheModel) {
        var runId = ID_GENERATOR.generate();
        runs.insert(runId, anyRunOfTheModel.modelId(), anyRunOfTheModel.workspaceId(), 1, CREATED_METADATA, USER);
        return find(runId, anyRunOfTheModel.workspaceId());
    }

    private GuardrailTrainingRun move(GuardrailTrainingRun run, GuardrailTrainingRunStatus newStatus) {
        assertThat(runs.updateStatus(run.id(), run.workspaceId(), run.status(), newStatus, null, null, WORKER))
                .isOne();
        return find(run.id(), run.workspaceId());
    }

    private GuardrailTrainingRun failAndRetry(GuardrailTrainingRun run) {
        var nextId = ID_GENERATOR.generate();
        assertThat(runs.failAndQueueRetry(run, "capacity", null, nextId, CREATED_METADATA, WORKER)).isTrue();
        return find(nextId, run.workspaceId());
    }

    private GuardrailTrainingRun find(UUID runId, String workspaceId) {
        return runs.findById(runId, workspaceId).orElseThrow();
    }

    @Test
    @DisplayName("A run is inserted QUEUED with its attempt and metadata, and found in its workspace only")
    void insert() {
        var workspaceId = UUID.randomUUID().toString();

        var run = createModel(workspaceId);

        assertThat(run)
                .usingRecursiveComparison()
                .ignoringFields("id", "modelId", "createdAt", "lastUpdatedAt")
                .isEqualTo(GuardrailTrainingRun.builder()
                        .workspaceId(workspaceId)
                        .status(QUEUED)
                        .attempt(1)
                        .metadata(CREATED_METADATA)
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build());
        assertThat(runs.findById(run.id(), UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    @DisplayName("A status write moves the run and merges each metadata patch into what is there")
    void updateStatusMergesMetadata() {
        var run = createModel(UUID.randomUUID().toString());

        assertThat(runs.updateStatus(run.id(), run.workspaceId(), QUEUED, RUNNING, null,
                json("{\"job\": {\"id\": \"job-1\"}}"), WORKER)).isOne();
        assertThat(runs.updateStatus(run.id(), run.workspaceId(), RUNNING, RUNNING, null,
                json("{\"job\": {\"stage\": \"training\"}}"), WORKER)).isOne();

        assertThat(find(run.id(), run.workspaceId()))
                .usingRecursiveComparison()
                .ignoringFields("lastUpdatedAt")
                .isEqualTo(run.toBuilder()
                        .status(RUNNING)
                        .metadata(json("""
                                {"files": {"layout": "v1"}, "job": {"id": "job-1", "stage": "training"}}"""))
                        .lastUpdatedBy(WORKER)
                        .build());
    }

    @Test
    @DisplayName("A status write from a status the run has already left writes nothing")
    void updateStatusWhenRunMovedByAnotherWriterWritesNothing() {
        var queued = createModel(UUID.randomUUID().toString());
        var running = move(queued, RUNNING);

        assertThat(runs.updateStatus(queued.id(), queued.workspaceId(), QUEUED, CANCELLED, "cancelled",
                json("{\"cancel\": true}"), USER)).isZero();

        assertThat(find(queued.id(), queued.workspaceId())).isEqualTo(running);
    }

    @Test
    @DisplayName("A status write to a run in another workspace writes nothing")
    void updateStatusWhenAnotherWorkspaceWritesNothing() {
        var run = createModel(UUID.randomUUID().toString());

        assertThat(runs.updateStatus(run.id(), UUID.randomUUID().toString(), QUEUED, CANCELLED, null, null, USER))
                .isZero();

        assertThat(find(run.id(), run.workspaceId())).isEqualTo(run);
    }

    @Test
    @DisplayName("A failure with a retry ends the run FAILED and queues the next attempt, which finds it as previous")
    void failAndQueueRetry() {
        var running = move(createModel(UUID.randomUUID().toString()), RUNNING);
        var failure = json("{\"failure\": {\"reason\": \"capacity\", \"retryable\": true}}");
        var nextId = ID_GENERATOR.generate();

        assertThat(runs.failAndQueueRetry(running, "capacity", failure, nextId, CREATED_METADATA, WORKER)).isTrue();

        var failed = find(running.id(), running.workspaceId());
        assertThat(failed)
                .usingRecursiveComparison()
                .ignoringFields("lastUpdatedAt")
                .isEqualTo(running.toBuilder()
                        .status(FAILED)
                        .statusMessage("capacity")
                        .metadata(json("""
                                {"files": {"layout": "v1"}, \
                                "failure": {"reason": "capacity", "retryable": true}}"""))
                        .build());
        var next = find(nextId, running.workspaceId());
        assertThat(next)
                .usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "lastUpdatedAt")
                .isEqualTo(running.toBuilder()
                        .status(QUEUED)
                        .attempt(2)
                        .metadata(CREATED_METADATA)
                        .createdBy(WORKER)
                        .lastUpdatedBy(WORKER)
                        .build());
        assertThat(runs.findPreviousAttempt(next)).contains(failed);
    }

    @Test
    @DisplayName("A repeated failure of the same run queues no second attempt")
    void failAndQueueRetryWhenRepeatedQueuesNoSecondAttempt() {
        var running = move(createModel(UUID.randomUUID().toString()), RUNNING);
        var next = failAndRetry(running);

        assertThat(runs.failAndQueueRetry(running, "capacity", null, ID_GENERATOR.generate(), CREATED_METADATA,
                WORKER)).isFalse();

        assertThat(runs.findByModel(running.modelId(), running.workspaceId(), 10, 0))
                .extracting(GuardrailTrainingRun::id)
                .containsExactly(next.id(), running.id());
    }

    @Test
    @DisplayName("When the next attempt cannot be inserted, the run's FAILED is rolled back with it")
    void failAndQueueRetryWhenInsertFailsRollsBack() {
        var running = move(createModel(UUID.randomUUID().toString()), RUNNING);

        // The run's own id as the next attempt's: the insert hits the primary key after the update went through, in the
        // one WRITE transaction a caller runs the method in.
        assertThatThrownBy(() -> template.inTransaction(WRITE, handle -> handle.attach(GuardrailTrainingRunDAO.class)
                .failAndQueueRetry(running, "capacity", null, running.id(), CREATED_METADATA, WORKER)))
                .isInstanceOf(UnableToExecuteStatementException.class)
                .hasCauseInstanceOf(SQLIntegrityConstraintViolationException.class);

        assertThat(find(running.id(), running.workspaceId())).isEqualTo(running);
        assertThat(runs.countByModel(running.modelId(), running.workspaceId())).isOne();
    }

    @Test
    @DisplayName("Runs are found by id in their workspace only")
    void findByIds() {
        var workspaceId = UUID.randomUUID().toString();
        var first = createModel(workspaceId);
        var second = move(createModel(workspaceId), COMPLETED);
        var otherWorkspace = createModel(UUID.randomUUID().toString());

        assertThat(runs.findByIds(List.of(first.id(), second.id(), otherWorkspace.id()), workspaceId))
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("The serving run is the most recent completed one; a newer failed run does not displace it")
    void findServingRun() {
        var first = createModel(UUID.randomUUID().toString());
        assertThat(runs.findServingRun(first.modelId(), first.workspaceId())).isEmpty();

        move(first, COMPLETED);
        var second = move(retrain(first), COMPLETED);
        move(retrain(first), FAILED);

        assertThat(runs.findServingRun(first.modelId(), first.workspaceId())).contains(second);
        assertThat(runs.findServingRun(first.modelId(), UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    @DisplayName("The run in flight is found while QUEUED or RUNNING, and not once it ends")
    void findInFlight() {
        var queued = createModel(UUID.randomUUID().toString());
        assertThat(runs.findInFlight(queued.modelId(), queued.workspaceId())).contains(queued);

        var running = move(queued, RUNNING);
        assertThat(runs.findInFlight(queued.modelId(), queued.workspaceId())).contains(running);

        move(running, COMPLETED);
        assertThat(runs.findInFlight(queued.modelId(), queued.workspaceId())).isEmpty();
    }

    @Test
    @DisplayName("A first attempt has no previous attempt, even after an earlier training of the model failed")
    void findPreviousAttemptWhenFirstAttemptReturnsEmpty() {
        var earlierTraining = move(createModel(UUID.randomUUID().toString()), FAILED);

        var retrained = retrain(earlierTraining);

        assertThat(runs.findPreviousAttempt(retrained)).isEmpty();
    }

    @Test
    @DisplayName("A model's runs page newest first, in its workspace only")
    void findByModel() {
        var first = move(createModel(UUID.randomUUID().toString()), COMPLETED);
        var second = move(retrain(first), FAILED);
        var third = retrain(first);

        assertThat(runs.findByModel(first.modelId(), first.workspaceId(), 2, 0)).containsExactly(third, second);
        assertThat(runs.findByModel(first.modelId(), first.workspaceId(), 2, 2)).containsExactly(first);
        assertThat(runs.countByModel(first.modelId(), first.workspaceId())).isEqualTo(3);
        assertThat(runs.findByModel(first.modelId(), UUID.randomUUID().toString(), 10, 0)).isEmpty();
        assertThat(runs.countByModel(first.modelId(), UUID.randomUUID().toString())).isZero();
    }

    @Test
    @DisplayName("The worker's scan finds the runs in flight of every workspace, the longest unchanged first")
    void findAllInFlight() {
        var workspaceId = UUID.randomUUID().toString();
        var queued = createModel(workspaceId);
        var running = move(createModel(UUID.randomUUID().toString()), RUNNING);
        var completed = move(createModel(workspaceId), COMPLETED);
        var ours = Set.of(queued.id(), running.id(), completed.id());

        // The container is shared with other test classes, whose runs in flight the scan also returns.
        assertThat(runs.findAllInFlight(1_000))
                .filteredOn(run -> ours.contains(run.id()))
                .containsExactly(queued, running);
        assertThat(runs.countInFlight(workspaceId)).isOne();
    }

    @Test
    @DisplayName("A model's runs are listed and deleted in its workspace only")
    void deleteByModel() {
        var first = move(createModel(UUID.randomUUID().toString()), FAILED);
        var second = retrain(first);
        var otherModel = createModel(first.workspaceId());

        assertThat(runs.findIdsByModel(first.modelId(), first.workspaceId()))
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(runs.deleteByModel(first.modelId(), UUID.randomUUID().toString())).isZero();

        assertThat(runs.deleteByModel(first.modelId(), first.workspaceId())).isEqualTo(2);

        assertThat(runs.findIdsByModel(first.modelId(), first.workspaceId())).isEmpty();
        assertThat(runs.findById(otherModel.id(), otherModel.workspaceId())).contains(otherModel);
    }
}
