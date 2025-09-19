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
import java.util.List;
import java.util.Optional;
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

    @Test
    void getAlerts_shouldReturnAlertPage() {
        // Given
        List<Alert> alerts = List.of(
                Alert.builder()
                        .id(UUID.randomUUID())
                        .name("Alert 1")
                        .conditionType("ERROR_RATE")
                        .thresholdValue(BigDecimal.valueOf(0.05))
                        .build(),
                Alert.builder()
                        .id(UUID.randomUUID())
                        .name("Alert 2")
                        .conditionType("LATENCY")
                        .thresholdValue(BigDecimal.valueOf(1000))
                        .build());

        when(alertService.findAlerts(1, 25, null, null, null, null)).thenReturn(alerts);
        when(alertService.count(null, null, null)).thenReturn(2L);

        // When
        Response response = alertsResource.getAlerts(1, 25, null, null, null, null);

        // Then
        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        assertThat(response.getEntity()).isInstanceOf(Alert.AlertPage.class);

        Alert.AlertPage alertPage = (Alert.AlertPage) response.getEntity();
        assertThat(alertPage.page()).isEqualTo(1);
        assertThat(alertPage.size()).isEqualTo(25);
        assertThat(alertPage.total()).isEqualTo(2L);
        assertThat(alertPage.content()).hasSize(2);
    }

    @Test
    void getById_shouldReturnAlert() {
        // Given
        UUID alertId = UUID.randomUUID();
        Alert alert = Alert.builder()
                .id(alertId)
                .name("Test Alert")
                .conditionType("ERROR_RATE")
                .thresholdValue(BigDecimal.valueOf(0.05))
                .build();

        when(alertService.getById(alertId)).thenReturn(Optional.of(alert));

        // When
        Response response = alertsResource.getById(alertId);

        // Then
        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        assertThat(response.getEntity()).isEqualTo(alert);
    }
}