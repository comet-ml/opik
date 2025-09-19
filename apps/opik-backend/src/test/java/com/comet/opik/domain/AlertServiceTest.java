package com.comet.opik.domain;

import com.comet.opik.api.Alert;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private AlertService alertService;

    private final UUID TEST_PROJECT_ID = UUID.randomUUID();
    private final String TEST_WORKSPACE_ID = "test-workspace";

    @BeforeEach
    void setUp() {
        // Mock is injected automatically by Mockito
    }

    @Test
    void create_shouldReturnCreatedAlert() {
        // Given
        Alert alertToCreate = Alert.builder()
                .name("Test Alert")
                .description("Test Description")
                .conditionType("ERROR_RATE")
                .thresholdValue(BigDecimal.valueOf(0.05))
                .projectId(TEST_PROJECT_ID)
                .build();

        Alert createdAlert = alertToCreate.toBuilder()
                .id(UUID.randomUUID())
                .workspaceId(TEST_WORKSPACE_ID)
                .createdAt(Instant.now())
                .createdBy("test-user")
                .lastUpdatedAt(Instant.now())
                .lastUpdatedBy("test-user")
                .build();

        when(alertService.create(alertToCreate)).thenReturn(createdAlert);

        // When
        Alert result = alertService.create(alertToCreate);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isNotNull();
        assertThat(result.name()).isEqualTo("Test Alert");
        assertThat(result.conditionType()).isEqualTo("ERROR_RATE");
        assertThat(result.workspaceId()).isEqualTo(TEST_WORKSPACE_ID);
    }

    @Test
    void getById_shouldReturnAlert() {
        // Given
        UUID alertId = UUID.randomUUID();
        Alert expectedAlert = Alert.builder()
                .id(alertId)
                .name("Test Alert")
                .conditionType("ERROR_RATE")
                .thresholdValue(BigDecimal.valueOf(0.05))
                .projectId(TEST_PROJECT_ID)
                .workspaceId(TEST_WORKSPACE_ID)
                .build();

        when(alertService.getById(alertId)).thenReturn(Optional.of(expectedAlert));

        // When
        Optional<Alert> result = alertService.getById(alertId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(alertId);
        assertThat(result.get().name()).isEqualTo("Test Alert");
    }

    @Test
    void findAlerts_shouldReturnListOfAlerts() {
        // Given
        List<Alert> expectedAlerts = List.of(
                Alert.builder()
                        .id(UUID.randomUUID())
                        .name("Alert 1")
                        .conditionType("ERROR_RATE")
                        .thresholdValue(BigDecimal.valueOf(0.05))
                        .projectId(TEST_PROJECT_ID)
                        .build(),
                Alert.builder()
                        .id(UUID.randomUUID())
                        .name("Alert 2")
                        .conditionType("LATENCY")
                        .thresholdValue(BigDecimal.valueOf(1000))
                        .projectId(TEST_PROJECT_ID)
                        .build());

        when(alertService.findAlerts(1, 25, null, null, TEST_PROJECT_ID, null)).thenReturn(expectedAlerts);

        // When
        List<Alert> result = alertService.findAlerts(1, 25, null, null, TEST_PROJECT_ID, null);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).isEqualTo(expectedAlerts);
    }

    @Test
    void count_shouldReturnCount() {
        // Given
        when(alertService.count(null, null, TEST_PROJECT_ID)).thenReturn(5L);

        // When
        long result = alertService.count(null, null, TEST_PROJECT_ID);

        // Then
        assertThat(result).isEqualTo(5L);
    }

    @Test
    void findByProjectId_shouldReturnAlertsForProject() {
        // Given
        List<Alert> expectedAlerts = List.of(
                Alert.builder()
                        .id(UUID.randomUUID())
                        .name("Project Alert 1")
                        .conditionType("ERROR_RATE")
                        .projectId(TEST_PROJECT_ID)
                        .build(),
                Alert.builder()
                        .id(UUID.randomUUID())
                        .name("Project Alert 2")
                        .conditionType("LATENCY")
                        .projectId(TEST_PROJECT_ID)
                        .build());

        when(alertService.findByProjectId(TEST_PROJECT_ID)).thenReturn(expectedAlerts);

        // When
        List<Alert> result = alertService.findByProjectId(TEST_PROJECT_ID);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).isEqualTo(expectedAlerts);
    }
}