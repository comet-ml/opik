package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.Redisson;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPIK-8262, cross-layer half. The unit tests assert what the publisher is asked to write; this asserts what
 * actually lands on a real Redis stream and survives the shipped codec — one entry per thread id, each decodable
 * with its own id intact.
 *
 * <p>Covers the migration republish too: {@code OnlineScoringBaseScorer.migrateOrScoreThreadIds} rewrites a legacy
 * multi-id entry by handing the same {@code enqueueMessage} a list of single-id copies, which is the second case
 * below. It deliberately stops at the stream rather than driving the scorer end to end — the scorer's own branching
 * is unit-tested, and standing up the full scoring stack (ClickHouse traces, an LLM provider) to re-observe a Redis
 * write would test the harness more than the change.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnlineScorePublisherIntegrationTest {

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final ServiceTogglesConfig serviceTogglesConfig = new ServiceTogglesConfig();

    @Mock
    private AutomationRuleEvaluatorService automationRuleEvaluatorService;

    private RedissonReactiveClient redissonClient;
    private OnlineScoringConfig config;
    private String streamName;
    private String pythonStreamName;

    @BeforeAll
    void setUpAll() {
        redis.start();
        var redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(redis.getRedisURI()).setDatabase(0);
        redissonClient = Redisson.create(redissonConfig).reactive();

        streamName = randomStreamName();
        pythonStreamName = randomStreamName();
        // The Python evaluator is behind a toggle; without it the publisher skips the enqueue entirely.
        serviceTogglesConfig.setTraceThreadPythonEvaluatorEnabled(true);
        config = OnlineScoringConfig.builder()
                .streamMaxLen(10_000)
                .streamTrimLimit(100)
                .streams(List.of(
                        OnlineScoringConfig.StreamConfiguration.builder()
                                .scorer(AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE.getType())
                                .streamName(streamName)
                                .codec(RedisStreamCodec.JAVA.getName())
                                .build(),
                        OnlineScoringConfig.StreamConfiguration.builder()
                                .scorer(AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON.getType())
                                .streamName(pythonStreamName)
                                .codec(RedisStreamCodec.JAVA.getName())
                                .build()))
                .build();
    }

    @AfterAll
    void tearDownAll() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        redis.stop();
    }

    @Test
    @DisplayName("A batch enqueue lands one decodable entry per thread id on the stream")
    void enqueueThreadMessageWritesOneEntryPerThreadId() {
        var publisher = newPublisher();
        var threadIds = List.of("thread-a-" + suffix(), "thread-b-" + suffix(), "thread-c-" + suffix());
        var rule = AutomationRuleEvaluatorTraceThreadLlmAsJudge.builder()
                .id(UUID.randomUUID())
                .name(podamFactory.manufacturePojo(String.class))
                .code(podamFactory.manufacturePojo(TraceThreadLlmAsJudgeCode.class))
                .build();

        var projectId = UUID.randomUUID();

        publisher.enqueueThreadMessage(threadIds, rule, projectId, "workspace", "user").block();

        // Compared whole, against independently built expectations: asserting only the thread ids would pass
        // while the codec silently dropped the evaluator code, the user, or the project. Plain element
        // equality is enough -- every type in the payload is a record, so generated equals covers each
        // component and picks up any added later.
        var expected = threadIds.stream()
                .map(threadId -> TraceThreadToScoreLlmAsJudge.builder()
                        .threadIds(List.of(threadId))
                        .ruleId(rule.getId())
                        .projectId(projectId)
                        .workspaceId("workspace")
                        .userName("user")
                        .code(rule.getCode())
                        .build())
                .toList();

        var written = readStream();
        assertThat(written)
                .as("the subscriber acks per entry, so each thread id needs its own entry to be retried alone")
                .hasSize(threadIds.size());
        assertThat(written).containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * The Python payload's mirror of the case above. Without it, the Python side is only ever asserted on the
     * arguments handed to the publisher, before serialization — so a codec regression losing
     * {@code code.metric} would leave the evaluator with nothing to run and no test would notice. This PR
     * changes the fan-out for both payload types, so both need the same end-to-end check.
     */
    @Test
    @DisplayName("A Python batch enqueue lands one decodable entry per thread id on its own stream")
    void enqueueThreadMessageWritesOnePythonEntryPerThreadId() {
        var publisher = newPublisher();
        var threadIds = List.of("thread-a-" + suffix(), "thread-b-" + suffix(), "thread-c-" + suffix());
        var projectId = UUID.randomUUID();
        var rule = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.builder()
                .id(UUID.randomUUID())
                .name(podamFactory.manufacturePojo(String.class))
                .code(podamFactory.manufacturePojo(TraceThreadUserDefinedMetricPythonCode.class))
                .build();

        publisher.enqueueThreadMessage(threadIds, rule, projectId, "workspace", "user").block();

        var expected = threadIds.stream()
                .map(threadId -> TraceThreadToScoreUserDefinedMetricPython.builder()
                        .threadIds(List.of(threadId))
                        .ruleId(rule.getId())
                        .projectId(projectId)
                        .workspaceId("workspace")
                        .userName("user")
                        .code(rule.getCode())
                        .build())
                .toList();

        List<TraceThreadToScoreUserDefinedMetricPython> written = readStream(pythonStreamName);
        assertThat(written)
                .as("the Python payload needs the same per-entry granularity as the LLM one")
                .hasSize(threadIds.size());
        assertThat(written).containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * The shape the migration shim produces: single-id copies of one legacy entry, handed to the same
     * {@code enqueueMessage} the scorer calls. Asserts they survive the codec with their ids and rule intact.
     */
    @Test
    @DisplayName("A migration republish lands each single-id copy intact")
    void migrationRepublishWritesEachSingleIdCopy() {
        var publisher = newPublisher();
        var legacy = podamFactory.manufacturePojo(TraceThreadToScoreLlmAsJudge.class).toBuilder()
                .threadIds(List.of("legacy-a-" + suffix(), "legacy-b-" + suffix()))
                .code(podamFactory.manufacturePojo(TraceThreadLlmAsJudgeCode.class))
                .build();
        var copies = legacy.threadIds().stream()
                .map(threadId -> legacy.toBuilder().threadIds(List.of(threadId)).build())
                .toList();

        publisher.enqueueMessage(copies, AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE).block();

        var written = readStream();
        assertThat(written).hasSize(2);
        // Whole-object comparison against the copies actually handed to the publisher. A migration that loses
        // the evaluator code would still score, silently, against a null definition -- ids alone cannot see it.
        assertThat(written)
                .as("a migrated entry must round-trip every field, not just its thread id")
                .containsExactlyInAnyOrderElementsOf(copies);
    }

    private OnlineScorePublisher newPublisher() {
        redissonClient.getStream(streamName, RedisStreamCodec.JAVA.getCodec()).delete().block();
        redissonClient.getStream(pythonStreamName, RedisStreamCodec.JAVA.getCodec()).delete().block();
        return new OnlineScorePublisherImpl(config, serviceTogglesConfig, redissonClient,
                automationRuleEvaluatorService);
    }

    private List<TraceThreadToScoreLlmAsJudge> readStream() {
        return readStream(streamName);
    }

    private <T> List<T> readStream(String name) {
        RStreamReactive<String, T> stream = redissonClient.getStream(name, RedisStreamCodec.JAVA.getCodec());
        return stream.range(StreamMessageId.MIN, StreamMessageId.MAX).block()
                .values().stream()
                .map(entry -> entry.get(OnlineScoringConfig.PAYLOAD_FIELD))
                .toList();
    }

    private static String randomStreamName() {
        return "test-stream-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase());
    }

    private static String suffix() {
        return RandomStringUtils.secure().nextAlphanumeric(16);
    }
}
