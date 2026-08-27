import { test as baseTest } from './json-output-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * The backend's two independent truncation limits, both of which the compare
 * grid's `truncate=true` read applies and its export must not.
 *
 * They are deliberately different mechanisms, and a value that trips one does
 * not necessarily trip the other:
 *  - the experiment item's trace `output` is slimmed by
 *    `TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH` (1 000) and gains a
 *    literal `...` suffix, so a truncated value is 1 003 characters long;
 *  - the dataset item's `data` map is cut by
 *    `responseFormatting.truncationSize` (10 001, `OPIK_RESPONSE_TRUNCATION_
 *    CHAR_LIMIT`) with **no** suffix, so a truncated value is exactly 10 001
 *    characters and looks perfectly ordinary.
 */
export const SLIM_STRING_MAX_LENGTH = 1_000;
export const SLIM_TRUNCATION_SUFFIX = '...';
export const DATASET_TRUNCATION_SIZE = 10_001;

/** The three rows the fixture seeds, named for where they sit against those limits. */
export type LongValueLabel = 'short' | 'long' | 'huge';

export interface LongValueRowRef {
  label: LongValueLabel;
  itemId: string;
  traceId: string;
  /** The dataset item's `input`, as seeded — whole. */
  input: string;
  /** The linked trace's `output.output`, as seeded — whole. */
  output: string;
}

export interface LongValueExperimentRef {
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  /** All three rows, in insertion order. */
  rows: LongValueRowRef[];
  /** Under every limit: neither its input nor its output is ever truncated. */
  short: LongValueRowRef;
  /** Output over the 1 000-char slim limit; input under the 10 001-char dataset limit. */
  long: LongValueRowRef;
  /** Both input and output over the 10 001-char dataset limit. */
  huge: LongValueRowRef;
}

export interface LongValueExperimentFixtures {
  longValueExperiment: LongValueExperimentRef;
}

/**
 * A deterministic string of at least `minLength` characters.
 *
 * Every word is numbered, so a truncated copy and a whole one differ visibly at
 * the tail rather than only in length — and the `-END` marker means "did this
 * value arrive complete?" can be asked of a CSV by substring alone.
 *
 * Deliberately free of commas, quotes and newlines: the CSV assertions compare
 * the seeded text against the file as written, and a value the CSV writer had
 * to quote or escape would no longer appear in it verbatim.
 */
const filler = (marker: string, minLength: number): string => {
  const parts = [`${marker}-START`];
  let length = parts[0].length;
  for (let i = 0; length < minLength; i++) {
    const word = `${marker}-w${String(i).padStart(5, '0')}`;
    parts.push(word);
    length += word.length + 1;
  }
  parts.push(`${marker}-END`);
  return parts.join(' ');
};

/**
 * The seed. Lengths are chosen to straddle both limits — see the constants
 * above — and the fixture asserts they really do before the values are written,
 * because a seed that fell under a limit would make every downstream assertion
 * pass against a build with the truncation bug still in it.
 */
const SEED: Array<{ label: LongValueLabel; input: string; output: string }> = [
  {
    label: 'short',
    input: 'short-input-value',
    output: 'short-output-value',
  },
  {
    label: 'long',
    input: filler('LONGIN', 2_000),
    output: filler('LONGOUT', 2_400),
  },
  {
    label: 'huge',
    input: filler('HUGEIN', 11_500),
    output: filler('HUGEOUT', 11_500),
  },
];

/**
 * One experiment over three dataset items whose values straddle the backend's
 * two truncation limits.
 *
 * This is the shape OPIK-8125 is about: the comparison grid reads these rows
 * truncated (so the long values render cut) while Export must read them whole.
 * Both halves have to be seeded — a dataset input alone tops out at the 10 001
 * limit and an experiment output alone at 1 000, and the fix has to hold for
 * both.
 *
 * Traces are written through the REST client rather than the SDK bridge for the
 * same reason `jsonOutputExperiment` does it: the bridge's nested-trace helper
 * resolves the id it returns with a `search_traces` per call, which is both
 * slower and rate-limited, and writing the id means no read-back is needed.
 *
 * Teardown lives here so it survives a mid-test failure: experiments and
 * datasets do not cascade with project deletion, and traces do not go away with
 * the experiment.
 */
export const test = baseTest.extend<LongValueExperimentFixtures>({
  longValueExperiment: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const datasetName = `${testNamespace}-trunc-ds`;
    const experimentName = `${testNamespace}-trunc-exp`;

    // Assert the seed before writing it: these lengths are the whole reason the
    // fixture discriminates, and a filler that came up short would leave a spec
    // that cannot fail.
    const requireSeed = (condition: boolean, why: string): void => {
      if (!condition) throw new Error(`[longValueExperiment fixture] ${why}`);
    };
    const seedFor = (label: LongValueLabel): { input: string; output: string } => {
      const row = SEED.find((candidate) => candidate.label === label);
      if (!row) throw new Error(`[longValueExperiment fixture] no seed row labelled ${label}`);
      return row;
    };
    const short = seedFor('short');
    const long = seedFor('long');
    const huge = seedFor('huge');
    requireSeed(
      short.input.length < SLIM_STRING_MAX_LENGTH && short.output.length < SLIM_STRING_MAX_LENGTH,
      'the short row must sit under every truncation limit, so it can prove a whole value is left alone',
    );
    for (const [label, row] of [
      ['long', long],
      ['huge', huge],
    ] as const) {
      requireSeed(
        row.output.length > SLIM_STRING_MAX_LENGTH,
        `the ${label} output is ${row.output.length} chars — it must exceed the ` +
          `${SLIM_STRING_MAX_LENGTH}-char slim limit to be truncated at all`,
      );
    }
    requireSeed(
      long.input.length < DATASET_TRUNCATION_SIZE,
      `the long input is ${long.input.length} chars — it must stay under the ` +
        `${DATASET_TRUNCATION_SIZE}-char dataset limit, so it can show that limit is not applied blindly`,
    );
    requireSeed(
      huge.input.length > DATASET_TRUNCATION_SIZE && huge.output.length > DATASET_TRUNCATION_SIZE,
      `the huge row must exceed the ${DATASET_TRUNCATION_SIZE}-char dataset limit on both axes`,
    );

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'Export of experiment comparison rows must not truncate',
      items: SEED.map((row) => ({
        label: row.label,
        input: row.input,
        expected_output: row.label,
      })) as unknown as Array<Record<string, unknown>>,
    });

    // Read the ids back rather than generating them, and key them by the label
    // column so nothing downstream depends on the insertion order.
    const storedItems = await backendClient.getDatasetItems(dataset.id);
    const itemIdByLabel = new Map<string, string>();
    for (const item of storedItems) {
      const label = item.data.label;
      if (typeof label !== 'string') {
        throw new Error(`[longValueExperiment fixture] dataset item ${item.id} has no string "label"`);
      }
      itemIdByLabel.set(label, item.id);
    }
    if (itemIdByLabel.size !== SEED.length) {
      throw new Error(
        `[longValueExperiment fixture] expected ${SEED.length} distinct labels, got ${itemIdByLabel.size}`,
      );
    }

    const rows: LongValueRowRef[] = [];
    for (const row of SEED) {
      const itemId = itemIdByLabel.get(row.label);
      if (!itemId) throw new Error(`[longValueExperiment fixture] no dataset item for label ${row.label}`);
      const traceId = await backendClient.createTraceWithSource({
        id: uuid7(),
        projectName: project.name,
        name: `${testNamespace}-${row.label}`,
        source: 'sdk',
        input: { input: row.input },
        output: { output: row.output },
      });
      rows.push({ label: row.label, itemId, traceId, input: row.input, output: row.output });
    }

    const experimentId = uuid7();
    await backendClient.createExperiment({
      id: experimentId,
      name: experimentName,
      datasetName,
      projectName: project.name,
    });
    await backendClient.createExperimentItems(
      rows.map((row) => ({ experimentId, datasetItemId: row.itemId, traceId: row.traceId })),
    );

    const byLabel = (label: LongValueLabel): LongValueRowRef => {
      const row = rows.find((candidate) => candidate.label === label);
      if (!row) throw new Error(`[longValueExperiment fixture] no seeded row labelled ${label}`);
      return row;
    };

    const ref: LongValueExperimentRef = {
      datasetId: dataset.id,
      datasetName,
      experimentId,
      experimentName,
      rows,
      short: byLabel('short'),
      long: byLabel('long'),
      huge: byLabel('huge'),
    };

    // Attach the shape, not the payloads: the seeded strings run to ~12 000
    // characters each and would bury the rest of the trace.
    await testInfo.attach('opik.longValueExperiment', {
      body: JSON.stringify(
        {
          datasetId: ref.datasetId,
          datasetName,
          experimentId,
          experimentName,
          rows: rows.map((row) => ({
            label: row.label,
            itemId: row.itemId,
            traceId: row.traceId,
            inputLength: row.input.length,
            outputLength: row.output.length,
          })),
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[longValueExperiment fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiment before the dataset it references; traces last, because they
      // do not go away with the experiment.
      await safe(`experiment ${experimentId}`, () => backendClient.deleteExperiment(experimentId));
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
      await safe('traces', () => backendClient.deleteTraces(rows.map((row) => row.traceId)));
    }
  },
});

export { expect } from './json-output-experiment.fixture';
