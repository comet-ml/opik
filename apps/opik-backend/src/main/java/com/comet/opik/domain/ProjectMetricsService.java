package com.comet.opik.domain;

import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@ImplementedBy(ProjectMetricsServiceImpl.class)
public interface ProjectMetricsService {
    ProjectMetricResponse getProjectMetrics(UUID projectId, ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsServiceImpl implements ProjectMetricsService {

    @Override
    public ProjectMetricResponse getProjectMetrics(UUID projectId, ProjectMetricRequest request) {
        return ProjectMetricResponse.EMPTY;
    }
}
