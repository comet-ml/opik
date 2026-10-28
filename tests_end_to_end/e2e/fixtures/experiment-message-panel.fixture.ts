import { test as baseTest } from './model-registry-providers.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient } from '../core/backend';

/**
 * The conversation the dataset item carries, in one column of its `data`.
 *
 * Two roles rather than one, and the System message first: `mapAndCombineMessages`
 * renders the bubbles in the order it maps them, and the viewer this replaces
 * collapsed a conversation to its LAST user message — dropping the system half
 * entirely. A single-message seed would be satisfied by that old behaviour.
 */
export const DATASET_CONVERSATION = [
  { role: 'system', content: 'You answer in exactly one word.' },
  { role: 'user', content: 'What is the capital of France?' },
] as const;

/**
 * The dataset columns that are NOT a conversation, which have to survive
 * beside it.
 *
 * Two of them, because one leftover key is a different render: the prettifier
 * collapses a single key to its bare value, so the block would carry no key
 * name for an assertion to find (and `panelRemainingKeyLines` would see no
 * CodeMirror lines at all). Two also makes "both survived" a claim a dropped
 * column can fail.
 *
 * Deliberately NOT alphabetically ordered here: the viewer sorts keys, so a
 * seed written in sorted order could not tell a sort from an accident.
 */
export const DATASET_SCALARS = { expected: 'Paris', difficulty: 3 } as const;

/** The assistant text, as the playground-shaped `{ output: ... }` carries it. */
export const OUTPUT_TEXT = 'Paris is the capital of France.';

/**
 * The output's sibling keys — everything the message mappers do NOT render.
 *
 * `splitOutputForMessages` drops only `output` for a playground-shaped payload
 * and hands the rest to the viewer below the conversation, so these three are
 * exactly what must still be on screen. Three different JSON types (number,
 * list, string) because the leftover block is rendered as YAML and a mapper
 * that dropped only the non-scalar one would otherwise pass.
 */
export const OUTPUT_SIBLINGS = {
  retrieval_score: 0.42,
  sources: ['wiki', 'atlas'],
  trace_note: 'grounded',
} as const;

/**
 * The leftover-key block the DATASET column must render, line by line.
 *
 * Alphabetical because the viewer sorts object keys (see
 * `compare-json-key-sorting.spec.ts`), and without the conversation column:
 * `messages` goes to the bubbles, so finding it here would mean the partition
 * duplicated it rather than split it.
 */
export const EXPECTED_DATASET_REMAINING_LINES = ['difficulty: 3', 'expected: Paris'];

/**
 * The leftover-key block the EXPERIMENT column must render, line by line.
 *
 * `output` is absent by design — it is the Assistant bubble — so this list is
 * also what pins it to being rendered once rather than twice.
 */
export const EXPECTED_OUTPUT_REMAINING_LINES = [
  'retrieval_score: 0.42',
  'sources:',
  '- wiki',
  '- atlas',
  'trace_note: grounded',
];

export interface ExperimentMessagePanelRef {
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  projectId: string;
  /** The dataset item whose `data` mixes a conversation with scalar columns. */
  datasetItemId: string;
  /** The trace whose output mixes a renderable message with sibling keys. */
  traceId: string;
}

export interface ExperimentMessagePanelFixtures {
  experimentMessagePanel: ExperimentMessagePanelRef;
}

/** How long the seeded experiment may take to become readable on the compare API. */
const QUERYABLE_TIMEOUT_MS = 120_000;
const POLL_MS = 2_000;

/**
 * How long a REST-written trace may take to become readable by id. Same budget
 * and same reason as `experimentImageOutput`: `createTracesBatch` is an
 * asynchronous ingest, so `GET /traces/{id}` 404s for a while after the write
 * returns.
 */
const READABLE_TIMEOUT_MS = 30_000;

const DATASET_ITEM_DATA = {
  messages: DATASET_CONVERSATION,
  ...DATASET_SCALARS,
} as unknown as Record<string, unknown>;

const TRACE_OUTPUT = { output: OUTPUT_TEXT, ...OUTPUT_SIBLINGS } as unknown as Record<
  string,
  unknown
>;

/**
 * One experiment row whose dataset item and whose output each mix a
 * conversation with keys that are not part of one (opik#8547, OPIK-7965).
 *
 * The shape is the whole point. `partitionMessageFields` decides PER KEY which
 * half of the dataset column renders where, and `splitOutputForMessages` does
 * the same for the output — and both failure modes are silent: a dropped
 * column or a dropped output sibling renders as a perfectly healthy panel. A
 * seed carrying only a conversation, or only scalars, cannot tell a partition
 * from a short-circuit.
 *
 * Seeded over REST rather than through an `evaluate()` run for the reason
 * `experimentImageOutput` gives: these rows exist to be RENDERED, and the exact
 * payloads have to reach the trace verbatim, which a task's return value cannot
 * guarantee.
 *
 * Teardown deletes the experiment, then the dataset, then the trace — none of
 * the three cascades with the project, and `global-teardown`'s run-prefix sweep
 * reaches the first two only.
 */
export const test = baseTest.extend<ExperimentMessagePanelFixtures>({
  experimentMessagePanel: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-msg-ds`;
    const experimentName = `${testNamespace}-msg-exp`;
    const experimentId = uuid7();
    const datasetItemId = uuid7();
    const traceId = uuid7();

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'conversation beside non-conversation keys in the compare row panel',
    });

    let seededTrace = false;
    try {
      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        items: [{ id: datasetItemId, data: DATASET_ITEM_DATA }],
      });

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: [
          {
            id: traceId,
            name: `${testNamespace}-msg-trace`,
            input: { input: DATASET_CONVERSATION[1].content },
            output: TRACE_OUTPUT,
          },
        ],
      });
      seededTrace = true;

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
      });

      await backendClient.createExperimentItems([
        { experimentId, datasetItemId, traceId },
      ]);

      // Both halves of the seed are proven server-side before any test opens a
      // browser. Not a formality: the panel's whole job here is to SPLIT these
      // payloads, so an ingest that reshaped one of them would leave the split
      // looking wrong — and a UI assertion over that reads as the rendering
      // defect these specs hunt rather than as the seed failure it would be.
      await waitForStoredPayload(backendClient, traceId);
      await waitForStoredDatasetItem(backendClient, dataset.id, datasetItemId);
      await waitForRows(backendClient, dataset.id, experimentId, 1);

      const ref: ExperimentMessagePanelRef = {
        datasetId: dataset.id,
        datasetName,
        experimentId,
        experimentName,
        projectId: project.id,
        datasetItemId,
        traceId,
      };

      await testInfo.attach('opik.experimentMessagePanel', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[experimentMessagePanel fixture] delete warning for ${what}:`, err);
          }
        };
        await safe(`experiment ${experimentName}`, () => backendClient.deleteExperiment(experimentId));
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTrace) {
          await safe(`trace ${traceId}`, () => backendClient.deleteTraces([traceId]));
        }
      }
    }
  },
});

/**
 * A payload as a string that does not depend on key ORDER.
 *
 * The backend stores a JSON object with its keys sorted, so a plain
 * `JSON.stringify` comparison against the seed fails on an ingest that was
 * perfectly faithful. Order is not something these seeds rely on either — the
 * viewer sorts keys for display regardless — so it is normalised away here
 * rather than asserted.
 */
const canonical = (value: unknown): string =>
  JSON.stringify(value, (_key, nested) =>
    nested !== null && typeof nested === 'object' && !Array.isArray(nested)
      ? Object.fromEntries(
          Object.entries(nested as Record<string, unknown>).sort(([a], [b]) =>
            a.localeCompare(b),
          ),
        )
      : nested,
  );

/**
 * Block until the trace reads back with the output it was written with.
 *
 * Compared as a whole object, not key by key: a sibling key the ingest dropped
 * is precisely what the spec above it would then blame the panel for.
 */
async function waitForStoredPayload(
  backendClient: BackendClient,
  traceId: string,
): Promise<void> {
  const start = Date.now();
  let seen: unknown = 'never became readable';
  while (Date.now() - start < READABLE_TIMEOUT_MS) {
    const payload = await backendClient.getTracePayload(traceId);
    if (payload !== null) {
      seen = payload.output;
      if (canonical(payload.output) === canonical(TRACE_OUTPUT)) return;
    }
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  throw new Error(
    `[experimentMessagePanel fixture] trace ${traceId} did not store its output as written ` +
      `after ${Date.now() - start}ms: ${JSON.stringify(seen)}`,
  );
}

/**
 * Block until the dataset item reads back with its conversation column intact.
 *
 * The messages column is the one the partition has to RECOGNISE, so a seed that
 * stored it as a string, or re-ordered the roles, would make the bubbles wrong
 * for a reason that has nothing to do with the code under test.
 */
async function waitForStoredDatasetItem(
  backendClient: BackendClient,
  datasetId: string,
  datasetItemId: string,
): Promise<void> {
  const start = Date.now();
  let seen: unknown = 'never became readable';
  while (Date.now() - start < READABLE_TIMEOUT_MS) {
    const items = await backendClient.listDatasetItemsWithData(datasetId);
    const item = items.find((candidate) => candidate.id === datasetItemId);
    if (item) {
      seen = item.data;
      if (canonical(item.data) === canonical(DATASET_ITEM_DATA)) return;
    }
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  throw new Error(
    `[experimentMessagePanel fixture] dataset item ${datasetItemId} did not store its data as ` +
      `written after ${Date.now() - start}ms: ${JSON.stringify(seen)}`,
  );
}

/**
 * Block until the experiment reports exactly `expected` rows.
 *
 * Exactly, not at-least: a row that has not landed yet renders as an empty
 * panel section, which is indistinguishable from a panel that dropped its keys.
 */
async function waitForRows(
  backendClient: BackendClient,
  datasetId: string,
  experimentId: string,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    seen = (await backendClient.compareItemsPage({ datasetId, experimentIds: [experimentId], size: 1 })).total;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  throw new Error(
    `[experimentMessagePanel fixture] experiment ${experimentId} reported ${seen} rows, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

export { expect } from './model-registry-providers.fixture';
