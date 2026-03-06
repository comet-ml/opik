package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.db.IdGeneratorImpl;
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
import reactor.test.StepVerifier;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookPublisherTest {

    private static final int STREAM_MAX_LEN = 10000;
    private static final int STREAM_TRIM_LIMIT = 100;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private final WebhookConfig webhookConfig = WebhookConfig.builder()
            .enabled(true)
            .streamName("test-webhook-stream-%s".formatted(
                    RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase()))
            .streamMaxLen(STREAM_MAX_LEN)
            .streamTrimLimit(STREAM_TRIM_LIMIT)
            .build();

    private final IdGeneratorImpl idGenerator = new IdGeneratorImpl();

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private RStreamReactive<Object, Object> stream;

    @Test
    void shouldUseConfiguredStreamAddParams() {
        when(redisClient.getStream(anyString(), any())).thenReturn(stream);
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(System.currentTimeMillis(), 0)));
        var publisher = new WebhookPublisher(redisClient, webhookConfig, idGenerator);

        var alert = podamFactory.manufacturePojo(Alert.class);
        var workspaceName = "workspace-%s".formatted(
                RandomStringUtils.secure().nextAlphanumeric(32).toLowerCase());
        var payload = "test-payload-%s".formatted(
                RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase());
        StepVerifier.create(publisher.publishWebhookEvent(
                AlertEventType.TRACE_ERRORS, alert, UUID.randomUUID().toString(), workspaceName, payload, 3))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<StreamAddParams<Object, Object>> captor = ArgumentCaptor.forClass(StreamAddParams.class);
        verify(stream, atLeastOnce()).add(captor.capture());
        var streamAddParams = captor.getValue();
        assertThat(streamAddParams.getMaxLen()).isEqualTo(STREAM_MAX_LEN);
        assertThat(streamAddParams.getLimit()).isEqualTo(STREAM_TRIM_LIMIT);
        assertThat(streamAddParams.isTrimStrict()).isFalse();
    }
}
