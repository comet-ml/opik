package com.comet.opik.api.resources.utils.traces;

import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.resources.utils.TestArgs;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface TracePageTestAssertion<T, R>
        permits TraceStatsAssertion, TraceStreamTestAssertion, TraceTestAssertion {

    void assertTest(
            String projectName,
            UUID projectId,
            String apiKey,
            String workspaceName,
            List<T> expected,
            List<T> unexpected,
            List<T> all,
            List<? extends TraceFilter> filters,
            Map<String, String> queryParams);

    TestArgs<T> transformTestParams(List<R> all, List<R> expected, List<R> unexpected);

}
