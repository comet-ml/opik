import { test, expect } from '../../fixtures/tracing.fixture';

test('Spans returned by search_spans contain expected fields and respect exclude option @fullregression @tracing', async ({
  helperClient,
  projectName,
  createTracesWithSpansClient,
}) => {
  test.info().annotations.push({
    type: 'description',
    description: `Tests that spans returned by search_spans contain the expected fields from the fixture and that the exclude option removes specified fields.

Steps:
1. Create traces with spans, tags, metadata, and feedback scores (handled by fixture)
2. Search spans without exclude and verify all expected fields are returned
3. Search spans with exclude=["feedback_scores"] and verify scores are absent while other fields remain`
  });

  const { traceConfig, spanConfig } = createTracesWithSpansClient;
  const totalSpans = traceConfig.count * spanConfig.count;

  await test.step('Search spans and verify all expected fields are returned', async () => {
    const spans = await helperClient.searchSpans(projectName, {
      maxResults: totalSpans,
      waitForAtLeast: totalSpans,
      waitForTimeout: 30,
    });

    expect(spans).toHaveLength(totalSpans);

    for (let i = 0; i < spanConfig.count; i++) {
      const span = spans.find((s: Record<string, any>) => s.name === `${spanConfig.prefix}${i}`);
      expect(span).toBeDefined();
      expect(span!.id).toBeDefined();
      expect(span!.trace_id).toBeDefined();
      expect(span!.input).toMatchObject({ input: `input-${i}` });
      expect(span!.output).toMatchObject({ output: `output-${i}` });
      expect(span!.tags).toEqual(expect.arrayContaining(spanConfig.tags!));
      expect(span!.metadata).toMatchObject(spanConfig.metadata!);

      const scores = span!.feedback_scores;
      expect(scores).toHaveLength(spanConfig.feedback_scores!.length);
      for (const expectedScore of spanConfig.feedback_scores!) {
        expect(scores).toEqual(
          expect.arrayContaining([expect.objectContaining({ name: expectedScore.name, value: expectedScore.value, source: 'sdk' })])
        );
      }
    }
  });

  await test.step('Search spans with exclude=["feedback_scores"] and verify scores are absent', async () => {
    const spans = await helperClient.searchSpans(projectName, {
      maxResults: totalSpans,
      exclude: ['feedback_scores'],
      waitForAtLeast: totalSpans,
      waitForTimeout: 30,
    });

    expect(spans).toHaveLength(totalSpans);

    for (let i = 0; i < spanConfig.count; i++) {
      const span = spans.find((s: Record<string, any>) => s.name === `${spanConfig.prefix}${i}`);
      expect(span).toBeDefined();
      expect(span!.id).toBeDefined();
      expect(span!.trace_id).toBeDefined();
      expect(span!.input).toMatchObject({ input: `input-${i}` });
      expect(span!.output).toMatchObject({ output: `output-${i}` });
      expect(span!.tags).toEqual(expect.arrayContaining(spanConfig.tags!));
      expect(span!.metadata).toMatchObject(spanConfig.metadata!);
      expect(span!.feedback_scores).toBeUndefined();
    }
  });
});
