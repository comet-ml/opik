package com.comet.opik.api.resources.utils.spans;

import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.resources.utils.TestArgs;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface SpanPageTestAssertion<T, R>
        permits SpansTestAssertion, SpanStreamTestAssertion, StatsTestAssertion {

    void runTestAndAssert(String projectName,
            UUID projectId,
            String apiKey,
            String workspaceName,
            List<T> expected,
            List<T> unexpected,
            List<T> spans,
            List<? extends SpanFilter> filters,
            Map<String, String> queryParams);

    TestArgs<T> transformTestParams(List<R> spans, List<R> expected, List<R> unexpected);
}
