package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddParams;
import org.redisson.api.stream.StreamMessageId;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineScorePublisherTest {

    private static final int GLOBAL_MAX_LEN = 10000;
    private static final int GLOBAL_TRIM_LIMIT = 100;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private final ServiceTogglesConfig serviceTogglesConfig = new ServiceTogglesConfig();

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private AutomationRuleEvaluatorService automationRuleEvaluatorService;

    @Mock
    private RStreamReactive<Object, Object> stream;

    @Test
    void shouldUseGlobalStreamAddParams() {
        var config = createConfig();
        var publisher = createPublisher(config);

        var message = podamFactory.manufacturePojo(String.class);
        publisher.enqueueMessage(List.of(message), AutomationRuleEvaluatorType.LLM_AS_JUDGE).block();

        await().untilAsserted(() -> {
            var streamAddParams = captureStreamAddParams();
            assertThat(streamAddParams.getMaxLen()).isEqualTo(GLOBAL_MAX_LEN);
            assertThat(streamAddParams.getLimit()).isEqualTo(GLOBAL_TRIM_LIMIT);
            assertThat(streamAddParams.isTrimStrict()).isFalse();
        });
    }

    @Test
    void shouldUsePerStreamStreamAddParams() {
        var config = createConfig(5000, 500);
        var publisher = createPublisher(config);

        var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
        publisher.enqueueMessage(messages, AutomationRuleEvaluatorType.LLM_AS_JUDGE).block();

        await().untilAsserted(() -> {
            var streamAddParams = captureStreamAddParams();
            assertThat(streamAddParams.getMaxLen()).isEqualTo(5000);
            assertThat(streamAddParams.getLimit()).isEqualTo(500);
            assertThat(streamAddParams.isTrimStrict()).isFalse();
        });
    }

    /**
     * OPIK-8262. {@code enqueueThreadMessage} used to build ONE stream entry carrying the whole thread-id
     * list. {@code BaseRedisSubscriber} acks and removes per entry, so N thread ids under one entry share a
     * single retry verdict — and once {@code ChatCompletionService.scoreTrace} classifies provider errors by
     * status (same change), a permanent 400 and a transient 429 can land under one entry and whichever the
     * consumer surfaces mis-serves the other. Publishing one entry per thread id makes failure granularity
     * match ack granularity, so a retry replays only the thread that failed.
     */
    @Nested
    @DisplayName("One stream entry per thread id")
    class ThreadMessageFanOutTests {

        @ParameterizedTest(name = "llm-as-judge: {0} thread ids -> {0} entries, one id each")
        @ValueSource(ints = {1, 2, 5})
        void shouldPublishOneLlmAsJudgeEntryPerThreadId(int threadCount) {
            var config = createConfig(AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE);
            var publisher = createPublisher(config);
            var threadIds = threadIds(threadCount);
            var rule = AutomationRuleEvaluatorTraceThreadLlmAsJudge.builder()
                    .id(UUID.randomUUID())
                    .name(podamFactory.manufacturePojo(String.class))
                    .code(podamFactory.manufacturePojo(TraceThreadLlmAsJudgeCode.class))
                    .build();

            publisher.enqueueThreadMessage(threadIds, rule, UUID.randomUUID(),
                    podamFactory.manufacturePojo(String.class), podamFactory.manufacturePojo(String.class)).block();

            var published = capturePayloads(TraceThreadToScoreLlmAsJudge.class);
            assertThat(published)
                    .as("one stream entry per thread id, so the subscriber's per-entry ack retires exactly one"
                            + " thread's work")
                    .hasSize(threadCount);
            assertThat(published)
                    .allSatisfy(message -> assertThat(message.threadIds())
                            .as("each entry carries exactly one thread id — the whole point of the split")
                            .hasSize(1));
            assertThat(published.stream().map(message -> message.threadIds().getFirst()).toList())
                    .as("every requested thread id is still enqueued, none lost in the split")
                    .containsExactlyInAnyOrderElementsOf(threadIds);
        }

        @ParameterizedTest(name = "python: {0} thread ids -> {0} entries, one id each")
        @ValueSource(ints = {1, 2, 5})
        void shouldPublishOnePythonEntryPerThreadId(int threadCount) {
            serviceTogglesConfig.setTraceThreadPythonEvaluatorEnabled(true);
            var config = createConfig(AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON);
            var publisher = createPublisher(config);
            var threadIds = threadIds(threadCount);
            var rule = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.builder()
                    .id(UUID.randomUUID())
                    .name(podamFactory.manufacturePojo(String.class))
                    .code(podamFactory.manufacturePojo(TraceThreadUserDefinedMetricPythonCode.class))
                    .build();

            publisher.enqueueThreadMessage(threadIds, rule, UUID.randomUUID(),
                    podamFactory.manufacturePojo(String.class), podamFactory.manufacturePojo(String.class)).block();

            var published = capturePayloads(TraceThreadToScoreUserDefinedMetricPython.class);
            assertThat(published).hasSize(threadCount);
            assertThat(published).allSatisfy(message -> assertThat(message.threadIds()).hasSize(1));
            assertThat(published.stream().map(message -> message.threadIds().getFirst()).toList())
                    .containsExactlyInAnyOrderElementsOf(threadIds);
        }

        /**
         * An empty list used to publish one entry whose {@code threadIds} violated the record's
         * {@code @NotEmpty} — a message no consumer could do anything with. Zero ids now means zero entries.
         */
        @Test
        @DisplayName("An empty thread-id list publishes nothing at all")
        void shouldPublishNothingForAnEmptyThreadIdList() {
            var config = createConfig(AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE);
            var publisher = createPublisherWithoutStreamStubs(config);
            var rule = AutomationRuleEvaluatorTraceThreadLlmAsJudge.builder()
                    .id(UUID.randomUUID())
                    .name(podamFactory.manufacturePojo(String.class))
                    .code(podamFactory.manufacturePojo(TraceThreadLlmAsJudgeCode.class))
                    .build();

            publisher.enqueueThreadMessage(List.of(), rule, UUID.randomUUID(),
                    podamFactory.manufacturePojo(String.class), podamFactory.manufacturePojo(String.class)).block();

            verify(stream, never()).add(any());
        }

        private List<String> threadIds(int count) {
            return IntStream.range(0, count)
                    .mapToObj(index -> "thread-%d-%s".formatted(index,
                            RandomStringUtils.secure().nextAlphanumeric(16)))
                    .toList();
        }

        private <T> List<T> capturePayloads(Class<T> messageType) {
            ArgumentCaptor<StreamAddParams<Object, Object>> captor = ArgumentCaptor.forClass(StreamAddParams.class);
            verify(stream, atLeastOnce()).add(captor.capture());
            return captor.getAllValues().stream()
                    .map(params -> params.getEntries().get(OnlineScoringConfig.PAYLOAD_FIELD))
                    .map(messageType::cast)
                    .toList();
        }
    }

    private OnlineScorePublisher createPublisher(OnlineScoringConfig onlineScoringConfig) {
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(System.currentTimeMillis(), 0)));
        return createPublisherWithoutStreamStubs(onlineScoringConfig);
    }

    /** For the cases that assert nothing is ever added — strict stubbing rejects an unused {@code add} stub. */
    private OnlineScorePublisher createPublisherWithoutStreamStubs(OnlineScoringConfig onlineScoringConfig) {
        when(redisClient.getStream(anyString(), any())).thenReturn(stream);
        return new OnlineScorePublisherImpl(
                onlineScoringConfig, serviceTogglesConfig, redisClient, automationRuleEvaluatorService);
    }

    private StreamAddParams<Object, Object> captureStreamAddParams() {
        ArgumentCaptor<StreamAddParams<Object, Object>> captor = ArgumentCaptor.forClass(StreamAddParams.class);
        verify(stream, atLeastOnce()).add(captor.capture());
        return captor.getValue();
    }

    private OnlineScoringConfig createConfig() {
        return createConfig(null, null);
    }

    private OnlineScoringConfig createConfig(Integer perStreamMaxLen, Integer perStreamTrimLimit) {
        return createConfig(AutomationRuleEvaluatorType.LLM_AS_JUDGE, perStreamMaxLen, perStreamTrimLimit);
    }

    private OnlineScoringConfig createConfig(AutomationRuleEvaluatorType type) {
        return createConfig(type, null, null);
    }

    private OnlineScoringConfig createConfig(AutomationRuleEvaluatorType type, Integer perStreamMaxLen,
            Integer perStreamTrimLimit) {
        var streamConfiguration = OnlineScoringConfig.StreamConfiguration.builder()
                .scorer(type.getType())
                .streamName("test-stream-%s".formatted(
                        RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase()))
                .codec(RedisStreamCodec.JAVA.getName())
                .streamMaxLen(perStreamMaxLen)
                .streamTrimLimit(perStreamTrimLimit)
                .build();
        return OnlineScoringConfig.builder()
                .streamMaxLen(GLOBAL_MAX_LEN)
                .streamTrimLimit(GLOBAL_TRIM_LIMIT)
                .streams(List.of(streamConfiguration))
                .build();
    }
}
