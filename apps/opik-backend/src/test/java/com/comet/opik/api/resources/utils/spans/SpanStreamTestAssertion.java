package com.comet.opik.api.resources.utils.spans;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.resources.utils.TestArgs;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.spans.SpanAssertions.assertSpan;

@RequiredArgsConstructor
public final class SpanStreamTestAssertion implements SpanPageTestAssertion<Span, Span> {

    private final SpanResourceClient spanResourceClient;
    private final String userName;

    @Override
    public void runTestAndAssert(String projectName,
            UUID projectId,
            String apiKey,
            String workspaceName,
            List<Span> expected,
            List<Span> unexpected,
            List<Span> spans,
            List<? extends SpanFilter> filters,
            Map<String, String> queryParams) {

        var streamRequest = SpanSearchStreamRequest.builder().projectName(projectName)
                .filters(List.copyOf(filters)).build();

        var actualSpans = spanResourceClient.getStreamAndAssertContent(apiKey, workspaceName, streamRequest);

        // SpanAssertions.assertSpan now automatically handles provider injection
        assertSpan(actualSpans, expected, userName);
    }

    @Override
    public TestArgs<Span> transformTestParams(List<Span> spans, List<Span> expected, List<Span> unexpected) {
        return TestArgs.of(spans, expected, unexpected);
    }
}
