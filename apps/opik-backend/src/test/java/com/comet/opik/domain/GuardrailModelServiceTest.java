package com.comet.opik.domain;

import com.comet.opik.api.GuardrailExecutionScope;
import com.comet.opik.api.GuardrailModel;
import com.comet.opik.api.GuardrailPolicy;
import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortingFactoryGuardrailModels;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.unit.UnitManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.GuardrailTrainingRunStatus.COMPLETED;
import static com.comet.opik.api.GuardrailTrainingRunStatus.QUEUED;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Guardrail Model Service")
class GuardrailModelServiceTest {

    private static final String USER = "user";
    private static final TimeBasedEpochGenerator ID_GENERATOR = Generators.timeBasedEpochGenerator();

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final RequestContext requestContext = new RequestContext();
    private TransactionTemplate template;
    private Jdbi jdbi;
    private GuardrailPolicyDAO policies;
    private GuardrailTrainingService trainingService;
    private GuardrailModelService service;

    @BeforeAll
    void setUp() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
        template = new TransactionTemplate(new UnitManager(jdbi));
        policies = jdbi.onDemand(GuardrailPolicyDAO.class);
        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> ID_GENERATOR.generate());
        trainingService = new GuardrailTrainingServiceImpl(template, () -> requestContext, idGenerator);
        service = new GuardrailModelServiceImpl(template, () -> requestContext, idGenerator, trainingService,
                new SortingFactoryGuardrailModels(), new SortingQueryBuilder());
    }

    @BeforeEach
    void newWorkspace() {
        requestContext.setWorkspaceId(UUID.randomUUID().toString());
        requestContext.setUserName(USER);
    }

    /** Ends the model's run in flight as the run worker will, whose status writes come with it. */
    private GuardrailTrainingRun end(UUID modelId, GuardrailTrainingRunStatus status) {
        var run = trainingService.findInFlight(modelId).orElseThrow();
        jdbi.useHandle(
                handle -> handle.createUpdate("UPDATE guardrail_training_runs SET status = :status WHERE id = :id")
                        .bind("status", status.name())
                        .bind("id", run.id().toString())
                        .execute());
        return trainingService.get(modelId, run.id());
    }

    @Test
    @DisplayName("A model is created with its first run QUEUED, and no serving run yet")
    void create() {
        var id = service.create("Toxicity", "Flags toxic replies");

        var model = service.get(id);
        var firstRun = trainingService.findInFlight(id).orElseThrow();
        assertThat(model)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "lastUpdatedAt")
                .isEqualTo(GuardrailModel.builder()
                        .id(id)
                        .name("Toxicity")
                        .description("Flags toxic replies")
                        .latestRunId(firstRun.id())
                        .latestRun(firstRun)
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build());
        assertThat(firstRun.status()).isEqualTo(QUEUED);
        assertThat(firstRun.attempt()).isOne();
    }

    @Test
    @DisplayName("A taken name, ignoring case, is a conflict, and leaves no run behind")
    void createWhenNameTakenReturnsConflict() {
        var id = service.create("Toxicity", "first");

        assertThatThrownBy(() -> service.create("toxicity", "second"))
                .isInstanceOf(EntityAlreadyExistsException.class);

        var page = service.find(1, 10, null, false, null, List.of());
        assertThat(page.content()).extracting(GuardrailModel::id).containsExactly(id);
        long runsInTheWorkspace = jdbi.withHandle(handle -> handle
                .createQuery("SELECT COUNT(*) FROM guardrail_training_runs WHERE workspace_id = :workspaceId")
                .bind("workspaceId", requestContext.getWorkspaceId())
                .mapTo(Long.class)
                .one());
        assertThat(runsInTheWorkspace).isOne();
    }

    @Test
    @DisplayName("A model is found in its own workspace only")
    void getWhenAnotherWorkspaceReturnsNotFound() {
        var id = service.create("Toxicity", "Flags toxic replies");

        requestContext.setWorkspaceId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updateDescription(id, "intruder")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.retrain(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("The page filters, sorts and reports the fields it sorts by")
    void find() {
        var usable = service.create("alpha", "first");
        end(usable, COMPLETED);
        var training = service.create("beta", "second");

        var page = service.find(1, 10, null, false, null,
                List.of(new SortingField("name", Direction.DESC)));

        assertThat(page.content()).extracting(GuardrailModel::id).containsExactly(training, usable);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.sortableBy()).containsExactly("name", "created_at", "last_updated_at");
        assertThat(service.find(1, 10, null, true, null, List.of()).content())
                .extracting(GuardrailModel::id).containsExactly(usable);
        assertThat(service.find(1, 10, null, false, QUEUED, List.of()).content())
                .extracting(GuardrailModel::id).containsExactly(training);
        assertThat(service.find(1, 10, "et", false, null, List.of()).content())
                .extracting(GuardrailModel::id).containsExactly(training);
    }

    @Test
    @DisplayName("The description is updated")
    void updateDescription() {
        var id = service.create("Toxicity", "Flags toxic replies");

        service.updateDescription(id, "Flags insults");

        assertThat(service.get(id).description()).isEqualTo("Flags insults");
    }

    @Test
    @DisplayName("A retrain is refused while a run is in flight, and starts a new first attempt once it has ended")
    void retrain() {
        var id = service.create("Toxicity", "Flags toxic replies");

        assertThatThrownBy(() -> service.retrain(id))
                .isInstanceOfSatisfying(ClientErrorException.class,
                        exception -> assertThat(exception.getResponse().getStatus()).isEqualTo(409));

        var completed = end(id, COMPLETED);
        var runId = service.retrain(id);

        var model = service.get(id);
        assertThat(model.servingRunId()).isEqualTo(completed.id());
        assertThat(model.latestRunId()).isEqualTo(runId);
        assertThat(trainingService.get(id, runId).attempt()).isOne();
    }

    @Test
    @DisplayName("Of two retrains at once, the second waits for the first's lock and finds its run in flight")
    void retrainWhenConcurrentStartsOneRun() throws Exception {
        var id = service.create("Toxicity", "Flags toxic replies");
        end(id, COMPLETED);
        var firstRetrained = new CountDownLatch(1);
        var firstMayCommit = new CountDownLatch(1);

        // The first retrain runs inside a transaction held open after it, so its lock is held while the second starts.
        var first = CompletableFuture.supplyAsync(() -> template.inTransaction(WRITE, handle -> {
            var runId = service.retrain(id);
            firstRetrained.countDown();
            firstMayCommit.await();
            return runId;
        }));
        assertThat(firstRetrained.await(30, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(() -> service.retrain(id));

        Awaitility.await().during(500, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(second).as("the second retrain waits on the model row's lock")
                        .isNotDone());
        firstMayCommit.countDown();

        var runId = first.get(30, TimeUnit.SECONDS);
        assertThat(second).failsWithin(30, TimeUnit.SECONDS)
                .withThrowableThat()
                .havingRootCause()
                .isInstanceOfSatisfying(ClientErrorException.class,
                        exception -> assertThat(exception.getResponse().getStatus()).isEqualTo(409));
        assertThat(trainingService.findInFlight(id)).map(GuardrailTrainingRun::id).contains(runId);
    }

    @Test
    @DisplayName("A model a policy rule references cannot be deleted")
    void deleteWhenReferencedReturnsConflict() {
        var id = service.create("Toxicity", "Flags toxic replies");
        var policy = GuardrailPolicy.builder()
                .id(ID_GENERATOR.generate())
                .name("policy")
                .executionScope(GuardrailExecutionScope.ON_REQUEST)
                .build();
        policies.save(policy, requestContext.getWorkspaceId(), USER);
        policies.saveRules(List.of(ID_GENERATOR.generate()), policy.id(), List.of(GuardrailType.CUSTOM_CLASSIFIER),
                List.of(JsonUtils.createObjectNode().put("model_id", id.toString())), USER);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOfSatisfying(ClientErrorException.class,
                        exception -> assertThat(exception.getResponse().getStatus()).isEqualTo(409));

        assertThat(service.get(id)).isNotNull();
    }

    @Test
    @DisplayName("A model is deleted with its runs; deleting it again does nothing")
    void delete() {
        var id = service.create("Toxicity", "Flags toxic replies");
        end(id, COMPLETED);
        service.retrain(id);

        service.delete(id);

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThat(trainingService.find(id, 1, 10).total()).isZero();
        service.delete(id);
    }
}
