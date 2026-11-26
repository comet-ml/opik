package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThreadBatchUpdate;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.events.TraceThreadsUpdated;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.GuardrailsGenerator;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.threads.TraceThreadDAO;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The source filter the annotation queue routing consumer applies, against a real database: of the
 * entities a message names, which ones an SDK logged.
 *
 * <p>Both queries read the latest version of a row from a {@code ReplacingMergeTree}, where a stale
 * version survives until the parts merge — which a test can reproduce, because nothing here merges in
 * between. Neither read has a REST surface of its own, so the DAOs are driven directly, with the data
 * written through the public API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("Annotation Queue Routing Reads Test")
class AnnotationQueueRoutingReadsTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(20);

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private GuardrailsResourceClient guardrailsResourceClient;
    private TraceDAO traceDAO;
    private ThreadDAO threadDAO;
    private TraceThreadDAO traceThreadDAO;
    private SpanDAO spanDAO;
    private TraceThreadService traceThreadService;

    private final Queue<TraceThreadsUpdated> threadsUpdated = new ConcurrentLinkedQueue<>();
    private final Object threadsUpdatedRecorder = new Object() {
        @Subscribe
        public void record(TraceThreadsUpdated event) {
            threadsUpdated.add(event);
        }
    };

    private EventBus eventBus;

    @BeforeAll
    void setUpAll(ClientSupport client, TraceDAO traceDAO, ThreadDAO threadDAO, TraceThreadDAO traceThreadDAO,
            SpanDAO spanDAO, TraceThreadService traceThreadService, EventBus eventBus) {
        this.eventBus = eventBus;
        eventBus.register(threadsUpdatedRecorder);
        this.traceThreadService = traceThreadService;
        this.traceDAO = traceDAO;
        this.threadDAO = threadDAO;
        this.traceThreadDAO = traceThreadDAO;
        this.spanDAO = spanDAO;

        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);

        traceResourceClient = new TraceResourceClient(client, TestUtils.getBaseUrl(client));
        spanResourceClient = new SpanResourceClient(client, TestUtils.getBaseUrl(client));
        guardrailsResourceClient = new GuardrailsResourceClient(client, TestUtils.getBaseUrl(client));
    }

    @AfterAll
    void tearDownAll() {
        eventBus.unregister(threadsUpdatedRecorder);
        setup.wireMock.server().stop();
    }

    @Test
    @DisplayName("logging source: only traces an SDK logged are returned")
    void loggingSourceIdsKeepSdkTracesOnly() {
        String projectName = randomName("project");
        UUID sdk = createTrace(projectName, Source.SDK, null);
        UUID playground = createTrace(projectName, Source.PLAYGROUND, null);
        UUID evaluator = createTrace(projectName, Source.EVALUATOR, null);
        UUID experiment = createTrace(projectName, Source.EXPERIMENT, null);
        UUID projectId = projectIdOf(sdk);

        var kept = traceDAO.getLoggingSourceIds(Set.of(projectId), Set.of(sdk, playground, evaluator, experiment))
                .contextWrite(this::workspaceContext)
                .block();

        assertThat(kept)
                .as("a queue is for production traffic: playground, evaluator and experiment traffic is not")
                .containsExactly(sdk);
    }

    /**
     * The reason the query reads the latest row per id rather than matching on any row: a trace is written
     * more than once, and until the parts merge both versions are there to be read. An out-of-order create
     * leaves an earlier row holding a source the later write corrects.
     */
    @Test
    @DisplayName("logging source: the latest version of a trace decides, not whichever version is read first")
    void loggingSourceIdsTakeTheLatestVersionOfATrace() {
        String projectName = randomName("project");
        UUID becameSdk = createTrace(projectName, Source.PLAYGROUND, null);
        UUID becamePlayground = createTrace(projectName, Source.SDK, null);
        UUID projectId = projectIdOf(becameSdk);

        // A second version of each, written after the first and carrying the other source.
        traceResourceClient.batchCreateTraces(List.of(
                traceOf(projectName, Source.SDK, null).toBuilder().id(becameSdk).build(),
                traceOf(projectName, Source.PLAYGROUND, null).toBuilder().id(becamePlayground).build()),
                API_KEY, WORKSPACE_NAME);

        var kept = traceDAO.getLoggingSourceIds(Set.of(projectId), Set.of(becameSdk, becamePlayground))
                .contextWrite(this::workspaceContext)
                .block();

        assertThat(kept)
                .as("the correction is what counts, in both directions")
                .containsExactly(becameSdk);
    }

    @Test
    @DisplayName("logging source: an id set with no project or no ids reads nothing")
    void loggingSourceIdsShortCircuitOnEmptyInput() {
        assertThat(traceDAO.getLoggingSourceIds(Set.of(), Set.of(UUID.randomUUID()))
                .contextWrite(this::workspaceContext).block()).isEmpty();
        assertThat(traceDAO.getLoggingSourceIds(Set.of(UUID.randomUUID()), Set.of())
                .contextWrite(this::workspaceContext).block()).isEmpty();
        assertThat(traceThreadDAO.getLoggingSourceIds(null, null)
                .contextWrite(this::workspaceContext).block()).isEmpty();
    }

    @Test
    @DisplayName("logging source: only threads an SDK logged are returned")
    void threadLoggingSourceIdsKeepSdkThreadsOnly() {
        String projectName = randomName("project");
        String sdkThread = randomName("thread");
        String playgroundThread = randomName("thread");
        createTrace(projectName, Source.SDK, sdkThread);
        createTrace(projectName, Source.PLAYGROUND, playgroundThread);

        UUID projectId = projectIdOf(createTrace(projectName, Source.SDK, null));
        Map<String, UUID> threadModelIds = traceResourceClient
                .getTraceThreads(projectId, null, API_KEY, WORKSPACE_NAME, List.of(), List.of(), Map.of())
                .content().stream()
                .collect(Collectors.toMap(thread -> thread.id(), thread -> thread.threadModelId()));

        var kept = traceThreadDAO.getLoggingSourceIds(Set.of(projectId), Set.copyOf(threadModelIds.values()))
                .contextWrite(this::workspaceContext)
                .block();

        assertThat(kept).containsExactly(threadModelIds.get(sdkThread));
    }

    /**
     * The routing consumer asks the traces search for an automation's filters plus the batch as an id list.
     * A trace outside the batch must not come back even though it matches, and a score filter must still be
     * evaluated: it reads an aggregate keyed on the narrowed trace set rather than the whole project.
     */
    @Test
    @DisplayName("match: the traces search narrowed to a batch applies the filters to the batch only")
    void traceSearchNarrowedToTheBatchAppliesTheFilters() {
        String projectName = randomName("project");
        String scoreName = randomName("score");
        UUID lowInBatch = createTrace(projectName, Source.SDK, null);
        UUID highInBatch = createTrace(projectName, Source.SDK, null);
        UUID lowOutsideBatch = createTrace(projectName, Source.SDK, null);
        score(lowInBatch, scoreName, "0.1");
        score(highInBatch, scoreName, "0.9");
        score(lowOutsideBatch, scoreName, "0.1");
        UUID projectId = projectIdOf(lowInBatch);

        var matched = traceDAO.search(2, TraceSearchCriteria.builder()
                .projectId(projectId)
                .filters(List.of(
                        TraceFilter.builder().field(TraceField.FEEDBACK_SCORES).operator(Operator.LESS_THAN)
                                .key(scoreName).value("0.5").build()))
                .traceIds(Set.of(lowInBatch, highInBatch))
                .exclude(Set.of(Trace.TraceField.INPUT, Trace.TraceField.OUTPUT, Trace.TraceField.METADATA))
                .build())
                .map(Trace::id)
                .contextWrite(this::workspaceContext)
                .collect(Collectors.toSet())
                .block();

        assertThat(matched).containsExactly(lowInBatch);
    }

    /**
     * A guardrails filter reads a join the trace id prefilter does not have, so the narrowed search keeps the
     * filter out of the prefilter and applies it once the batch is selected.
     */
    @Test
    @DisplayName("match: the traces search narrowed to a batch applies a guardrails filter to the batch only")
    void traceSearchNarrowedToTheBatchAppliesAGuardrailsFilter() {
        String projectName = randomName("project");
        UUID failedInBatch = createTrace(projectName, Source.SDK, null);
        UUID passedInBatch = createTrace(projectName, Source.SDK, null);
        UUID failedOutsideBatch = createTrace(projectName, Source.SDK, null);
        guardrail(projectName, failedInBatch, GuardrailResult.FAILED);
        guardrail(projectName, passedInBatch, GuardrailResult.PASSED);
        guardrail(projectName, failedOutsideBatch, GuardrailResult.FAILED);
        UUID projectId = projectIdOf(failedInBatch);

        var matched = traceDAO.search(2, TraceSearchCriteria.builder()
                .projectId(projectId)
                .filters(List.of(TraceFilter.builder().field(TraceField.GUARDRAILS).operator(Operator.EQUAL)
                        .value(GuardrailResult.FAILED.getResult()).build()))
                .traceIds(Set.of(failedInBatch, passedInBatch))
                .exclude(Set.of(Trace.TraceField.INPUT, Trace.TraceField.OUTPUT, Trace.TraceField.METADATA))
                .build())
                .map(Trace::id)
                .contextWrite(this::workspaceContext)
                .collect(Collectors.toSet())
                .block();

        assertThat(matched).containsExactly(failedInBatch);
    }

    @Test
    @DisplayName("match: the threads search narrowed to a batch returns the matching threads' model ids")
    void threadSearchNarrowedToTheBatchReturnsModelIds() {
        String projectName = randomName("project");
        String firstPart = randomName("thread");
        String secondPart = randomName("thread");
        // A thread id is free text: one holding a comma must not match the threads named by its parts.
        String inBatch = firstPart + "," + secondPart;
        createTrace(projectName, Source.SDK, inBatch);
        createTrace(projectName, Source.SDK, inBatch);
        createTrace(projectName, Source.SDK, firstPart);
        createTrace(projectName, Source.SDK, secondPart);
        UUID projectId = projectIdOf(createTrace(projectName, Source.SDK, null));

        // Room for the threads named by the parts, so a split id shows up as extra rows.
        var matched = threadDAO.search(3, TraceSearchCriteria.builder()
                .projectId(projectId)
                .filters(List.of(
                        TraceThreadFilter.builder().field(TraceThreadField.NUMBER_OF_MESSAGES)
                                .operator(Operator.GREATER_THAN_EQUAL).value("1").build()))
                .threadIds(Set.of(inBatch))
                .build())
                .contextWrite(this::workspaceContext)
                .collectList()
                .block();

        assertThat(matched).singleElement().satisfies(thread -> {
            assertThat(thread.id()).isEqualTo(inBatch);
            assertThat(thread.threadModelId()).isNotNull();
        });
    }

    @Test
    @DisplayName("thread lookup: updated traces resolve to their threads, per project")
    void traceIdsResolveToTheirThreads() {
        String projectName = randomName("project");
        String threadId = randomName("thread");
        UUID first = createTrace(projectName, Source.SDK, threadId);
        UUID second = createTrace(projectName, Source.SDK, threadId);
        UUID noThread = createTrace(projectName, Source.SDK, null);
        UUID projectId = projectIdOf(first);

        var threadIds = traceDAO.getThreadIdsByTraceIds(Set.of(first, second, noThread))
                .contextWrite(this::workspaceContext)
                .block();

        assertThat(threadIds).isEqualTo(Map.of(projectId, Set.of(threadId)));
    }

    /**
     * A thread's own tags and its closing are thread changes no trace event carries, so the thread routes on its
     * own event.
     */
    @Test
    @DisplayName("events: tagging a thread, batch tagging it and closing it each announce the thread")
    void taggingAndClosingAThreadAnnounceIt() {
        String projectName = randomName("project");
        String threadId = randomName("thread");
        UUID projectId = projectIdOf(createTrace(projectName, Source.SDK, threadId));
        UUID threadModelId = traceResourceClient.getTraceThread(threadId, projectId, API_KEY, WORKSPACE_NAME)
                .threadModelId();

        traceResourceClient.updateThread(TraceThreadUpdate.builder().tags(Set.of("vip")).build(), threadModelId,
                API_KEY, WORKSPACE_NAME, HttpStatus.SC_NO_CONTENT);
        awaitThreadsUpdated(projectId, threadModelId);

        // The batch update names threads by model id only, so its event carries no project.
        traceResourceClient.batchUpdateThreads(TraceThreadBatchUpdate.builder()
                .ids(Set.of(threadModelId))
                .update(TraceThreadUpdate.builder().tags(Set.of("billing")).build())
                .mergeTags(true)
                .build(), API_KEY, WORKSPACE_NAME);
        awaitThreadsUpdated(null, threadModelId);

        traceResourceClient.closeTraceThread(threadId, projectId, null, API_KEY, WORKSPACE_NAME);
        awaitThreadsUpdated(projectId, threadModelId);
    }

    private void awaitThreadsUpdated(UUID projectId, UUID threadModelId) {
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(threadsUpdated)
                .anySatisfy(event -> {
                    assertThat(event.workspaceId()).isEqualTo(WORKSPACE_ID);
                    assertThat(event.projectId()).isEqualTo(projectId);
                    assertThat(event.threadModelIds()).containsExactly(threadModelId);
                }));
        threadsUpdated.clear();
    }

    @Test
    @DisplayName("span lookup: a span score resolves to the span's trace")
    void spanIdsResolveToTheirTraces() {
        String projectName = randomName("project");
        UUID traceId = createTrace(projectName, Source.SDK, null);
        UUID otherTraceId = createTrace(projectName, Source.SDK, null);
        UUID spanId = createSpan(projectName, traceId);
        UUID otherSpanId = createSpan(projectName, otherTraceId);
        UUID sameTraceSpanId = createSpan(projectName, traceId);

        var traceIds = spanDAO.getTraceIdsBySpanIds(Set.of(spanId, otherSpanId, sameTraceSpanId))
                .contextWrite(this::workspaceContext)
                .block();

        assertThat(traceIds).containsExactlyInAnyOrder(traceId, otherTraceId);
    }

    @Test
    @DisplayName("thread lookup: model ids of existing threads, without creating the missing ones")
    void threadModelIdsAreFoundWithoutCreatingMissingOnes() {
        String projectName = randomName("project");
        String existing = randomName("thread");
        String missing = randomName("thread");
        UUID projectId = projectIdOf(createTrace(projectName, Source.SDK, existing));

        var found = traceThreadService.findThreadModelIds(projectId, Set.of(existing, missing))
                .contextWrite(this::workspaceContext)
                .block();

        assertThat(found).containsOnlyKeys(existing);
        // Looking again proves the first call created nothing for the missing thread.
        assertThat(traceThreadService.findThreadModelIds(projectId, Set.of(missing))
                .contextWrite(this::workspaceContext).block()).isEmpty();
    }

    private void score(UUID traceId, String name, String value) {
        traceResourceClient.feedbackScore(traceId, FeedbackScore.builder()
                .name(name)
                .value(new BigDecimal(value))
                .source(ScoreSource.SDK)
                .build(), WORKSPACE_NAME, API_KEY);
    }

    private void guardrail(String projectName, UUID traceId, GuardrailResult result) {
        guardrailsResourceClient.addBatch(new GuardrailsGenerator()
                .generateGuardrailsForTrace(traceId, createSpan(projectName, traceId), projectName).stream()
                .map(guardrail -> guardrail.toBuilder().result(result).build())
                .toList(), API_KEY, WORKSPACE_NAME);
    }

    private UUID createSpan(String projectName, UUID traceId) {
        return spanResourceClient.createSpan(factory.manufacturePojo(Span.class).toBuilder()
                .id(null)
                .projectName(projectName)
                .projectId(null)
                .traceId(traceId)
                .parentSpanId(null)
                .feedbackScores(null)
                .build(), API_KEY, WORKSPACE_NAME);
    }

    private UUID projectIdOf(UUID traceId) {
        return traceResourceClient.getById(traceId, WORKSPACE_NAME, API_KEY).projectId();
    }

    private UUID createTrace(String projectName, Source source, String threadId) {
        return traceResourceClient.createTrace(traceOf(projectName, source, threadId), API_KEY, WORKSPACE_NAME);
    }

    private Trace traceOf(String projectName, Source source, String threadId) {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName(projectName)
                .projectId(null)
                .threadId(threadId)
                .source(source)
                .startTime(Instant.now())
                .endTime(Instant.now())
                .feedbackScores(null)
                .usage(null)
                .build();
    }

    private reactor.util.context.Context workspaceContext(reactor.util.context.Context context) {
        return context.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID).put(RequestContext.USER_NAME, USER);
    }

    private static String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(20));
    }
}
