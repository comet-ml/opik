import { test as baseTest, expect } from './bystander.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { BackendFilter } from '../core/backend';

export interface QuickFilterSpanRef {
  id: string;
  name: string;
}

export interface QuickFilterTraceRef {
  id: string;
  name: string;
  /** `alpha` | `beta` | `gamma` — also the span's `span_owner` metadata value. */
  suffix: string;
  /** Value of the trace's `trace_env` metadata attribute. */
  traceEnv: string;
  spans: { retrieve: QuickFilterSpanRef; generate: QuickFilterSpanRef };
}

export interface QuickFilterAttributesRef {
  traces: QuickFilterTraceRef[];
  /** Span metadata key/value the quick-filter click hands off (`stage: retrieve`). */
  spanStage: { key: string; value: string };
  /** Second span metadata key, used to prove a handoff appends rather than overwrites. */
  spanOwner: { key: string; value: string };
  /** Trace metadata key/value, for the mirror direction (Spans -> Traces). */
  traceEnvAttribute: { key: string; value: string };
  /** Every seeded span id, in no particular order. */
  allSpanIds: string[];
  /** The span ids a `metadata.stage contains retrieve` filter must return — and only these. */
  stageSpanIds: string[];
  /** The one span id that survives `stage contains retrieve` AND `span_owner contains <value>`. */
  stageAndOwnerSpanId: string;
  /** The trace ids a `metadata.trace_env contains prod` filter must return — and only these. */
  traceEnvTraceIds: string[];
}

export interface QuickFilterAttributesFixtures {
  quickFilterAttributes: QuickFilterAttributesRef;
}

const STAGE_KEY = 'stage';
const STAGE_VALUE = 'retrieve';
const OTHER_STAGE_VALUE = 'generate';
const OWNER_KEY = 'span_owner';
const TRACE_ENV_KEY = 'trace_env';
const TRACE_ENV_VALUE = 'prod';

/**
 * Three traces, each with a `retrieve` and a `generate` child span. Attributes
 * are staggered so every filter the quick-filter handoff can write narrows to a
 * *different*, non-trivial subset — a handoff that dropped its row, or wrote it
 * against the wrong field, would return everything and fail the assertion
 * rather than quietly passing:
 *
 *   span   metadata.stage      contains "retrieve" -> 3 of 6 spans
 *   span   metadata.span_owner contains "beta"     -> 1 of those 3
 *   trace  metadata.trace_env  contains "prod"     -> 2 of 3 traces
 *
 * `span_owner` is what makes the append case assertable: it is a key the first
 * handoff knows nothing about, so a second handoff that overwrote instead of
 * appending would leave 3 rows in the table rather than 1.
 */
const SEEDS: Array<{ suffix: string; traceEnv: string }> = [
  { suffix: 'alpha', traceEnv: TRACE_ENV_VALUE },
  { suffix: 'beta', traceEnv: TRACE_ENV_VALUE },
  { suffix: 'gamma', traceEnv: 'staging' },
];

/** The seed whose `span_owner` the append test hands off as its second filter. */
const OWNER_VALUE = 'beta';

const metadataFilter = (key: string, value: string): BackendFilter => ({
  field: 'metadata',
  type: 'dictionary',
  operator: 'contains',
  key,
  value,
});

export const test = baseTest.extend<QuickFilterAttributesFixtures>({
  quickFilterAttributes: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const traces: QuickFilterTraceRef[] = [];

    for (const seed of SEEDS) {
      const traceName = `${testNamespace}-${seed.suffix}`;
      const spanNames = {
        retrieve: `${traceName}-${STAGE_VALUE}`,
        generate: `${traceName}-${OTHER_STAGE_VALUE}`,
      };

      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: traceName,
        input: { query: `question from ${seed.suffix}` },
        output: { answer: `answer from ${seed.suffix}` },
        metadata: { [TRACE_ENV_KEY]: seed.traceEnv },
        spans: [
          {
            name: spanNames.retrieve,
            type: 'general',
            input: { step: STAGE_VALUE },
            output: { done: true },
            metadata: { [STAGE_KEY]: STAGE_VALUE, [OWNER_KEY]: seed.suffix },
          },
          {
            name: spanNames.generate,
            type: 'general',
            input: { step: OTHER_STAGE_VALUE },
            output: { done: true },
            metadata: { [STAGE_KEY]: OTHER_STAGE_VALUE, [OWNER_KEY]: seed.suffix },
          },
        ],
      });

      // The bridge answers with the trace id and a span count but no span ids,
      // and these specs assert on exact span ids in the Spans table — so resolve
      // them by the names chosen above.
      const spans = await backendClient.listSpans({
        projectId: project.id,
        traceId: created.id,
      });
      const byName = (name: string): QuickFilterSpanRef => {
        const matches = spans.filter((s) => s.name === name);
        if (matches.length !== 1) {
          throw new Error(
            `[quickFilterAttributes fixture] expected exactly 1 span named "${name}" under trace ${created.id}, got ${matches.length}`,
          );
        }
        return matches[0];
      };

      traces.push({
        id: created.id,
        name: created.name,
        suffix: seed.suffix,
        traceEnv: seed.traceEnv,
        spans: { retrieve: byName(spanNames.retrieve), generate: byName(spanNames.generate) },
      });
    }

    const stageSpanIds = traces.map((t) => t.spans.retrieve.id);
    const ownerTrace = traces.find((t) => t.suffix === OWNER_VALUE);
    if (!ownerTrace) {
      throw new Error(`[quickFilterAttributes fixture] no seed with suffix "${OWNER_VALUE}"`);
    }

    const ref: QuickFilterAttributesRef = {
      traces,
      spanStage: { key: STAGE_KEY, value: STAGE_VALUE },
      spanOwner: { key: OWNER_KEY, value: OWNER_VALUE },
      traceEnvAttribute: { key: TRACE_ENV_KEY, value: TRACE_ENV_VALUE },
      allSpanIds: traces.flatMap((t) => [t.spans.retrieve.id, t.spans.generate.id]),
      stageSpanIds,
      stageAndOwnerSpanId: ownerTrace.spans.retrieve.id,
      traceEnvTraceIds: traces.filter((t) => t.traceEnv === TRACE_ENV_VALUE).map((t) => t.id),
    };

    // Prove server-side that the seed really discriminates before any spec
    // opens a browser over it. A UI assertion sitting on a fixture whose
    // metadata never landed is a test that cannot fail — and it would read as
    // coverage forever. Polled because the write is eventually consistent.
    await expect
      .poll(
        async () =>
          (
            await backendClient.listSpans({
              projectId: project.id,
              filters: [metadataFilter(STAGE_KEY, STAGE_VALUE)],
            })
          )
            .map((s) => s.id)
            .sort(),
        { timeout: 30_000, message: 'seeded spans filtered by metadata.stage' },
      )
      .toEqual([...stageSpanIds].sort());

    const stageAndOwner = await backendClient.listSpans({
      projectId: project.id,
      filters: [metadataFilter(STAGE_KEY, STAGE_VALUE), metadataFilter(OWNER_KEY, OWNER_VALUE)],
    });
    expect(stageAndOwner.map((s) => s.id)).toEqual([ref.stageAndOwnerSpanId]);

    await expect
      .poll(
        async () =>
          (
            await backendClient.listTraceIds({
              projectId: project.id,
              filters: [metadataFilter(TRACE_ENV_KEY, TRACE_ENV_VALUE)],
            })
          ).sort(),
        { timeout: 30_000, message: 'seeded traces filtered by metadata.trace_env' },
      )
      .toEqual([...ref.traceEnvTraceIds].sort());

    await testInfo.attach('opik.quickFilterAttributes', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    // Deleting the project does not take its traces with it, and the
    // run-prefix sweep in global-teardown only knows about experiments,
    // datasets and projects — so the traces (and with them their spans) are
    // deleted here, where teardown runs on pass, fail and timeout alike.
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(traces.map((t) => t.id));
      } catch (err) {
        console.warn('[quickFilterAttributes fixture] trace delete warning:', err);
      }
    }
  },
});

export { expect } from './bystander.fixture';
