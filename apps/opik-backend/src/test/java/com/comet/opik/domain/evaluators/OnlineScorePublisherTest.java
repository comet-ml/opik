package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.infrastructure.JacksonConfig;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.core.StreamReadConstraints;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineScorePublisherTest {

    private static final int GLOBAL_MAX_LEN = 10000;
    private static final int GLOBAL_TRIM_LIMIT = 100;

    // Smallest document limit a valid JacksonConfig can carry: maxStringLength has a 1MB floor and
    // maxDocumentLength must be >= maxStringLength (JacksonConfig.isMaxDocumentLengthValid). Keeping the
    // test at that floor means the config under test is one production could actually run.
    private static final long ONE_MB = 1_048_576L;

    // A String message serializes as a quoted JSON scalar, so a payload of N ASCII characters measures
    // N + 2 bytes - the arithmetic the boundary cases below depend on.
    private static final int JSON_QUOTES = 2;

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
     * The guard is opt-in, so the shipped default must publish a payload of any size. Same message as the
     * drop case below, only the toggle differs.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 1000, (int) ONE_MB})
    void shouldPublishOversizedMessagesWhenDropDisabled(int payloadChars) {
        var publisher = createPublisher(createConfig(false), jacksonConfig(ONE_MB));

        publisher.enqueueMessage(List.of("a".repeat(payloadChars)), AutomationRuleEvaluatorType.LLM_AS_JUDGE)
                .block();

        verify(stream, times(1)).add(any());
    }

    /**
     * With the toggle on, the effective ceiling is min(maxDocumentLength, DEFAULT_MAX_STRING_LEN) - here the
     * configured 1MB, the lower of the two. Covers below, exactly at, and one byte over the limit.
     */
    @ParameterizedTest
    @CsvSource({
            "1024, true",
            "1048574, true", // 1048574 + 2 quotes == the 1MB limit exactly
            "1048575, false", // one byte over
            "2097152, false",
    })
    void shouldDropOnlyOversizedMessagesWhenDropEnabled(int payloadChars, boolean expectPublished) {
        var publisher = createPublisher(createConfig(true), jacksonConfig(ONE_MB));
        var message = "a".repeat(payloadChars);

        assertThat(message.length() + JSON_QUOTES <= ONE_MB).isEqualTo(expectPublished);

        assertThat(publisher.exceedsPayloadLimit(message)).isEqualTo(!expectPublished);

        publisher.enqueueMessage(List.of(message), AutomationRuleEvaluatorType.LLM_AS_JUDGE).block();

        verify(stream, expectPublished ? times(1) : never()).add(any());
    }

    /**
     * When maxDocumentLength is higher than Jackson's default maxStringLength - the stock configuration, and
     * unlimited (<= 0) too - min() must pick the legacy default, not the configured limit. That is the
     * rolling-upgrade protection: a consumer still running a build without the codec-init fix decodes at
     * DEFAULT_MAX_STRING_LEN, and a message above it wedges the stream on that pod.
     */
    @ParameterizedTest
    @ValueSource(longs = {0L, 536_870_912L})
    void shouldHoldTheLegacyCeilingWhenTheConfiguredLimitIsHigher(long maxDocumentLength) {
        var publisher = createPublisher(createConfig(true), jacksonConfig(maxDocumentLength));

        var atLimit = "a".repeat(StreamReadConstraints.DEFAULT_MAX_STRING_LEN - JSON_QUOTES);
        assertThat(publisher.exceedsPayloadLimit(atLimit)).isFalse();

        var overLimit = "a".repeat(StreamReadConstraints.DEFAULT_MAX_STRING_LEN - JSON_QUOTES + 1);
        assertThat(publisher.exceedsPayloadLimit(overLimit)).isTrue();
    }

    /**
     * The samplers compensate for a dropped message off exceedsPayloadLimit - a per-experiment assertion
     * counter, a sampling decision metric. With the guard off nothing is dropped, so it must never claim a
     * message would be, whatever the configured limit.
     */
    @ParameterizedTest
    @ValueSource(longs = {0L, ONE_MB, 536_870_912L})
    void shouldNeverReportExceedingWhenDropDisabled(long maxDocumentLength) {
        var publisher = createPublisher(createConfig(false), jacksonConfig(maxDocumentLength));

        assertThat(publisher.exceedsPayloadLimit("a".repeat((int) ONE_MB * 2))).isFalse();
    }

    private JacksonConfig jacksonConfig(long maxDocumentLength) {
        var jacksonConfig = new JacksonConfig();
        jacksonConfig.setMaxStringLength((int) ONE_MB);
        jacksonConfig.setMaxDocumentLength(maxDocumentLength);
        return jacksonConfig;
    }

    private OnlineScorePublisher createPublisher(OnlineScoringConfig onlineScoringConfig,
            JacksonConfig jacksonConfig) {
        // lenient: the drop cases never reach stream.add, so the stub goes unused there.
        lenient().when(redisClient.getStream(anyString(), any())).thenReturn(stream);
        lenient().when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(System.currentTimeMillis(), 0)));
        return new OnlineScorePublisherImpl(
                onlineScoringConfig, serviceTogglesConfig, jacksonConfig, redisClient,
                automationRuleEvaluatorService);
    }

    private OnlineScorePublisher createPublisher(OnlineScoringConfig onlineScoringConfig) {
        when(redisClient.getStream(anyString(), any())).thenReturn(stream);
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(System.currentTimeMillis(), 0)));
        return new OnlineScorePublisherImpl(
                onlineScoringConfig, serviceTogglesConfig, new JacksonConfig(), redisClient,
                automationRuleEvaluatorService);
    }

    private StreamAddParams<Object, Object> captureStreamAddParams() {
        ArgumentCaptor<StreamAddParams<Object, Object>> captor = ArgumentCaptor.forClass(StreamAddParams.class);
        verify(stream, atLeastOnce()).add(captor.capture());
        return captor.getValue();
    }

    private OnlineScoringConfig createConfig() {
        return createConfig(null, null);
    }

    private OnlineScoringConfig createConfig(boolean dropOversizedPayloads) {
        return createConfig(null, null).toBuilder().dropOversizedPayloads(dropOversizedPayloads).build();
    }

    private OnlineScoringConfig createConfig(Integer perStreamMaxLen, Integer perStreamTrimLimit) {
        var streamConfiguration = OnlineScoringConfig.StreamConfiguration.builder()
                .scorer(AutomationRuleEvaluatorType.LLM_AS_JUDGE.getType())
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
