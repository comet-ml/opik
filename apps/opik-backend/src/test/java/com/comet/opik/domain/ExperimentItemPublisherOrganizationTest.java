package com.comet.opik.domain;

import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.auth.FixedOrganizationResolver;
import com.comet.opik.infrastructure.auth.OrganizationResolver;
import com.comet.opik.podam.PodamFactoryUtils;
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
        config.setStreamName("experiment-items");
        when(redisClient.getStream(anyString(), any())).thenReturn(stream);
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(System.currentTimeMillis(), 0)));
        when(cancellationService.recordQueued(anyString(), anyMap())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Stamps the organization of the request that started the run")
    void stampsTheRunsOrganization() {
        publisher(FixedOrganizationResolver.of("organization-1"))
                .publish(UUID.randomUUID(), List.of(message(null), message(null))).block();

        assertThat(published()).extracting(ExperimentItemToProcess::organizationId)
                .containsExactly("organization-1", "organization-1");
    }

    @Test
    @DisplayName("Stamps the run's organization over a blank one")
    void stampsOverABlankOrganization() {
        publisher(FixedOrganizationResolver.of("organization-1"))
                .publish(UUID.randomUUID(), List.of(message("   "))).block();

        assertThat(published()).extracting(ExperimentItemToProcess::organizationId)
                .containsExactly("organization-1");
    }

    @Test
    @DisplayName("Keeps the organization an item already carries")
    void keepsTheItemsOwnOrganization() {
        publisher(FixedOrganizationResolver.of("organization-1"))
                .publish(UUID.randomUUID(), List.of(message("organization-2"))).block();

        assertThat(published()).extracting(ExperimentItemToProcess::organizationId)
                .containsExactly("organization-2");
    }

    @Test
    @DisplayName("Publishes without an organization when none is known or it can't be resolved")
    void publishesWithoutAnOrganization() {
        publisher(FixedOrganizationResolver.NONE).publish(UUID.randomUUID(), List.of(message(null))).block();
        publisher(new FixedOrganizationResolver(Mono.error(new IllegalStateException("Comet down"))))
                .publish(UUID.randomUUID(), List.of(message(null))).block();

        assertThat(published()).extracting(ExperimentItemToProcess::organizationId)
                .containsExactly(null, null);
    }

    private ExperimentItemPublisher publisher(OrganizationResolver organizationResolver) {
        return new ExperimentItemPublisher(redisClient, config, testSuiteAssertionCounterService,
                cancellationService, itemLedger, organizationResolver);
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
