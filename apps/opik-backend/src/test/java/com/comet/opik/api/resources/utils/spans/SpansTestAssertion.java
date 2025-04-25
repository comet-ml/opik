package com.comet.opik.api.resources.utils.spans;

import com.comet.opik.api.Span;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.resources.utils.TestArgs;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.domain.SpanType;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.spans.SpanAssertions.assertSpan;

@RequiredArgsConstructor
public final class SpansTestAssertion implements SpanPageTestAssertion<Span, Span> {

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

        int page = Integer.parseInt(queryParams.getOrDefault("page", "1"));
        int size = Integer.parseInt(queryParams.getOrDefault("size",
                spans.size() + expected.size() + unexpected.size() + ""));

        Span.SpanPage actualPage = spanResourceClient.findSpans(
                workspaceName,
                apiKey,
                projectName,
                projectId,
                page,
                size,
                Optional.ofNullable(queryParams.get("trace_id")).map(UUID::fromString).orElse(null),
                Optional.ofNullable(queryParams.get("type")).map(SpanType::valueOf).orElse(null),
                filters,
                List.of(),
                List.of());

        SpanAssertions.assertPage(actualPage, page, expected.size(), expected.size());
        assertSpan(actualPage.content(), expected, unexpected, userName);

    }

    @Override
    public TestArgs<Span> transformTestParams(List<Span> spans, List<Span> expected, List<Span> unexpected) {
        return TestArgs.of(spans, expected, unexpected);
    }
}
