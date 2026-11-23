package com.comet.opik.domain;

import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.unit.UnitManager;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.GuardrailTrainingRunStatus.CANCELLED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.COMPLETED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.QUEUED;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Guardrail Training Service")
class GuardrailTrainingServiceTest {

    private static final String USER = "user";
    private static final TimeBasedEpochGenerator ID_GENERATOR = Generators.timeBasedEpochGenerator();

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final RequestContext requestContext = new RequestContext();
    private TransactionTemplate template;
    private GuardrailTrainingRunDAO runs;
    private GuardrailTrainingService service;

    @BeforeAll
    void setUp() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        var jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
        template = new TransactionTemplate(new UnitManager(jdbi));
        runs = jdbi.onDemand(GuardrailTrainingRunDAO.class);
        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> ID_GENERATOR.generate());
        service = new GuardrailTrainingServiceImpl(template, () -> requestContext, idGenerator);
    }

    @BeforeEach
    void newWorkspace() {
        requestContext.setWorkspaceId(UUID.randomUUID().toString());
        requestContext.setUserName(USER);
    }

    private GuardrailTrainingRun move(GuardrailTrainingRun run, GuardrailTrainingRunStatus newStatus) {
        assertThat(runs.updateStatus(run.id(), run.workspaceId(), run.status(), newStatus, null, null, USER))
                .isOne();
        return runs.findById(run.id(), run.workspaceId()).orElseThrow();
    }

    @Test
    @DisplayName("A run is created QUEUED as attempt 1 in the request's workspace, with the S3 key layout in its metadata")
    void create() {
        var modelId = ID_GENERATOR.generate();

        var run = service.create(modelId);

        assertThat(run)
                .usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "lastUpdatedAt")
                .isEqualTo(GuardrailTrainingRun.builder()
                        .modelId(modelId)
                        .workspaceId(requestContext.getWorkspaceId())
                        .status(QUEUED)
                        .attempt(1)
                        .metadata(JsonUtils.getJsonNodeFromString("{\"files\": {\"layout\": \"v1\"}}"))
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build());
        assertThat(service.get(modelId, run.id())).isEqualTo(run);
        assertThat(service.findInFlight(modelId)).contains(run);
    }

    @Test
    @DisplayName("Creating a run joins the caller's transaction, so it rolls back with it")
    void createJoinsTheCallersTransaction() {
        var modelId = ID_GENERATOR.generate();

        assertThatThrownBy(() -> template.inTransaction(WRITE, handle -> {
            service.create(modelId);
            throw new IllegalStateException("the caller fails after creating the run");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(service.findInFlight(modelId)).isEmpty();
    }

    @Test
    @DisplayName("A model's runs page newest first, and its serving run is its latest completed one")
    void findAndFindServingRun() {
        var modelId = ID_GENERATOR.generate();
        var first = move(service.create(modelId), COMPLETED);
        var second = service.create(modelId);

        var page = service.find(modelId, 1, 1);

        assertThat(page.content()).containsExactly(second);
        assertThat(page.total()).isEqualTo(2);
        assertThat(service.find(modelId, 2, 1).content()).containsExactly(first);
        assertThat(service.findServingRun(modelId)).contains(first);
        assertThat(service.findByIds(List.of(first.id(), second.id()))).containsExactlyInAnyOrder(first, second);
        assertThat(service.findByIds(List.of())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = GuardrailTrainingRunStatus.class, names = {"QUEUED", "RUNNING"})
    @DisplayName("A run in flight is cancelled with who cancelled it")
    void cancel(GuardrailTrainingRunStatus status) {
        var modelId = ID_GENERATOR.generate();
        var created = service.create(modelId);
        var run = status == QUEUED ? created : move(created, status);

        service.cancel(modelId, run.id());

        assertThat(service.get(modelId, run.id()))
                .usingRecursiveComparison()
                .ignoringFields("lastUpdatedAt")
                .isEqualTo(run.toBuilder()
                        .status(CANCELLED)
                        .statusMessage("Cancelled by " + USER)
                        .lastUpdatedBy(USER)
                        .build());
    }

    @Test
    @DisplayName("A run that has ended cannot be cancelled")
    void cancelWhenEndedReturnsConflict() {
        var modelId = ID_GENERATOR.generate();
        var completed = move(service.create(modelId), COMPLETED);

        assertThatThrownBy(() -> service.cancel(modelId, completed.id()))
                .isInstanceOfSatisfying(ClientErrorException.class,
                        exception -> assertThat(exception.getResponse().getStatus()).isEqualTo(409));

        assertThat(service.get(modelId, completed.id())).isEqualTo(completed);
    }

    @Test
    @DisplayName("A run is found only under its own model and workspace")
    void getWhenAnotherModelOrWorkspaceReturnsNotFound() {
        var modelId = ID_GENERATOR.generate();
        var run = service.create(modelId);

        assertThatThrownBy(() -> service.get(ID_GENERATOR.generate(), run.id()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel(ID_GENERATOR.generate(), run.id()))
                .isInstanceOf(NotFoundException.class);

        requestContext.setWorkspaceId(UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.get(modelId, run.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Deleting a model's runs returns their ids")
    void deleteByModel() {
        var modelId = ID_GENERATOR.generate();
        var first = move(service.create(modelId), COMPLETED);
        var second = service.create(modelId);

        assertThat(service.deleteByModel(modelId)).containsExactlyInAnyOrder(first.id(), second.id());

        assertThat(service.find(modelId, 1, 10).total()).isZero();
    }
}
