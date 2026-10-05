package com.comet.opik.db;

import com.clickhouse.client.api.Client;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import lombok.Builder;
import org.awaitility.Awaitility;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

/**
 * Two workspaces seeded through the public API, as an Opik user would write them: workspace A with projects A1 and
 * A2, workspace B with B1. Each project gets a small sample of every table the free-form SQL accounts read: traces
 * in their own threads, a span per trace, a feedback score per trace, a dataset with items and an experiment over
 * them. Ids are UUIDv7, and the projects' ids are assigned by the API.
 */
@Builder(toBuilder = true)
public record FreeFormSqlTestData(Workspace a, Workspace b, Project a1, Project a2, Project b1) {

    /** Rows per project and table: enough to tell scopes apart, small enough to keep the suites fast. */
    public static final int PER_PROJECT = 5;
    private static final String USER = UUID.randomUUID().toString();
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    @Builder(toBuilder = true)
    public record Workspace(String id, String name, String apiKey) {
    }

    @Builder(toBuilder = true)
    public record Project(Workspace workspace, String name, UUID id) {
    }

    public static FreeFormSqlTestData seed(ClientSupport client, WireMockRuntime wireMock, Client admin) {
        var baseUrl = TestUtils.getBaseUrl(client);
        PodamFactory factory = PodamFactoryUtils.newPodamFactory();
        var seeder = new Seeder(factory, new ProjectResourceClient(client, baseUrl, factory),
                new TraceResourceClient(client, baseUrl), new SpanResourceClient(client, baseUrl),
                new DatasetResourceClient(client, baseUrl), new ExperimentResourceClient(client, baseUrl, factory));

        var a = workspace(wireMock);
        var b = workspace(wireMock);
        var data = FreeFormSqlTestData.builder().a(a).b(b)
                .a1(seeder.project(a)).a2(seeder.project(a)).b1(seeder.project(b))
                .build();
        // Threads are written after the traces, off the request: wait for every project's.
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(250, TimeUnit.MILLISECONDS)
                .until(() -> Set.of(data.a1(), data.a2(), data.b1()).stream().allMatch(project -> PER_PROJECT == count(
                        admin, "trace_threads", project)));
        return data;
    }

    private static Workspace workspace(WireMockRuntime wireMock) {
        var workspace = Workspace.builder().id(UUID.randomUUID().toString()).name(UUID.randomUUID().toString())
                .apiKey(UUID.randomUUID().toString()).build();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), workspace.apiKey(), workspace.name(), workspace.id(),
                USER);
        return workspace;
    }

    private static long count(Client admin, String table, Project project) {
        return Long.parseLong(admin.queryAll("""
                SELECT count() FROM %s.%s WHERE workspace_id = '%s' AND project_id = '%s'
                """.formatted(DATABASE_NAME, table, project.workspace().id(), project.id())).getFirst().getString(1));
    }

    private record Seeder(PodamFactory factory, ProjectResourceClient projects,
            TraceResourceClient traces,
            SpanResourceClient spans, DatasetResourceClient datasets, ExperimentResourceClient experiments) {

        Project project(Workspace workspace) {
            String name = UUID.randomUUID().toString();
            var project = Project.builder().workspace(workspace).name(name)
                    .id(projects.createProject(name, workspace.apiKey(), workspace.name())).build();

            List<Trace> projectTraces = IntStream.range(0, PER_PROJECT)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .id(ID_GENERATOR.generateId()).projectName(name).projectId(null)
                            .threadId(ID_GENERATOR.generateId().toString()).feedbackScores(null).build())
                    .toList();
            traces.batchCreateTraces(projectTraces, workspace.apiKey(), workspace.name());

            spans.batchCreateSpans(projectTraces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .id(ID_GENERATOR.generateId()).projectName(name).projectId(null).traceId(trace.id())
                            .parentSpanId(null).feedbackScores(null).build())
                    .toList(), workspace.apiKey(), workspace.name());

            traces.feedbackScores(projectTraces.stream()
                    .<FeedbackScoreBatchItem>map(trace -> factory.manufacturePojo(FeedbackScoreBatchItem.class)
                            .toBuilder().id(trace.id()).projectName(name).projectId(null)
                            // No category, so the score is a feedback score rather than an assertion result.
                            .categoryName(null).build())
                    .toList(), workspace.apiKey(), workspace.name());

            String datasetName = UUID.randomUUID().toString();
            List<DatasetItem> items = IntStream.range(0, PER_PROJECT)
                    .mapToObj(i -> DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                            .id(ID_GENERATOR.generateId()).build())
                    .toList();
            datasets.createDatasetItems(DatasetResourceClient.buildDatasetItemBatch(factory).toBuilder()
                    .datasetName(datasetName).datasetId(null).items(items).build(), workspace.name(),
                    workspace.apiKey());

            UUID experimentId = experiments.create(experiments.createPartialExperiment()
                    .datasetName(datasetName).projectId(project.id()).build(), workspace.apiKey(), workspace.name());
            experiments.createExperimentItem(IntStream.range(0, PER_PROJECT)
                    .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .experimentId(experimentId).datasetItemId(items.get(i).id())
                            .traceId(projectTraces.get(i).id()).build())
                    .collect(Collectors.toSet()), workspace.apiKey(), workspace.name());
            return project;
        }
    }
}
