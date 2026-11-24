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
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.GuardrailTrainingRunStatus.CANCELLED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.COMPLETED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.FAILED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.QUEUED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Guardrail Training Run DAO")
class GuardrailTrainingRunDAOTest {

    private static final String USER = "user";
    private static final JsonNode CREATED_METADATA = json("{\"files\": {\"layout\": \"v1\"}}");
    private static final TimeBasedEpochGenerator ID_GENERATOR = Generators.timeBasedEpochGenerator();

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private Jdbi jdbi;
    private GuardrailTrainingRunDAO runs;

    @BeforeAll
    void setUp() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
        runs = jdbi.onDemand(GuardrailTrainingRunDAO.class);
    }

    private static JsonNode json(String value) {
        return JsonUtils.getJsonNodeFromString(value);
    }

    /** A first attempt of a new model. The runs table needs no model row: it holds no foreign key. */
    private GuardrailTrainingRun createFirstRun(String workspaceId) {
        var runId = ID_GENERATOR.generate();
        runs.insert(runId, ID_GENERATOR.generate(), workspaceId, 1, CREATED_METADATA, USER);
        return find(runId, workspaceId);
    }

    private GuardrailTrainingRun retrain(GuardrailTrainingRun anyRunOfTheModel) {
        var runId = ID_GENERATOR.generate();
        runs.insert(runId, anyRunOfTheModel.modelId(), anyRunOfTheModel.workspaceId(), 1, CREATED_METADATA, USER);
        return find(runId, anyRunOfTheModel.workspaceId());
    }

    /** Moves the run as the run worker will, whose status writes come with it. */
    private GuardrailTrainingRun move(GuardrailTrainingRun run, GuardrailTrainingRunStatus newStatus) {
        jdbi.useHandle(
                handle -> handle.createUpdate("UPDATE guardrail_training_runs SET status = :status WHERE id = :id")
                        .bind("status", newStatus.name())
                        .bind("id", run.id().toString())
                        .execute());
        return find(run.id(), run.workspaceId());
    }

    private GuardrailTrainingRun find(UUID runId, String workspaceId) {
        return runs.findById(runId, workspaceId).orElseThrow();
    }

    @Test
    @DisplayName("A run is inserted QUEUED with its attempt and metadata, and found in its workspace only")
    void insert() {
        var workspaceId = UUID.randomUUID().toString();

        var run = createFirstRun(workspaceId);

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
    @DisplayName("Runs are found by id in their workspace only")
    void findByIds() {
        var workspaceId = UUID.randomUUID().toString();
        var first = createFirstRun(workspaceId);
        var second = move(createFirstRun(workspaceId), COMPLETED);
        var otherWorkspace = createFirstRun(UUID.randomUUID().toString());

        assertThat(runs.findByIds(List.of(first.id(), second.id(), otherWorkspace.id()), workspaceId))
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("The run in flight is found while QUEUED or RUNNING, and not once it ends")
    void findInFlight() {
        var queued = createFirstRun(UUID.randomUUID().toString());
        assertThat(runs.findInFlight(queued.modelId(), queued.workspaceId())).contains(queued);

        var running = move(queued, RUNNING);
        assertThat(runs.findInFlight(queued.modelId(), queued.workspaceId())).contains(running);

        move(running, COMPLETED);
        assertThat(runs.findInFlight(queued.modelId(), queued.workspaceId())).isEmpty();
    }

    @Test
    @DisplayName("A model's runs page newest first, in its workspace only")
    void findByModel() {
        var first = move(createFirstRun(UUID.randomUUID().toString()), COMPLETED);
        var second = move(retrain(first), FAILED);
        var third = retrain(first);

        assertThat(runs.findByModel(first.modelId(), first.workspaceId(), 2, 0)).containsExactly(third, second);
        assertThat(runs.findByModel(first.modelId(), first.workspaceId(), 2, 2)).containsExactly(first);
        assertThat(runs.countByModel(first.modelId(), first.workspaceId())).isEqualTo(3);
        assertThat(runs.findByModel(first.modelId(), UUID.randomUUID().toString(), 10, 0)).isEmpty();
        assertThat(runs.countByModel(first.modelId(), UUID.randomUUID().toString())).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = GuardrailTrainingRunStatus.class, names = {"QUEUED", "RUNNING"})
    @DisplayName("A run in flight is cancelled in one write, with who cancelled it and when it finished")
    void cancel(GuardrailTrainingRunStatus status) {
        var run = move(createFirstRun(UUID.randomUUID().toString()), status);

        assertThat(runs.cancel(run.id(), run.modelId(), run.workspaceId(), "Cancelled by " + USER, USER)).isOne();

        var cancelled = find(run.id(), run.workspaceId());
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(cancelled)
                .usingRecursiveComparison()
                .ignoringFields("finishedAt", "lastUpdatedAt")
                .isEqualTo(run.toBuilder()
                        .status(CANCELLED)
                        .statusMessage("Cancelled by " + USER)
                        .lastUpdatedBy(USER)
                        .build());
    }

    @ParameterizedTest
    @EnumSource(value = GuardrailTrainingRunStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    @DisplayName("A run that has ended is left as it is")
    void cancelWhenEndedWritesNothing(GuardrailTrainingRunStatus status) {
        var run = move(createFirstRun(UUID.randomUUID().toString()), status);

        assertThat(runs.cancel(run.id(), run.modelId(), run.workspaceId(), "Cancelled by " + USER, USER)).isZero();

        assertThat(find(run.id(), run.workspaceId())).isEqualTo(run);
    }

    @Test
    @DisplayName("A run is cancelled under its own model and workspace only")
    void cancelWhenAnotherModelOrWorkspaceWritesNothing() {
        var run = createFirstRun(UUID.randomUUID().toString());

        assertThat(runs.cancel(run.id(), ID_GENERATOR.generate(), run.workspaceId(), "Cancelled", USER)).isZero();
        assertThat(runs.cancel(run.id(), run.modelId(), UUID.randomUUID().toString(), "Cancelled", USER)).isZero();

        assertThat(find(run.id(), run.workspaceId())).isEqualTo(run);
    }

    @Test
    @DisplayName("A model's runs are listed and deleted in its workspace only")
    void deleteByModel() {
        var first = move(createFirstRun(UUID.randomUUID().toString()), FAILED);
        var second = retrain(first);
        var otherModel = createFirstRun(first.workspaceId());

        assertThat(runs.findIdsByModel(first.modelId(), first.workspaceId()))
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(runs.deleteByModel(first.modelId(), UUID.randomUUID().toString())).isZero();

        assertThat(runs.deleteByModel(first.modelId(), first.workspaceId())).isEqualTo(2);

        assertThat(runs.findIdsByModel(first.modelId(), first.workspaceId())).isEmpty();
        assertThat(runs.findById(otherModel.id(), otherModel.workspaceId())).contains(otherModel);
    }
}
