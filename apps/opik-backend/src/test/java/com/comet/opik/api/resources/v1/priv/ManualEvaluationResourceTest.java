package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ManualEvaluationEntityType;
import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import com.comet.opik.domain.evaluators.ManualEvaluationService;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Manual Evaluation Resource Test")
class ManualEvaluationResourceTest {

    @Mock
    private ManualEvaluationService manualEvaluationService;

    @Mock
    private Provider<RequestContext> requestContextProvider;

    @Mock
    private RequestContext requestContext;

    private ManualEvaluationResource manualEvaluationResource;

    @BeforeEach
    void setUp() {
        manualEvaluationResource = new ManualEvaluationResource(
                manualEvaluationService,
                requestContextProvider);

        when(requestContextProvider.get()).thenReturn(requestContext);
    }

    @Nested
    @DisplayName("Evaluate Traces Endpoint")
    class EvaluateTracesEndpoint {

        @Test
        @DisplayName("should return 202 Accepted when evaluation request is successful")
        void shouldReturn202AcceptedWhenEvaluationRequestIsSuccessful() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            List<UUID> traceIds = List.of(UUID.randomUUID(), UUID.randomUUID());
            List<UUID> ruleIds = List.of(UUID.randomUUID());

            var request = ManualEvaluationRequest.builder()
                    .entityIds(traceIds)
                    .ruleIds(ruleIds)
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            var evaluationResponse = ManualEvaluationResponse.of(2, 1);

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(eq(request), eq(projectId), eq(workspaceId), eq(userName)))
                    .thenReturn(Mono.just(evaluationResponse));

            // When
            Response response = manualEvaluationResource.evaluateTraces(projectId, request);

            // Then
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(response.getEntity()).isEqualTo(evaluationResponse);

            verify(manualEvaluationService).evaluate(request, projectId, workspaceId, userName);
        }

        @Test
        @DisplayName("should handle BadRequestException from service")
        void shouldHandleBadRequestExceptionFromService() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            var request = ManualEvaluationRequest.builder()
                    .entityIds(List.of(UUID.randomUUID()))
                    .ruleIds(List.of(UUID.randomUUID()))
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(any(), any(), anyString(), anyString()))
                    .thenReturn(Mono.error(new BadRequestException("Rules not found")));

            // When & Then
            assertThatThrownBy(() -> manualEvaluationResource.evaluateTraces(projectId, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Rules not found");
        }

        @Test
        @DisplayName("should handle NotFoundException from service")
        void shouldHandleNotFoundExceptionFromService() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            var request = ManualEvaluationRequest.builder()
                    .entityIds(List.of(UUID.randomUUID()))
                    .ruleIds(List.of(UUID.randomUUID()))
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(any(), any(), anyString(), anyString()))
                    .thenReturn(Mono.error(new NotFoundException("Project not found")));

            // When & Then
            assertThatThrownBy(() -> manualEvaluationResource.evaluateTraces(projectId, request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Project not found");
        }

        @Test
        @DisplayName("should extract workspace and user info from request context")
        void shouldExtractWorkspaceAndUserInfoFromRequestContext() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = "workspace-123";
            String userName = "john-doe";

            var request = ManualEvaluationRequest.builder()
                    .entityIds(List.of(UUID.randomUUID()))
                    .ruleIds(List.of(UUID.randomUUID()))
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            var evaluationResponse = ManualEvaluationResponse.of(1, 1);

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(request, projectId, workspaceId, userName))
                    .thenReturn(Mono.just(evaluationResponse));

            // When
            manualEvaluationResource.evaluateTraces(projectId, request);

            // Then
            verify(requestContext).getWorkspaceId();
            verify(requestContext).getUserName();
            verify(manualEvaluationService).evaluate(request, projectId, workspaceId, userName);
        }
    }

    @Nested
    @DisplayName("Evaluate Threads Endpoint")
    class EvaluateThreadsEndpoint {

        @Test
        @DisplayName("should return 202 Accepted when evaluation request is successful")
        void shouldReturn202AcceptedWhenEvaluationRequestIsSuccessful() {
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

            var evaluationResponse = ManualEvaluationResponse.of(3, 2);

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(eq(request), eq(projectId), eq(workspaceId), eq(userName)))
                    .thenReturn(Mono.just(evaluationResponse));

            // When
            Response response = manualEvaluationResource.evaluateThreads(projectId, request);

            // Then
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(response.getEntity()).isEqualTo(evaluationResponse);

            verify(manualEvaluationService).evaluate(request, projectId, workspaceId, userName);
        }

        @Test
        @DisplayName("should handle BadRequestException from service")
        void shouldHandleBadRequestExceptionFromService() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            var request = ManualEvaluationRequest.builder()
                    .entityIds(List.of(UUID.randomUUID()))
                    .ruleIds(List.of(UUID.randomUUID(), UUID.randomUUID()))
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(any(), any(), anyString(), anyString()))
                    .thenReturn(Mono.error(new BadRequestException("Some rules not found")));

            // When & Then
            assertThatThrownBy(() -> manualEvaluationResource.evaluateThreads(projectId, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Some rules not found");
        }

        @Test
        @DisplayName("should handle NotFoundException from service")
        void shouldHandleNotFoundExceptionFromService() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            var request = ManualEvaluationRequest.builder()
                    .entityIds(List.of(UUID.randomUUID()))
                    .ruleIds(List.of(UUID.randomUUID()))
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(any(), any(), anyString(), anyString()))
                    .thenReturn(Mono.error(new NotFoundException("Project not found")));

            // When & Then
            assertThatThrownBy(() -> manualEvaluationResource.evaluateThreads(projectId, request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Project not found");
        }

        @Test
        @DisplayName("should extract workspace and user info from request context")
        void shouldExtractWorkspaceAndUserInfoFromRequestContext() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = "workspace-456";
            String userName = "jane-smith";

            var request = ManualEvaluationRequest.builder()
                    .entityIds(List.of(UUID.randomUUID()))
                    .ruleIds(List.of(UUID.randomUUID()))
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            var evaluationResponse = ManualEvaluationResponse.of(1, 1);

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(request, projectId, workspaceId, userName))
                    .thenReturn(Mono.just(evaluationResponse));

            // When
            manualEvaluationResource.evaluateThreads(projectId, request);

            // Then
            verify(requestContext).getWorkspaceId();
            verify(requestContext).getUserName();
            verify(manualEvaluationService).evaluate(request, projectId, workspaceId, userName);
        }

        @Test
        @DisplayName("should handle empty entity lists")
        void shouldHandleEmptyEntityLists() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            var request = ManualEvaluationRequest.builder()
                    .entityIds(List.of()) // Empty list
                    .ruleIds(List.of(UUID.randomUUID()))
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            var evaluationResponse = ManualEvaluationResponse.of(0, 1);

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(request, projectId, workspaceId, userName))
                    .thenReturn(Mono.just(evaluationResponse));

            // When
            Response response = manualEvaluationResource.evaluateThreads(projectId, request);

            // Then
            assertThat(response.getStatus()).isEqualTo(202);
            var responseEntity = (ManualEvaluationResponse) response.getEntity();
            assertThat(responseEntity.entitiesQueued()).isZero();
        }
    }

    @Nested
    @DisplayName("Response Message Validation")
    class ResponseMessageValidation {

        @Test
        @DisplayName("should return proper message for single entity and single rule")
        void shouldReturnProperMessageForSingleEntityAndSingleRule() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            var request = ManualEvaluationRequest.builder()
                    .entityIds(List.of(UUID.randomUUID()))
                    .ruleIds(List.of(UUID.randomUUID()))
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            var evaluationResponse = ManualEvaluationResponse.of(1, 1);

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(request, projectId, workspaceId, userName))
                    .thenReturn(Mono.just(evaluationResponse));

            // When
            Response response = manualEvaluationResource.evaluateTraces(projectId, request);

            // Then
            var responseEntity = (ManualEvaluationResponse) response.getEntity();
            assertThat(responseEntity.message())
                    .isEqualTo("Successfully queued 1 entity for evaluation with 1 rule");
        }

        @Test
        @DisplayName("should return proper message for multiple entities and multiple rules")
        void shouldReturnProperMessageForMultipleEntitiesAndMultipleRules() {
            // Given
            UUID projectId = UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            String userName = "test-user";

            var request = ManualEvaluationRequest.builder()
                    .entityIds(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                    .ruleIds(List.of(UUID.randomUUID(), UUID.randomUUID()))
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            var evaluationResponse = ManualEvaluationResponse.of(3, 2);

            when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
            when(requestContext.getUserName()).thenReturn(userName);
            when(manualEvaluationService.evaluate(request, projectId, workspaceId, userName))
                    .thenReturn(Mono.just(evaluationResponse));

            // When
            Response response = manualEvaluationResource.evaluateThreads(projectId, request);

            // Then
            var responseEntity = (ManualEvaluationResponse) response.getEntity();
            assertThat(responseEntity.message())
                    .isEqualTo("Successfully queued 3 entities for evaluation with 2 rules");
        }
    }
}
