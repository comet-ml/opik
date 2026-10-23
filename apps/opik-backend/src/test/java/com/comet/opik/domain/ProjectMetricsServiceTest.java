package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.Project;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.metrics.BreakdownConfig;
import com.comet.opik.api.metrics.BreakdownField;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectMetricsServiceTest {

    @Mock
    private ProjectMetricsDAO dao;
    @Mock
    private ProjectService projectService;

    @ParameterizedTest
    @EnumSource(value = MetricType.class, names = {"COST", "SPAN_COST"})
    void othersCostsPreserveDecimalPrecisionAndSortSparseBuckets(MetricType metricType) {
        var projectId = UUID.randomUUID();
        var first = Instant.parse("2026-01-01T00:00:00Z");
        var second = first.plusSeconds(3600);
        var third = second.plusSeconds(3600);
        var entries = new ArrayList<ProjectMetricsDAO.Entry>();
        var results = new ArrayList<ProjectMetricResponse.Results<Number>>();
        for (int i = 12; i >= 3; i--) {
            entries.add(entry("group-" + i, first, String.valueOf(i)));
            results.add(ProjectMetricResponse.Results.<Number>builder().name("group-" + i)
                    .data(List.of(point(first, String.valueOf(i)))).build());
        }
        // Deliberately unsorted and sparse; the shared bucket must be summed without a double conversion.
        entries.add(entry("small-a", third, "0.4"));
        entries.add(entry("small-a", first, "0.100000000000000001"));
        entries.add(entry("small-b", second, "0.3"));
        entries.add(entry("small-b", first, "0.200000000000000002"));
        results.add(ProjectMetricResponse.Results.<Number>builder().name("__others__")
                .data(List.of(point(first, "0.300000000000000003"), point(second, "0.3"), point(third, "0.4")))
                .build());
        when(projectService.getOrFail(projectId)).thenReturn(Mono.just(Project.builder().id(projectId).build()));
        if (metricType == MetricType.SPAN_COST) {
            when(dao.getSpanCost(eq(projectId), any())).thenReturn(Mono.just(entries));
        } else {
            when(dao.getCost(eq(projectId), any())).thenReturn(Mono.just(entries));
        }
        var request = ProjectMetricRequest.builder().metricType(metricType).interval(TimeInterval.HOURLY)
                .intervalStart(first).intervalEnd(third.plusSeconds(3600))
                .breakdown(BreakdownConfig.builder().field(BreakdownField.NAME).build()).build();
        var expected = ProjectMetricResponse.<Number>builder().projectId(projectId).metricType(metricType)
                .interval(TimeInterval.HOURLY).results(results).build();
        var service = new ProjectMetricsServiceImpl(dao, projectService, new InstantToUUIDMapper());
        assertThat(service.getProjectMetrics(projectId, request).block()).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(expected);
    }

    private ProjectMetricsDAO.Entry entry(String group, Instant time, String cost) {
        return ProjectMetricsDAO.Entry.builder().name("cost").groupName(group).time(time)
                .value(new BigDecimal(cost)).build();
    }

    private DataPoint<Number> point(Instant time, String cost) {
        return DataPoint.<Number>builder().time(time).value(new BigDecimal(cost)).build();
    }
}
