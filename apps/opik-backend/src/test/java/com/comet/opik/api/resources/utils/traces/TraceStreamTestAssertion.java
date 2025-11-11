package com.comet.opik.api.resources.utils.traces;

import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.resources.utils.TestArgs;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
public final class TraceStreamTestAssertion implements TracePageTestAssertion<Trace, Trace> {

    private final TraceResourceClient traceResourceClient;
    private final String userName;

    @Override
    public void assertTest(String projectName, UUID projectId, String apiKey, String workspaceName,
            List<Trace> expected, List<Trace> unexpected, List<Trace> all, List<? extends TraceFilter> filters,
            Map<String, String> queryParams) {

        int limit = expected.size() + unexpected.size() + all.size();

        var streamRequest = TraceSearchStreamRequest.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(List.copyOf(filters))
                .fromTime(Optional.ofNullable(queryParams.get("from_time")).map(Instant::parse).orElse(null))
                .toTime(Optional.ofNullable(queryParams.get("to_time")).map(Instant::parse).orElse(null))
                .limit(limit > 0 ? limit : null)
                .build();

        var actualTraces = traceResourceClient.getStreamAndAssertContent(apiKey, workspaceName, streamRequest);

        TraceAssertions.assertTraces(actualTraces, expected, userName);
    }

    @Override
    public TestArgs<Trace> transformTestParams(List<Trace> all, List<Trace> expected, List<Trace> unexpected) {
        return TestArgs.of(all, expected, unexpected);
    }
}
