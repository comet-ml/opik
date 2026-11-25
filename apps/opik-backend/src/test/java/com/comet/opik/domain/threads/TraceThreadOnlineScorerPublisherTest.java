package com.comet.opik.domain.threads;

import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.auth.FixedOrganizationResolver;
import com.comet.opik.infrastructure.auth.OrganizationResolver;
import com.comet.opik.infrastructure.auth.RequestContext;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TraceThreadOnlineScorerPublisher: the organization its thread judges carry")
class TraceThreadOnlineScorerPublisherTest {

    private final UUID projectId = UUID.randomUUID();
    private final String workspaceId = UUID.randomUUID().toString();
    private final String userName = RandomStringUtils.secure().nextAlphanumeric(10);
    private final String organizationId = UUID.randomUUID().toString();

    @Mock
    private OnlineScorePublisher onlineScorePublisher;

    @Test
    @DisplayName("Every rule sampled in one close shares one lookup")
    void sampledRulesShareOneLookup() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var ruleIds = Stream.generate(UUID::randomUUID).limit(3).toList();
        var thread = thread(ruleIds.stream().collect(Collectors.toMap(Function.identity(), ruleId -> true)));
        stubEnqueue();

        publish(resolver, thread);

        ruleIds.forEach(ruleId -> verify(onlineScorePublisher).enqueueThreadMessage(List.of(thread.threadId()),
                ruleId, projectId, workspaceId, userName, organizationId));
        assertThat(resolver.lookups()).isOne();
    }

    @Test
    @DisplayName("Enqueues without an organization when none is known")
    void enqueuesWithoutAnOrganizationWhenNoneIsKnown() {
        var resolver = FixedOrganizationResolver.none();
        var ruleId = UUID.randomUUID();
        var thread = thread(Map.of(ruleId, true));
        stubEnqueue();

        publish(resolver, thread);

        verify(onlineScorePublisher).enqueueThreadMessage(List.of(thread.threadId()), ruleId, projectId, workspaceId,
                userName, null);
        assertThat(resolver.lookups()).isOne();
    }

    @Test
    @DisplayName("Looks nothing up when no thread was sampled")
    void noLookupWhenNothingWasSampled() {
        var resolver = FixedOrganizationResolver.of(organizationId);

        publish(resolver, thread(Map.of(UUID.randomUUID(), false)));

        verifyNoInteractions(onlineScorePublisher);
        assertThat(resolver.lookups()).isZero();
    }

    private void publish(OrganizationResolver resolver, TraceThreadModel thread) {
        new TraceThreadOnlineScorerPublisher(onlineScorePublisher, resolver)
                .publish(projectId, List.of(thread))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();
    }

    private void stubEnqueue() {
        when(onlineScorePublisher.enqueueThreadMessage(anyList(), any(UUID.class), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    private TraceThreadModel thread(Map<UUID, Boolean> sampling) {
        return TraceThreadModel.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .threadId(UUID.randomUUID().toString())
                .sampling(sampling)
                .build();
    }
}
