package com.comet.opik.domain.evaluators;

import com.comet.opik.api.ManualEvaluationEntityType;
import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@DisplayName("Manual Evaluation Service Test")
class ManualEvaluationServiceTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private AutomationRuleEvaluatorService automationRuleEvaluatorService;

    @Mock
    private OnlineScorePublisher onlineScorePublisher;

    @Captor
    private ArgumentCaptor<List<String>> entityIdStringsCaptor;

    private ManualEvaluationService manualEvaluationService;

    @BeforeEach
    void setUp() {
        manualEvaluationService = new ManualEvaluationServiceImpl(
                automationRuleEvaluatorService,
                onlineScorePublisher);
    }

    @Nested
    @DisplayName("Trace Evaluation")
    class TraceEvaluation {

        @Test
        @DisplayName("should successfully evaluate traces with LLM_AS_JUDGE rules")
        void shouldSuccessfullyEvaluateTracesWithLlmAsJudgeRules() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> traceIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            List<UUID> ruleIds = List.of(UUID.randomUUID(), UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(traceIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            var rule1 = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .id(ruleIds.get(0))
                    .build();
            var rule2 = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .id(ruleIds.get(1))
                    .build();

            List<AutomationRuleEvaluator<?>> rules = new ArrayList<>();
            rules.add(rule1);
            rules.add(rule2);

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) rules);

            // When
            Mono<ManualEvaluationResponse> result = manualEvaluationService.evaluate(request, projectId, workspaceId,
                    userName);

            // Then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.entitiesQueued()).isEqualTo(3);
                        assertThat(response.rulesApplied()).isEqualTo(2);
                        assertThat(response.message())
                                .isEqualTo("Successfully queued 3 entities for evaluation with 2 rules");
                    })
                    .verifyComplete();

            // Verify that enqueueThreadMessage was called for each rule (trace IDs are converted to strings)
            verify(onlineScorePublisher, times(2)).enqueueThreadMessage(
                    entityIdStringsCaptor.capture(),
                    any(UUID.class),
                    eq(projectId),
                    eq(workspaceId),
                    eq(userName));

            // Verify the trace IDs (as strings) passed to enqueueThreadMessage
            List<String> expectedTraceIdStrings = traceIds.stream().map(UUID::toString).toList();
            assertThat(entityIdStringsCaptor.getAllValues())
                    .allSatisfy(capturedIds -> assertThat(capturedIds).isEqualTo(expectedTraceIdStrings));
        }

        @Test
        @DisplayName("should successfully evaluate traces with USER_DEFINED_METRIC_PYTHON rules")
        void shouldSuccessfullyEvaluateTracesWithUserDefinedMetricPythonRules() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> traceIds = List.of(UUID.randomUUID());
            List<UUID> ruleIds = List.of(UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(traceIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            var rule = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                    .toBuilder()
                    .id(ruleIds.get(0))
                    .build();

            List<AutomationRuleEvaluator<?>> rules = List.of(rule);

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) rules);

            // When
            Mono<ManualEvaluationResponse> result = manualEvaluationService.evaluate(request, projectId, workspaceId,
                    userName);

            // Then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.entitiesQueued()).isEqualTo(1);
                        assertThat(response.rulesApplied()).isEqualTo(1);
                        assertThat(response.message())
                                .isEqualTo("Successfully queued 1 entity for evaluation with 1 rule");
                    })
                    .verifyComplete();

            // Verify that enqueueThreadMessage was called
            List<String> expectedTraceIdStrings = traceIds.stream().map(UUID::toString).toList();
            verify(onlineScorePublisher).enqueueThreadMessage(
                    eq(expectedTraceIdStrings),
                    eq(ruleIds.get(0)),
                    eq(projectId),
                    eq(workspaceId),
                    eq(userName));
        }

        @Test
        @DisplayName("should throw BadRequestException when rules not found")
        void shouldThrowBadRequestExceptionWhenRulesNotFound() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> traceIds = List.of(UUID.randomUUID());
            List<UUID> ruleIds = List.of(UUID.randomUUID(), UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(traceIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            // Only return one rule when two are requested
            var rule = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .id(ruleIds.get(0))
                    .build();

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) List.of(rule));

            // When & Then
            StepVerifier.create(manualEvaluationService.evaluate(request, projectId, workspaceId, userName))
                    .expectErrorMatches(throwable -> throwable instanceof BadRequestException
                            && throwable.getMessage().contains("Automation rule(s) not found"))
                    .verify();
        }

        @Test
        @DisplayName("should handle empty trace list")
        void shouldHandleEmptyTraceList() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> traceIds = List.of(); // Empty list
            List<UUID> ruleIds = List.of(UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(traceIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            var rule = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .id(ruleIds.get(0))
                    .build();

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) List.of(rule));

            // When
            Mono<ManualEvaluationResponse> result = manualEvaluationService.evaluate(request, projectId, workspaceId,
                    userName);

            // Then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.entitiesQueued()).isZero();
                        assertThat(response.rulesApplied()).isEqualTo(1);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Thread Evaluation")
    class ThreadEvaluation {

        @Test
        @DisplayName("should successfully evaluate threads with LLM_AS_JUDGE rules")
        void shouldSuccessfullyEvaluateThreadsWithLlmAsJudgeRules() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> threadIds = List.of(UUID.randomUUID(), UUID.randomUUID());
            List<UUID> ruleIds = List.of(UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(threadIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            var rule = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .id(ruleIds.get(0))
                    .build();

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) List.of(rule));

            // When
            Mono<ManualEvaluationResponse> result = manualEvaluationService.evaluate(request, projectId, workspaceId,
                    userName);

            // Then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.entitiesQueued()).isEqualTo(2);
                        assertThat(response.rulesApplied()).isEqualTo(1);
                        assertThat(response.message())
                                .isEqualTo("Successfully queued 2 entities for evaluation with 1 rule");
                    })
                    .verifyComplete();

            // Verify that enqueueThreadMessage was called
            verify(onlineScorePublisher).enqueueThreadMessage(
                    entityIdStringsCaptor.capture(),
                    eq(ruleIds.get(0)),
                    eq(projectId),
                    eq(workspaceId),
                    eq(userName));

            // Verify the thread IDs were converted to strings
            List<String> expectedThreadIds = threadIds.stream().map(UUID::toString).toList();
            assertThat(entityIdStringsCaptor.getValue()).isEqualTo(expectedThreadIds);
        }

        @Test
        @DisplayName("should successfully evaluate threads with USER_DEFINED_METRIC_PYTHON rules")
        void shouldSuccessfullyEvaluateThreadsWithUserDefinedMetricPythonRules() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> threadIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            List<UUID> ruleIds = List.of(UUID.randomUUID(), UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(threadIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            var rule1 = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                    .toBuilder()
                    .id(ruleIds.get(0))
                    .build();
            var rule2 = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .id(ruleIds.get(1))
                    .build();

            List<AutomationRuleEvaluator<?>> rules = new ArrayList<>();
            rules.add(rule1);
            rules.add(rule2);

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) rules);

            // When
            Mono<ManualEvaluationResponse> result = manualEvaluationService.evaluate(request, projectId, workspaceId,
                    userName);

            // Then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.entitiesQueued()).isEqualTo(3);
                        assertThat(response.rulesApplied()).isEqualTo(2);
                        assertThat(response.message())
                                .isEqualTo("Successfully queued 3 entities for evaluation with 2 rules");
                    })
                    .verifyComplete();

            // Verify that enqueueThreadMessage was called for each rule
            verify(onlineScorePublisher, times(2)).enqueueThreadMessage(
                    anyList(),
                    any(UUID.class),
                    eq(projectId),
                    eq(workspaceId),
                    eq(userName));
        }

        @Test
        @DisplayName("should handle empty thread list")
        void shouldHandleEmptyThreadList() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> threadIds = List.of(); // Empty list
            List<UUID> ruleIds = List.of(UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(threadIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            var rule = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .id(ruleIds.get(0))
                    .build();

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) List.of(rule));

            // When
            Mono<ManualEvaluationResponse> result = manualEvaluationService.evaluate(request, projectId, workspaceId,
                    userName);

            // Then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.entitiesQueued()).isZero();
                        assertThat(response.rulesApplied()).isEqualTo(1);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Rule Validation")
    class RuleValidation {

        @Test
        @DisplayName("should throw BadRequestException when no rules are returned")
        void shouldThrowBadRequestExceptionWhenNoRulesReturned() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> traceIds = List.of(UUID.randomUUID());
            List<UUID> ruleIds = List.of(UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(traceIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn(List.of()); // No rules found

            // When & Then
            StepVerifier.create(manualEvaluationService.evaluate(request, projectId, workspaceId, userName))
                    .expectErrorMatches(throwable -> throwable instanceof BadRequestException
                            && throwable.getMessage().contains("Automation rule(s) not found"))
                    .verify();
        }

        @Test
        @DisplayName("should throw BadRequestException when only some rules are found")
        void shouldThrowBadRequestExceptionWhenOnlySomeRulesFound() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> threadIds = List.of(UUID.randomUUID());
            UUID foundRuleId = UUID.randomUUID();
            UUID missingRuleId = UUID.randomUUID();
            List<UUID> ruleIds = List.of(foundRuleId, missingRuleId);

            var request = ManualEvaluationRequest.builder()
                    .entityIds(threadIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            var rule = podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .id(foundRuleId)
                    .build();

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) List.of(rule)); // Only one rule found

            // When & Then
            StepVerifier.create(manualEvaluationService.evaluate(request, projectId, workspaceId, userName))
                    .expectErrorMatches(throwable -> throwable instanceof BadRequestException
                            && throwable.getMessage().contains("Automation rule(s) not found")
                            && throwable.getMessage().contains(missingRuleId.toString()))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Mixed Scenarios")
    class MixedScenarios {

        @Test
        @DisplayName("should handle multiple entities and multiple rules for traces")
        void shouldHandleMultipleEntitiesAndMultipleRulesForTraces() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> traceIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID());
            List<UUID> ruleIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(traceIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            List<AutomationRuleEvaluator<?>> rules = ruleIds.stream()
                    .map(ruleId -> podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class)
                            .toBuilder()
                            .id(ruleId)
                            .build())
                    .collect(Collectors.toList());

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) rules);

            // When
            Mono<ManualEvaluationResponse> result = manualEvaluationService.evaluate(request, projectId, workspaceId,
                    userName);

            // Then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.entitiesQueued()).isEqualTo(5);
                        assertThat(response.rulesApplied()).isEqualTo(3);
                        assertThat(response.message())
                                .isEqualTo("Successfully queued 5 entities for evaluation with 3 rules");
                    })
                    .verifyComplete();

            // Verify that enqueueThreadMessage was called 3 times (once per rule)
            List<String> expectedTraceIdStrings = traceIds.stream().map(UUID::toString).toList();
            verify(onlineScorePublisher, times(3)).enqueueThreadMessage(
                    eq(expectedTraceIdStrings),
                    any(UUID.class),
                    eq(projectId),
                    eq(workspaceId),
                    eq(userName));
        }

        @Test
        @DisplayName("should handle multiple entities and multiple rules for threads")
        void shouldHandleMultipleEntitiesAndMultipleRulesForThreads() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> threadIds = List.of(UUID.randomUUID(), UUID.randomUUID());
            List<UUID> ruleIds = List.of(UUID.randomUUID(), UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(threadIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            List<AutomationRuleEvaluator<?>> rules = new ArrayList<>();
            rules.add(podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .id(ruleIds.get(0))
                    .build());
            rules.add(podamFactory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                    .toBuilder()
                    .id(ruleIds.get(1))
                    .build());

            when(automationRuleEvaluatorService.<Object, AutomationRuleEvaluator<Object>>findByIds(
                    any(Set.class), eq(projectId), eq(workspaceId)))
                    .thenReturn((List) rules);

            // When
            Mono<ManualEvaluationResponse> result = manualEvaluationService.evaluate(request, projectId, workspaceId,
                    userName);

            // Then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.entitiesQueued()).isEqualTo(2);
                        assertThat(response.rulesApplied()).isEqualTo(2);
                        assertThat(response.message())
                                .isEqualTo("Successfully queued 2 entities for evaluation with 2 rules");
                    })
                    .verifyComplete();

            // Verify that enqueueThreadMessage was called 2 times (once per rule)
            verify(onlineScorePublisher, times(2)).enqueueThreadMessage(
                    anyList(),
                    any(UUID.class),
                    eq(projectId),
                    eq(workspaceId),
                    eq(userName));
        }
    }
}
