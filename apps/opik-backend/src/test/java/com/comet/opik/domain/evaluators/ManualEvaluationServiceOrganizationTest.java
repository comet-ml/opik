package com.comet.opik.domain.evaluators;

import com.comet.opik.api.ManualEvaluationEntityType;
import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.FixedOrganizationResolver;
import com.comet.opik.infrastructure.auth.OrganizationResolver;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManualEvaluationService: the organization its judge messages carry")
class ManualEvaluationServiceOrganizationTest {

    private static final PodamFactory PODAM_FACTORY = PodamFactoryUtils.newPodamFactory();

    private final String organizationId = UUID.randomUUID().toString();
    private final UUID projectId = UUID.randomUUID();
    private final String workspaceId = UUID.randomUUID().toString();
    private final String userName = RandomStringUtils.secure().nextAlphanumeric(10);

    @Mock
    private AutomationRuleEvaluatorService automationRuleEvaluatorService;

    @Mock
    private OnlineScorePublisher onlineScorePublisher;

    @Mock
    private TraceService traceService;

    @Mock
    private SpanService spanService;

    @Mock
    private ProjectService projectService;

    @Mock
    private TraceThreadService traceThreadService;

    @Test
    @DisplayName("Trace and thread judge rules on traces share one lookup")
    void traceAndThreadJudgeRulesShareOneLookup() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var traceJudges = List.of(traceJudge(), traceJudge(), traceJudge());
        var threadJudges = List.of(threadJudge(), threadJudge());
        var traceIds = stubTraces();
        stubEnqueues();

        evaluate(resolver, ManualEvaluationEntityType.TRACE, traceIds, rules(traceJudges, threadJudges));

        verify(onlineScorePublisher, times(traceJudges.size())).enqueueMessage(anyList(), any(), eq(organizationId));
        verify(onlineScorePublisher, times(threadJudges.size())).enqueueThreadMessage(anyList(),
                any(AutomationRuleEvaluator.class), eq(projectId), eq(workspaceId), eq(userName), eq(organizationId));
        assertThat(resolver.lookups()).isOne();
    }

    @Test
    @DisplayName("Thread judge rules on threads share one lookup")
    void threadJudgeRulesShareOneLookup() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var threadJudges = List.of(threadJudge(), threadJudge(), threadJudge());
        var threadModelIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(traceThreadService.getThreadIdsByThreadModelIds(threadModelIds)).thenReturn(Mono.just(Map.of(
                threadModelIds.getFirst(), UUID.randomUUID().toString(),
                threadModelIds.getLast(), UUID.randomUUID().toString())));
        when(onlineScorePublisher.enqueueThreadMessage(anyList(), any(AutomationRuleEvaluator.class), any(), any(),
                any(), any())).thenReturn(Mono.empty());

        evaluate(resolver, ManualEvaluationEntityType.THREAD, threadModelIds, rules(List.of(), threadJudges));

        verify(onlineScorePublisher, times(threadJudges.size())).enqueueThreadMessage(anyList(),
                any(AutomationRuleEvaluator.class), eq(projectId), eq(workspaceId), eq(userName), eq(organizationId));
        assertThat(resolver.lookups()).isOne();
    }

    @Test
    @DisplayName("Enqueues without an organization when none is known, after one lookup")
    void enqueuesWithoutAnOrganizationWhenNoneIsKnown() {
        var resolver = FixedOrganizationResolver.none();
        var traceJudges = List.of(traceJudge(), traceJudge());
        var threadJudges = List.of(threadJudge(), threadJudge());
        var traceIds = stubTraces();
        stubEnqueues();

        evaluate(resolver, ManualEvaluationEntityType.TRACE, traceIds, rules(traceJudges, threadJudges));

        verify(onlineScorePublisher, times(traceJudges.size())).enqueueMessage(anyList(), any(), isNull());
        verify(onlineScorePublisher, times(threadJudges.size())).enqueueThreadMessage(anyList(),
                any(AutomationRuleEvaluator.class), eq(projectId), eq(workspaceId), eq(userName), isNull());
        assertThat(resolver.lookups()).isOne();
    }

    private void evaluate(OrganizationResolver resolver, ManualEvaluationEntityType entityType, List<UUID> entityIds,
            List<AutomationRuleEvaluator<?, ?>> rules) {
        var ruleIds = rules.stream().map(AutomationRuleEvaluator::getId).toList();
        doReturn(rules).when(automationRuleEvaluatorService).findByIds(Set.copyOf(ruleIds), Set.of(projectId),
                workspaceId);
        var request = ManualEvaluationRequest.builder()
                .projectId(projectId)
                .entityIds(entityIds)
                .ruleIds(ruleIds)
                .entityType(entityType)
                .build();

        new ManualEvaluationServiceImpl(automationRuleEvaluatorService, onlineScorePublisher, traceService,
                spanService, projectService, traceThreadService, new ServiceTogglesConfig(), resolver)
                .evaluate(request, projectId, workspaceId, userName)
                .block();
    }

    private List<UUID> stubTraces() {
        var traces = Stream.generate(() -> Trace.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .name(RandomStringUtils.secure().nextAlphanumeric(10))
                .startTime(Instant.now())
                .build())
                .limit(2)
                .toList();
        var traceIds = traces.stream().map(Trace::id).toList();
        when(projectService.get(projectId, workspaceId)).thenReturn(Project.builder()
                .id(projectId)
                .name(RandomStringUtils.secure().nextAlphanumeric(10))
                .build());
        when(traceService.getByIds(traceIds)).thenReturn(Flux.fromIterable(traces));
        return traceIds;
    }

    private void stubEnqueues() {
        when(onlineScorePublisher.enqueueMessage(anyList(), any(), any())).thenReturn(Mono.empty());
        when(onlineScorePublisher.enqueueThreadMessage(anyList(), any(AutomationRuleEvaluator.class), any(), any(),
                any(), any())).thenReturn(Mono.empty());
    }

    private static List<AutomationRuleEvaluator<?, ?>> rules(List<AutomationRuleEvaluatorLlmAsJudge> traceJudges,
            List<AutomationRuleEvaluatorTraceThreadLlmAsJudge> threadJudges) {
        return Stream.<AutomationRuleEvaluator<?, ?>>concat(traceJudges.stream(), threadJudges.stream()).toList();
    }

    private static AutomationRuleEvaluatorLlmAsJudge traceJudge() {
        return PODAM_FACTORY.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class);
    }

    private static AutomationRuleEvaluatorTraceThreadLlmAsJudge threadJudge() {
        return PODAM_FACTORY.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class);
    }
}
