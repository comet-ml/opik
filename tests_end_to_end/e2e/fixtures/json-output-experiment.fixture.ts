import { test as baseTest } from './evaluated-thread.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * The JSON keys every seeded trace carries, under `input`, `output` and
 * `metadata` alike. Three of them are hostile to naive string interpolation of
 * the key into SQL — a dot (which is also the prefix separator), a space (which
 * the front end serialises onto the wire as `+`), and a double quote.
 */
export const JSON_SORT_KEYS = ['plain', 'key.with.dot', 'key with space', 'key"quote'] as const;

export type JsonSortKey = (typeof JSON_SORT_KEYS)[number];

/** The JSON containers whose dynamic keys the compare grid can sort by. */
export const JSON_SORT_PREFIXES = ['output', 'input', 'metadata'] as const;

export type JsonSortPrefix = (typeof JSON_SORT_PREFIXES)[number];

export interface JsonOutputExperimentRef {
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  traceIds: string[];
  /** Dataset item ids in the order the items were inserted. */
  itemIds: string[];
  /** Dataset item ids ordered by their JSON value ascending. */
  itemIdsByJsonValueAsc: string[];
  /** Dataset item ids ordered by `data.label` ascending. */
  itemIdsByLabelAsc: string[];
}

export interface JsonOutputExperimentFixtures {
  jsonOutputExperiment: JsonOutputExperimentRef;
}

/** The dataset item column the non-regression half sorts by. */
export const LABEL_COLUMN = 'label';

/**
 * Five items whose `label` order, JSON-value order and insertion order are three
 * different permutations.
 *
 * That is the whole design: if all three agreed, a backend that ignored the sort
 * field entirely — or sorted by the wrong one — would still return the expected
 * sequence, and the spec would read as coverage while proving nothing.
 *
 *   insertion:  item-3  item-1  item-5  item-2  item-4
 *   by label:   item-1  item-2  item-3  item-4  item-5
 *   by value:   item-3  item-5  item-1  item-4  item-2   (val-1 .. val-5)
 *
 * Values are strings, never numbers: `JSONExtractRaw` returns raw JSON text, so
 * a numeric value would sort lexicographically (100 < 25 < 4). That is a real
 * pre-existing wart, but it is not what this spec is about, and seeding numbers
 * here would bake it in as though it were the intended contract.
 */
const SEED_ROWS: Array<{ label: string; value: string }> = [
  { label: 'item-3', value: 'val-1' },
  { label: 'item-1', value: 'val-3' },
  { label: 'item-5', value: 'val-2' },
  { label: 'item-2', value: 'val-5' },
  { label: 'item-4', value: 'val-4' },
];

/** The same value under every key, so every key must produce the same order. */
const jsonPayload = (value: string): Record<string, unknown> =>
  Object.fromEntries(JSON_SORT_KEYS.map((key) => [key, value]));

/**
 * One experiment over five dataset items, each linked to a trace whose `input`,
 * `output` and `metadata` carry the same value under four awkward JSON keys.
 *
 * This is the shape the experiment-comparison grid sorts by `output.<key>` —
 * the path OPIK-8023 rebound as a ClickHouse query parameter. The traces are
 * created through the SDK bridge and the experiment linkage through the public
 * REST client, because the linkage (not the run) is what the grid reads.
 *
 * Teardown lives here so it survives a mid-test failure: experiments and
 * datasets do not cascade with project deletion, and traces are deleted
 * explicitly rather than relying on the project sweep.
 */
export const test = baseTest.extend<JsonOutputExperimentFixtures>({
  jsonOutputExperiment: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-json-ds`;
    const experimentName = `${testNamespace}-json-exp`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'JSON-key sorting on the experiment comparison grid',
      items: SEED_ROWS.map((row) => ({
        [LABEL_COLUMN]: row.label,
        input: `question for ${row.label}`,
        expected_output: `answer for ${row.label}`,
      })) as unknown as Array<Record<string, unknown>>,
    });

    // Read the ids back rather than generating them: the insert order is the
    // order the bridge sent them, and every expected sequence below is keyed on
    // the label, not on a position.
    const storedItems = await backendClient.getDatasetItems(dataset.id);
    const itemIdByLabel = new Map<string, string>();
    for (const item of storedItems) {
      const label = item.data[LABEL_COLUMN];
      if (typeof label !== 'string') {
        throw new Error(
          `[jsonOutputExperiment fixture] dataset item ${item.id} has no string "${LABEL_COLUMN}"`,
        );
      }
      itemIdByLabel.set(label, item.id);
    }
    if (itemIdByLabel.size !== SEED_ROWS.length) {
      throw new Error(
        `[jsonOutputExperiment fixture] expected ${SEED_ROWS.length} distinct labels, got ${itemIdByLabel.size}`,
      );
    }
    const idFor = (label: string): string => {
      const id = itemIdByLabel.get(label);
      if (!id) throw new Error(`[jsonOutputExperiment fixture] no dataset item for label ${label}`);
      return id;
    };

    // Written through the REST client rather than the SDK bridge: the bridge's
    // nested-trace helper issues a `search_traces` per call to resolve the id it
    // returns, and five of those in a row are reliably rate-limited (429, with
    // backoffs of up to a minute) — which showed up as fixture-setup timeouts,
    // not as anything to do with what is being tested. Writing the id means no
    // read-back is needed at all.
    const traceIds: string[] = [];
    for (const row of SEED_ROWS) {
      const payload = jsonPayload(row.value);
      traceIds.push(
        await backendClient.createTraceWithSource({
          id: uuid7(),
          projectName: project.name,
          name: `${testNamespace}-${row.label}`,
          source: 'sdk',
          input: payload,
          output: payload,
          metadata: payload,
        }),
      );
    }

    const experimentId = uuid7();
    await backendClient.createExperiment({
      id: experimentId,
      name: experimentName,
      datasetName,
      projectName: project.name,
    });
    await backendClient.createExperimentItems(
      SEED_ROWS.map((row, i) => ({
        experimentId,
        datasetItemId: idFor(row.label),
        traceId: traceIds[i],
      })),
    );

    const ref: JsonOutputExperimentRef = {
      datasetId: dataset.id,
      datasetName,
      experimentId,
      experimentName,
      traceIds,
      itemIds: SEED_ROWS.map((row) => idFor(row.label)),
      itemIdsByJsonValueAsc: [...SEED_ROWS]
        .sort((a, b) => a.value.localeCompare(b.value))
        .map((row) => idFor(row.label)),
      itemIdsByLabelAsc: [...SEED_ROWS]
        .sort((a, b) => a.label.localeCompare(b.label))
        .map((row) => idFor(row.label)),
    };

    await testInfo.attach('opik.jsonOutputExperiment', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[jsonOutputExperiment fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiment before the dataset it references; traces last, because they
      // do not go away with the experiment.
      await safe(`experiment ${experimentId}`, () => backendClient.deleteExperiment(experimentId));
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
      await safe('traces', () => backendClient.deleteTraces(traceIds));
    }
  },
});

export { expect } from './evaluated-thread.fixture';
