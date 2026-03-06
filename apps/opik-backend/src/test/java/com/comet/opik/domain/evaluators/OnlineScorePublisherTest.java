package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddParams;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
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
        publisher.enqueueMessage(List.of(message), AutomationRuleEvaluatorType.LLM_AS_JUDGE);

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
        publisher.enqueueMessage(messages, AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        await().untilAsserted(() -> {
            var streamAddParams = captureStreamAddParams();
            assertThat(streamAddParams.getMaxLen()).isEqualTo(5000);
            assertThat(streamAddParams.getLimit()).isEqualTo(500);
            assertThat(streamAddParams.isTrimStrict()).isFalse();
        });
    }

    private OnlineScorePublisher createPublisher(OnlineScoringConfig onlineScoringConfig) {
        when(redisClient.getStream(anyString(), any())).thenReturn(stream);
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(System.currentTimeMillis(), 0)));
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
