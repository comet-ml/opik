package com.comet.opik.domain;

import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExperimentItemPublisher: the organization an experiment item carries")
class ExperimentItemPublisherOrganizationTest {

    private static final PodamFactory PODAM_FACTORY = PodamFactoryUtils.newPodamFactory();

    private final ExperimentExecutionConfig config = new ExperimentExecutionConfig();
    private final String organizationId = UUID.randomUUID().toString();

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private RStreamReactive<Object, Object> stream;

    @Mock
    private TestSuiteAssertionCounterService testSuiteAssertionCounterService;

    @Mock
    private ExperimentCancellationService cancellationService;

    @Mock
    private ExperimentItemLedger itemLedger;

    @BeforeEach
    void setUp() {
        config.setStreamName(RandomStringUtils.secure().nextAlphanumeric(10));
        when(redisClient.getStream(anyString(), any())).thenReturn(stream);
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(System.currentTimeMillis(), 0)));
        when(cancellationService.recordQueued(anyString(), anyMap())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Stamps the run's organization onto items that carry none")
    void stampsTheRunsOrganization() {
        var messages = List.of(message(null), message(null));

        publisher().publish(UUID.randomUUID(), messages, organizationId).block();

        assertThat(published()).containsExactlyInAnyOrderElementsOf(messages.stream()
                .map(message -> message.toBuilder().organizationId(organizationId).build())
                .toList());
    }

    @Test
    @DisplayName("Stamps the run's organization over a blank one")
    void stampsOverABlankOrganization() {
        var message = message("   ");

        publisher().publish(UUID.randomUUID(), List.of(message), organizationId).block();

        assertThat(published()).containsExactly(message.toBuilder().organizationId(organizationId).build());
    }

    @Test
    @DisplayName("Keeps the organization an item already carries")
    void keepsTheItemsOwnOrganization() {
        var message = message(UUID.randomUUID().toString());

        publisher().publish(UUID.randomUUID(), List.of(message), organizationId).block();

        assertThat(published()).containsExactly(message);
    }

    @Test
    @DisplayName("Publishes without an organization when none is known")
    void publishesWithoutAnOrganization() {
        var message = message(null);

        publisher().publish(UUID.randomUUID(), List.of(message), null).block();

        assertThat(published()).containsExactly(message);
    }

    private ExperimentItemPublisher publisher() {
        return new ExperimentItemPublisher(redisClient, config, testSuiteAssertionCounterService,
                cancellationService, itemLedger);
    }

    private List<ExperimentItemToProcess> published() {
        ArgumentCaptor<StreamAddParams<Object, Object>> captor = ArgumentCaptor.forClass(StreamAddParams.class);
        verify(stream, atLeastOnce()).add(captor.capture());
        return captor.getAllValues().stream()
                .map(params -> params.getEntries().get(ExperimentExecutionConfig.PAYLOAD_FIELD))
                .map(ExperimentItemToProcess.class::cast)
                .toList();
    }

    private static ExperimentItemToProcess message(String organizationId) {
        return PODAM_FACTORY.manufacturePojo(ExperimentItemToProcess.class).toBuilder()
                .organizationId(organizationId)
                .build();
    }
}
