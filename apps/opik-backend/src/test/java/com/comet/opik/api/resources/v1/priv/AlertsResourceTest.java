package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertCreateRequest;
import com.comet.opik.domain.AlertService;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertsResourceTest {

    @Mock
    private Provider<RequestContext> requestContextProvider;

    @Mock
    private AlertService alertService;

    @Mock
    private UriInfo uriInfo;

    @Mock
    private UriBuilder uriBuilder;

    private AlertsResource alertsResource;

    private final String TEST_WORKSPACE_ID = "test-workspace";
    private final UUID TEST_PROJECT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        alertsResource = new AlertsResource(requestContextProvider, alertService);

        RequestContext requestContext = new RequestContext();
        requestContext.setWorkspaceId(TEST_WORKSPACE_ID);
        when(requestContextProvider.get()).thenReturn(requestContext);
    }

    @Test
    void createAlert_shouldReturn201AndLocationHeader() {
        // Given
        AlertCreateRequest request = AlertCreateRequest.builder()
                .name("Test Alert")
                .description("Test Description")
                .conditionType("ERROR_RATE")
                .thresholdValue(BigDecimal.valueOf(0.05))
                .projectId(TEST_PROJECT_ID)
                .build();

        Alert createdAlert = Alert.builder()
                .id(UUID.randomUUID())
                .name("Test Alert")
                .description("Test Description")
                .conditionType("ERROR_RATE")
                .thresholdValue(BigDecimal.valueOf(0.05))
                .projectId(TEST_PROJECT_ID)
                .workspaceId(TEST_WORKSPACE_ID)
                .createdAt(Instant.now())
                .createdBy("test-user")
                .lastUpdatedAt(Instant.now())
                .lastUpdatedBy("test-user")
                .build();

        when(alertService.create(any(Alert.class))).thenReturn(createdAlert);
        when(uriInfo.getAbsolutePathBuilder()).thenReturn(uriBuilder);
        when(uriBuilder.path(any(String.class))).thenReturn(uriBuilder);
        when(uriBuilder.build()).thenReturn(java.net.URI.create("/v1/private/alerts/" + createdAlert.id()));

        // When
        Response response = alertsResource.createAlert(request, uriInfo);

        // Then
        assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
        assertThat(response.getLocation()).isNotNull();
    }
}