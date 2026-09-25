package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.threads.TraceThreadDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ClickHouse reads the annotation queue routing consumer makes, against a real database: the
 * effective scores a condition is evaluated against, and which of a batch's entities an SDK logged.
 *
 * <p>Both are deduplication queries over {@code ReplacingMergeTree} tables, where a stale row survives
 * until the parts merge — which a test can reproduce because nothing here merges in between. Neither read
 * has a REST surface of its own, so the DAOs are driven directly with data written through the public API.
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
    private FeedbackScoreDAO feedbackScoreDAO;
    private TraceDAO traceDAO;
    private TraceThreadDAO traceThreadDAO;

    @BeforeAll
    void setUpAll(ClientSupport client, FeedbackScoreDAO feedbackScoreDAO, TraceDAO traceDAO,
            TraceThreadDAO traceThreadDAO) {
        this.feedbackScoreDAO = feedbackScoreDAO;
        this.traceDAO = traceDAO;
        this.traceThreadDAO = traceThreadDAO;

        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);

        traceResourceClient = new TraceResourceClient(client, TestUtils.getBaseUrl(client));
    }

    @AfterAll
    void tearDownAll() {
        setup.wireMock.server().stop();
    }

    @Test
    @DisplayName("effective scores: the latest value of a name wins, and every name comes back with its project")
    void effectiveScoresTakeTheLatestValuePerName() {
        String projectName = randomName("project");
        UUID traceId = createTrace(projectName, Source.SDK, null);
        String rescored = randomName("relevance");
        String other = randomName("tone");

        score(projectName, traceId, rescored, 0.2);
        score(projectName, traceId, other, 0.9);
        // The same score again: a second row for the same replacing key, unmerged until ClickHouse says so.
        score(projectName, traceId, rescored, 0.7);

        var scores = effectiveScores(EntityType.TRACE, Set.of(traceId));

        assertThat(scores)
                .as("a re-scored name is worth its latest value, not the average of its versions")
                .containsOnlyKeys(traceId);
        assertThat(scores.get(traceId).scores())
                .containsOnlyKeys(rescored, other)
                .satisfies(values -> {
                    assertThat(values.get(rescored)).isEqualByComparingTo(BigDecimal.valueOf(0.7));
                    assertThat(values.get(other)).isEqualByComparingTo(BigDecimal.valueOf(0.9));
                });
        assertThat(scores.get(traceId).projectId()).isNotNull();
    }

    @Test
    @DisplayName("effective scores: an entity with no scores is absent rather than empty")
    void effectiveScoresSkipUnscoredEntities() {
        String projectName = randomName("project");
        UUID scored = createTrace(projectName, Source.SDK, null);
        UUID unscored = createTrace(projectName, Source.SDK, null);
        score(projectName, scored, randomName("relevance"), 0.5);

        assertThat(effectiveScores(EntityType.TRACE, Set.of(scored, unscored))).containsOnlyKeys(scored);
    }

    @Test
    @DisplayName("effective scores: entities of one batch come back together, each under its own id")
    void effectiveScoresCoverTheWholeBatch() {
        String projectName = randomName("project");
        String name = randomName("relevance");
        var traceIds = List.of(createTrace(projectName, Source.SDK, null), createTrace(projectName, Source.SDK, null),
                createTrace(projectName, Source.SDK, null));
        traceIds.forEach(traceId -> score(projectName, traceId, name, 0.4));

        assertThat(effectiveScores(EntityType.TRACE, Set.copyOf(traceIds)))
                .containsOnlyKeys(traceIds.toArray(UUID[]::new));
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

    private Map<UUID, EntityFeedbackScores> effectiveScores(EntityType entityType, Set<UUID> entityIds) {
        return feedbackScoreDAO.getEffectiveScores(entityType, entityIds)
                .contextWrite(this::workspaceContext)
                .collect(Collectors.groupingBy(EffectiveFeedbackScore::entityId))
                .map(rows -> rows.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                entry -> EntityFeedbackScores.builder()
                                        .entityId(entry.getKey())
                                        .projectId(entry.getValue().getFirst().projectId())
                                        .scores(entry.getValue().stream()
                                                .collect(Collectors.toUnmodifiableMap(EffectiveFeedbackScore::name,
                                                        EffectiveFeedbackScore::value)))
                                        .build())))
                .block();
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

    private void score(String projectName, UUID traceId, String name, double value) {
        var item = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                .id(traceId)
                .projectName(projectName)
                .projectId(null)
                .name(name)
                .value(BigDecimal.valueOf(value))
                .categoryName(null)
                .source(ScoreSource.SDK)
                .build();

        traceResourceClient.feedbackScores(List.of(item), API_KEY, WORKSPACE_NAME);
    }

    private reactor.util.context.Context workspaceContext(reactor.util.context.Context context) {
        return context.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID).put(RequestContext.USER_NAME, USER);
    }

    private static String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(20));
    }
}
