package com.comet.opik.api.resources.utils.spans;

import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.resources.utils.TestArgs;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.StatsUtils.getProjectSpanStatItems;

@RequiredArgsConstructor
public final class StatsTestAssertion implements SpanPageTestAssertion<ProjectStats.ProjectStatItem<?>, Span> {

    private final SpanResourceClient spanResourceClient;

    @Override
    public void runTestAndAssert(String projectName,
            UUID projectId,
            String apiKey,
            String workspaceName,
            List<ProjectStats.ProjectStatItem<?>> expected,
            List<ProjectStats.ProjectStatItem<?>> unexpected,
            List<ProjectStats.ProjectStatItem<?>> spans,
            List<? extends SpanFilter> filters,
            Map<String, String> queryParams) {

        ProjectStats actualStats = spanResourceClient.getSpansStats(projectName, projectId, filters, apiKey,
                workspaceName, queryParams);

        SpanAssertions.assertionStatusPage(actualStats.stats(), expected);
    }

    @Override
    public TestArgs<ProjectStats.ProjectStatItem<?>> transformTestParams(List<Span> spans, List<Span> expected,
            List<Span> unexpected) {
        // Stats are calculated from raw database columns, not from DTOs with injected provider
        // So we should NOT prepare spans (inject provider) when calculating expected stats
        return TestArgs.of(List.of(), getProjectSpanStatItems(expected),
                List.of());
    }
}
