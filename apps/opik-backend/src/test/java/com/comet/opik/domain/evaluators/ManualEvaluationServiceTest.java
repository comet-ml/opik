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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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

    static Stream<Arguments> entityTypeProvider() {
        return Stream.of(
                arguments(ManualEvaluationEntityType.TRACE),
                arguments(ManualEvaluationEntityType.THREAD));
    }

    static Stream<Arguments> multipleEntitiesAndRulesProvider() {
        return Stream.of(
                arguments(ManualEvaluationEntityType.TRACE, 3, 2),
                arguments(ManualEvaluationEntityType.THREAD, 5, 3),
                arguments(ManualEvaluationEntityType.TRACE, 1, 1),
                arguments(ManualEvaluationEntityType.THREAD, 2, 1));
    }

    @Nested
    @DisplayName("Entity Evaluation")
    class EntityEvaluation {

        @ParameterizedTest
        @MethodSource("com.comet.opik.domain.evaluators.ManualEvaluationServiceTest#entityTypeProvider")
        @DisplayName("should successfully evaluate entities with LLM_AS_JUDGE rules")
        void shouldSuccessfullyEvaluateEntitiesWithLlmAsJudgeRules(ManualEvaluationEntityType entityType) {
            // Given
            var projectId = podamFactory.manufacturePojo(UUID.class);
            var workspaceId = podamFactory.manufacturePojo(String.class);
            var userName = podamFactory.manufacturePojo(String.class);

            var entityIds = List.of(
                    podamFactory.manufacturePojo(UUID.class),
                    podamFactory.manufacturePojo(UUID.class),
                    podamFactory.manufacturePojo(UUID.class));
            var ruleIds = List.of(
                    podamFactory.manufacturePojo(UUID.class),
                    podamFactory.manufacturePojo(UUID.class));

            var request = ManualEvaluationRequest.builder()
                    .entityIds(entityIds)
                    .ruleIds(ruleIds)
                    .entityType(entityType)
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

            // Verify that enqueueThreadMessage was called for each rule
            verify(onlineScorePublisher, times(2)).enqueueThreadMessage(
                    entityIdStringsCaptor.capture(),
                    any(UUID.class),
                    eq(projectId),
                    eq(workspaceId),
                    eq(userName));

            // Verify the entity IDs (as strings) passed to enqueueThreadMessage
            List<String> expectedEntityIdStrings = entityIds.stream().map(UUID::toString).toList();
            assertThat(entityIdStringsCaptor.getAllValues())
                    .allSatisfy(capturedIds -> assertThat(capturedIds).isEqualTo(expectedEntityIdStrings));
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.domain.evaluators.ManualEvaluationServiceTest#entityTypeProvider")
        @DisplayName("should successfully evaluate entities with USER_DEFINED_METRIC_PYTHON rules")
        void shouldSuccessfullyEvaluateEntitiesWithUserDefinedMetricPythonRules(ManualEvaluationEntityType entityType) {
            // Given
            var projectId = podamFactory.manufacturePojo(UUID.class);
            var workspaceId = podamFactory.manufacturePojo(String.class);
            var userName = podamFactory.manufacturePojo(String.class);

            var entityIds = List.of(podamFactory.manufacturePojo(UUID.class));
            var ruleIds = List.of(podamFactory.manufacturePojo(UUID.class));

            var request = ManualEvaluationRequest.builder()
                    .entityIds(entityIds)
                    .ruleIds(ruleIds)
                    .entityType(entityType)
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
            List<String> expectedEntityIdStrings = entityIds.stream().map(UUID::toString).toList();
            verify(onlineScorePublisher).enqueueThreadMessage(
                    eq(expectedEntityIdStrings),
                    eq(ruleIds.get(0)),
                    eq(projectId),
                    eq(workspaceId),
                    eq(userName));
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.domain.evaluators.ManualEvaluationServiceTest#entityTypeProvider")
        @DisplayName("should handle empty entity list")
        void shouldHandleEmptyEntityList(ManualEvaluationEntityType entityType) {
            // Given
            var projectId = podamFactory.manufacturePojo(UUID.class);
            var workspaceId = podamFactory.manufacturePojo(String.class);
            var userName = podamFactory.manufacturePojo(String.class);

            List<UUID> entityIds = List.of(); // Empty list
            var ruleIds = List.of(podamFactory.manufacturePojo(UUID.class));

            var request = ManualEvaluationRequest.builder()
                    .entityIds(entityIds)
                    .ruleIds(ruleIds)
                    .entityType(entityType)
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
            var projectId = podamFactory.manufacturePojo(UUID.class);
            var workspaceId = podamFactory.manufacturePojo(String.class);
            var userName = podamFactory.manufacturePojo(String.class);

            var entityIds = List.of(podamFactory.manufacturePojo(UUID.class));
            var ruleIds = List.of(podamFactory.manufacturePojo(UUID.class));

            var request = ManualEvaluationRequest.builder()
                    .entityIds(entityIds)
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
            var projectId = podamFactory.manufacturePojo(UUID.class);
            var workspaceId = podamFactory.manufacturePojo(String.class);
            var userName = podamFactory.manufacturePojo(String.class);

            var entityIds = List.of(podamFactory.manufacturePojo(UUID.class));
            var foundRuleId = podamFactory.manufacturePojo(UUID.class);
            var missingRuleId = podamFactory.manufacturePojo(UUID.class);
            List<UUID> ruleIds = List.of(foundRuleId, missingRuleId);

            var request = ManualEvaluationRequest.builder()
                    .entityIds(entityIds)
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

        @ParameterizedTest
        @MethodSource("com.comet.opik.domain.evaluators.ManualEvaluationServiceTest#multipleEntitiesAndRulesProvider")
        @DisplayName("should handle multiple entities and multiple rules")
        void shouldHandleMultipleEntitiesAndMultipleRules(ManualEvaluationEntityType entityType, int entityCount,
                int ruleCount) {
            // Given
            var projectId = podamFactory.manufacturePojo(UUID.class);
            var workspaceId = podamFactory.manufacturePojo(String.class);
            var userName = podamFactory.manufacturePojo(String.class);

            var entityIds = Stream.generate(() -> podamFactory.manufacturePojo(UUID.class))
                    .limit(entityCount)
                    .collect(Collectors.toList());
            var ruleIds = Stream.generate(() -> podamFactory.manufacturePojo(UUID.class))
                    .limit(ruleCount)
                    .collect(Collectors.toList());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(entityIds)
                    .ruleIds(ruleIds)
                    .entityType(entityType)
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
                        assertThat(response.entitiesQueued()).isEqualTo(entityCount);
                        assertThat(response.rulesApplied()).isEqualTo(ruleCount);
                    })
                    .verifyComplete();

            // Verify that enqueueThreadMessage was called for each rule
            List<String> expectedEntityIdStrings = entityIds.stream().map(UUID::toString).toList();
            verify(onlineScorePublisher, times(ruleCount)).enqueueThreadMessage(
                    eq(expectedEntityIdStrings),
                    any(UUID.class),
                    eq(projectId),
                    eq(workspaceId),
                    eq(userName));
        }

        @Test
        @DisplayName("should handle mixed rule types")
        void shouldHandleMixedRuleTypes() {
            // Given
            var projectId = podamFactory.manufacturePojo(UUID.class);
            var workspaceId = podamFactory.manufacturePojo(String.class);
            var userName = podamFactory.manufacturePojo(String.class);

            var entityIds = List.of(
                    podamFactory.manufacturePojo(UUID.class),
                    podamFactory.manufacturePojo(UUID.class));
            var ruleIds = List.of(
                    podamFactory.manufacturePojo(UUID.class),
                    podamFactory.manufacturePojo(UUID.class));

            var request = ManualEvaluationRequest.builder()
                    .entityIds(entityIds)
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
    }
}
