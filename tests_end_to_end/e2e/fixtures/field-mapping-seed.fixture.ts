import { test as baseTest, expect } from './prompt-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * The trace's own sections. Every value is a distinct, recognisable literal
 * because the whole subject is *which* one ends up under *which* dataset-item
 * key: a mapping that quietly read the wrong section would still produce a
 * perfectly ordinary-looking item, and only a value that could not have come
 * from anywhere else makes that visible.
 *
 * `noise` exists so `input` is an object with more than one key — it is what
 * makes "the item carries the trace's whole input" and "the item carries
 * `input.input_text`" two different answers.
 */
export const FM_TRACE_INPUT = {
  input_text: 'fm-trace-input-text',
  noise: 'fm-trace-noise',
} as const;
export const FM_TRACE_OUTPUT = { answer: 'fm-trace-output-answer' } as const;
export const FM_TRACE_METADATA = { mkey: 'fm-trace-metadata-value' } as const;
export const FM_TRACE_TAGS = ['fm-source-tag'] as const;

/** The one LLM span under the trace, for the `from-spans` half. */
export const FM_SPAN_INPUT = { input_text: 'fm-span-input-text' } as const;
export const FM_SPAN_OUTPUT = { answer: 'fm-span-output-answer' } as const;
export const FM_SPAN_MODEL = 'gpt-4.1';
export const FM_SPAN_PROVIDER = 'openai';

export interface FieldMappingSeedRef {
  traceId: string;
  traceName: string;
  spanId: string;
  spanName: string;
  /**
   * The trace's `metadata` and `tags` **as stored**, not as sent.
   *
   * The Python SDK adds its own keys on the way through — a trace with an LLM
   * span comes back carrying `metadata.providers` — so `FM_TRACE_METADATA` is
   * what the mapped `metadata.mkey` path reads, while this is what a spec
   * comparing the whole enriched section has to compare against. Hard-coding
   * the SDK's additions here instead would encode the bridge's behaviour into
   * an assertion about Opik's.
   */
  traceMetadata: Record<string, unknown>;
  traceTags: string[];
  /** An EMPTY `DATASET`-type dataset in the project — the mapped write's target. */
  datasetId: string;
  datasetName: string;
  /** An EMPTY `TEST_SUITE`-type dataset in the same project. */
  testSuiteId: string;
  testSuiteName: string;
}

export interface FieldMappingSeedFixtures {
  fieldMappingSeed: FieldMappingSeedRef;
}

/**
 * One trace carrying every section a field mapping can read, one LLM span under
 * it, and two empty datasets to write mapped items into — a `DATASET` and a
 * `TEST_SUITE`.
 *
 * **The datasets are empty on purpose.** Each spec asserts the created item is
 * the *only* item in its dataset, which is what turns "my mapped row looks
 * right" into "the endpoint wrote exactly one row and nothing else". The shared
 * `dataset` fixture seeds three items, so it cannot stand in.
 *
 * Seeded through `createNestedTrace` rather than the REST writes: it is the one
 * seeder that sets `tags` and `metadata` on the trace and attaches a span in a
 * single call, and — unlike the bridge's `@opik.track` route — it emits exactly
 * the spans it is given, with no extra root span. A stray span would change the
 * enriched `spans` array and so the very key set these specs compare.
 *
 * Both datasets are deleted here rather than in the tests: datasets do not
 * cascade with project deletion, and a spec that fails mid-assertion must not
 * leave one behind. The trace is deleted for the same reason
 * `cached-token-spans` deletes its own.
 */
export const test = baseTest.extend<FieldMappingSeedFixtures>({
  fieldMappingSeed: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const traceName = `${testNamespace}-fm-trace`;
    const spanName = `${testNamespace}-fm-span`;
    const datasetName = `${testNamespace}-fm-ds`;
    const testSuiteName = `${testNamespace}-fm-suite`;

    let traceId: string | null = null;
    let datasetId: string | null = null;
    let testSuiteId: string | null = null;

    try {
      const trace = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: traceName,
        input: { ...FM_TRACE_INPUT },
        output: { ...FM_TRACE_OUTPUT },
        metadata: { ...FM_TRACE_METADATA },
        tags: [...FM_TRACE_TAGS],
        spans: [
          {
            name: spanName,
            type: 'llm',
            input: { ...FM_SPAN_INPUT },
            output: { ...FM_SPAN_OUTPUT },
            model: FM_SPAN_MODEL,
            provider: FM_SPAN_PROVIDER,
            // No `usage`: an LLM span that reports none leaves the trace's
            // rolled-up `usage` empty, and the enrichment mapper drops an empty
            // one. That is what keeps the enriched key set — which these specs
            // compare exactly — decided by the seed rather than by whatever the
            // backend happened to aggregate.
          },
        ],
      });
      traceId = trace.id;

      // The bridge confirms the trace is queryable before it answers, but the
      // project-scoped span read is a separate index. Poll rather than assume:
      // a spec that opened with a missing span id would fail somewhere far from
      // the cause.
      await expect
        .poll(
          async () =>
            (await backendClient.listSpanRefs({ projectId: project.id, traceId: trace.id }))
              .length,
          { timeout: 60_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(1);
      const spans = await backendClient.listSpanRefs({
        projectId: project.id,
        traceId: trace.id,
      });

      // What the trace actually holds, and a hard check that the two sections
      // the mappings read from really landed. Without it a bridge that dropped
      // `metadata` would leave every spec comparing an absent section against
      // an absent section — coverage that cannot fail.
      //
      // Both checks compare VALUES, not shapes. `traceMetadata` and `traceTags`
      // are handed to the specs as the oracle for the enriched `metadata` and
      // `tags` fields, so a seed that stored `['wrong-tag']` would otherwise
      // satisfy `item.data.tags === fieldMappingSeed.traceTags` against itself
      // — a comparison of the bridge with the bridge, which no Opik defect can
      // fail. Pinning them here is what keeps the exported oracle a statement
      // about what was asked for.
      const stored = await backendClient.getTracePayload(trace.id);
      const traceMetadata = (stored?.metadata ?? {}) as Record<string, unknown>;
      const traceTags = stored?.tags ?? [];
      for (const [key, expected] of Object.entries(FM_TRACE_METADATA)) {
        if (traceMetadata[key] !== expected) {
          throw new Error(
            `[fieldMappingSeed fixture] trace ${trace.id} stored metadata.${key}=${JSON.stringify(traceMetadata[key])}, expected ${JSON.stringify(expected)}: ${JSON.stringify(traceMetadata)}`,
          );
        }
      }
      // Order included: the specs compare the enriched `tags` array with
      // `toEqual`, which is order-sensitive, so the oracle has to be too.
      if (
        traceTags.length !== FM_TRACE_TAGS.length ||
        traceTags.some((tag, i) => tag !== FM_TRACE_TAGS[i])
      ) {
        throw new Error(
          `[fieldMappingSeed fixture] trace ${trace.id} stored tags ${JSON.stringify(traceTags)}, expected ${JSON.stringify(FM_TRACE_TAGS)}`,
        );
      }

      const dataset = await sdkClient.python.createDataset({
        project_name: project.name,
        name: datasetName,
        description: `field-mapping target seeded by ${testInfo.title}`,
      });
      datasetId = dataset.id;

      const testSuite = await sdkClient.python.createTestSuite({
        project_name: project.name,
        name: testSuiteName,
        description: `field-mapping test-suite target seeded by ${testInfo.title}`,
      });
      testSuiteId = testSuite.id;

      const ref: FieldMappingSeedRef = {
        traceId: trace.id,
        traceName: trace.name,
        spanId: spans[0].id,
        spanName: spans[0].name,
        traceMetadata,
        traceTags,
        datasetId: dataset.id,
        datasetName: dataset.name,
        testSuiteId: testSuite.id,
        testSuiteName: testSuite.name,
      };

      await testInfo.attach('opik.fieldMappingSeed', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        // Swallow-and-warn, never rethrow: a delete that failed here would
        // replace whichever seeding or assertion error actually broke the test.
        // Test suites share storage with datasets, so both go through
        // `deleteDataset`.
        for (const [id, label] of [
          [datasetId, datasetName],
          [testSuiteId, testSuiteName],
        ] as const) {
          if (!id) continue;
          try {
            await backendClient.deleteDataset(id);
          } catch (err) {
            console.warn(`[fieldMappingSeed fixture] delete warning for ${label}:`, err);
          }
        }
        if (traceId) {
          try {
            await backendClient.deleteTraces([traceId]);
          } catch (err) {
            console.warn(`[fieldMappingSeed fixture] delete warning for ${traceName}:`, err);
          }
        }
      }
    }
  },
});

export { expect };
