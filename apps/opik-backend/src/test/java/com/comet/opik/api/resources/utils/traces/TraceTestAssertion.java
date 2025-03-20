package com.comet.opik.api.resources.utils.traces;

import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.resources.utils.TestArgs;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
public final class TraceTestAssertion implements TracePageTestAssertion<Trace, Trace> {

    private final TraceResourceClient traceResourceClient;
    private final String userName;

    @Override
    public void assertTest(String projectName, UUID projectId, String apiKey, String workspaceName,
            List<Trace> expected, List<Trace> unexpected, List<Trace> all, List<? extends TraceFilter> filters,
            Map<String, String> queryParams) {

        int size = Integer
                .parseInt(queryParams.getOrDefault("size", all.size() + expected.size() + unexpected.size() + ""));

        var actualPage = traceResourceClient.getTraces(projectName, projectId, apiKey, workspaceName, filters,
                List.of(), size, queryParams);

        var actualTraces = actualPage.content();

        int page = Integer.parseInt(queryParams.getOrDefault("page", "1"));

        TraceAssertions.assertPage(actualPage, page, expected.size(), expected.size());

        TraceAssertions.assertTraces(actualTraces, expected, unexpected, userName);
    }

    @Override
    public TestArgs<Trace> transformTestParams(List<Trace> all, List<Trace> expected, List<Trace> unexpected) {
        return TestArgs.of(all, expected, unexpected);
    }
}
