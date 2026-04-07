import { test, expect } from '../../fixtures/tracing.fixture';

test('Traces returned by search_traces contain expected fields and respect exclude option @fullregression @tracing', async ({
  helperClient,
  projectName,
  createTracesWithSpansClient,
}) => {
  test.info().annotations.push({
    type: 'description',
    description: `Tests that traces returned by search_traces contain the expected fields from the fixture and that the exclude option removes specified fields.

Steps:
1. Create traces with tags, metadata, and feedback scores (handled by fixture)
2. Search traces without exclude and verify all expected fields are returned
3. Search traces with exclude=["feedback_scores"] and verify scores are absent while other fields remain`
  });

  const { traceConfig } = createTracesWithSpansClient;

  await test.step('Search traces and verify all expected fields are returned', async () => {
    const traces = await helperClient.searchTraces(projectName, {
      maxResults: traceConfig.count,
    });

    expect(traces).toHaveLength(traceConfig.count);

    for (let i = 0; i < traceConfig.count; i++) {
      const trace = traces.find((t: Record<string, any>) => t.name === `${traceConfig.prefix}${i}`);
      expect(trace).toBeDefined();
      expect(trace!.id).toBeDefined();
      expect(trace!.input).toMatchObject({ input: `input-${i}` });
      expect(trace!.output).toMatchObject({ output: `output-${i}` });
      expect(trace!.tags).toEqual(expect.arrayContaining(traceConfig.tags!));
      expect(trace!.metadata).toMatchObject(traceConfig.metadata!);

      const scores = trace!.feedback_scores;
      expect(scores).toHaveLength(traceConfig.feedback_scores!.length);
      for (const expectedScore of traceConfig.feedback_scores!) {
        expect(scores).toEqual(
          expect.arrayContaining([expect.objectContaining({ name: expectedScore.name, value: expectedScore.value, source: 'sdk' })])
        );
      }
    }
  });

  await test.step('Search traces with exclude=["feedback_scores"] and verify scores are absent', async () => {
    const traces = await helperClient.searchTraces(projectName, {
      maxResults: traceConfig.count,
      exclude: ['feedback_scores'],
    });

    expect(traces).toHaveLength(traceConfig.count);

    for (let i = 0; i < traceConfig.count; i++) {
      const trace = traces.find((t: Record<string, any>) => t.name === `${traceConfig.prefix}${i}`);
      expect(trace).toBeDefined();
      expect(trace!.id).toBeDefined();
      expect(trace!.input).toMatchObject({ input: `input-${i}` });
      expect(trace!.output).toMatchObject({ output: `output-${i}` });
      expect(trace!.tags).toEqual(expect.arrayContaining(traceConfig.tags!));
      expect(trace!.metadata).toMatchObject(traceConfig.metadata!);
      expect(trace!.feedback_scores).toBeUndefined();
    }
  });
});
