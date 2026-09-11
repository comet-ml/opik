package com.comet.opik.api.resources.utils.traces;

import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.resources.utils.TestArgs;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.StatsUtils.getProjectTraceStatItems;

@RequiredArgsConstructor
public final class TraceStatsAssertion implements TracePageTestAssertion<ProjectStats.ProjectStatItem<?>, Trace> {

    private final TraceResourceClient traceResourceClient;

    @Override
    public void assertTest(String projectName, UUID projectId, String apiKey, String workspaceName,
            List<ProjectStats.ProjectStatItem<?>> expected, List<ProjectStats.ProjectStatItem<?>> unexpected,
            List<ProjectStats.ProjectStatItem<?>> all, List<? extends TraceFilter> filters,
            Map<String, String> queryParams) {
        ProjectStats actualStats = traceResourceClient.getTraceStats(projectName, projectId, apiKey, workspaceName,
                filters, queryParams);

        TraceAssertions.assertStats(actualStats.stats(), expected);
    }

    @Override
    public TestArgs<ProjectStats.ProjectStatItem<?>> transformTestParams(List<Trace> all, List<Trace> expected,
            List<Trace> unexpected) {
        return TestArgs.of(List.of(), getProjectTraceStatItems(expected), List.of());
    }
}
