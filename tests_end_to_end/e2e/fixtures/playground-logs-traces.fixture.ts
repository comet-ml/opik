import { expect } from '@playwright/test';
import { test as baseTest } from './source-mix-traces.fixture';
import { uuid7 } from '../core/backend';

/**
 * One seeded row of the (source, created_from) matrix the Playground logs
 * filter discriminates on.
 *
 * `createdFrom` is `null` for a row written with no `created_from` in its
 * metadata at all — the shape an SDK `evaluate()` run has — and never `''`:
 * "absent" and "some other value" reach different halves of the dictionary
 * filter, so the ref must not collapse them.
 */
export interface PlaygroundLogsTraceRef {
  id: string;
  name: string;
  source: 'sdk' | 'experiment' | 'playground' | 'optimization';
  createdFrom: string | null;
  /** Whether the shipped filter pair must list this row in the sidebar. */
  inSidebar: boolean;
}

export interface PlaygroundLogsTracesRef {
  all: PlaygroundLogsTraceRef[];
  /** Ids the sidebar must render — `source != sdk` AND `created_from = playground`. */
  expectedIds: string[];
  /** The `source = playground` row, which is all the PRE-FIX filter would have found. */
  preFixIds: string[];
  /**
   * The rows the fix ADDED: in the sidebar now, invisible under the pre-fix
   * filter. These are the playground runs against a dataset or a test suite,
   * which log `source=experiment`, plus the optimization-sourced one.
   */
  addedByFixIds: string[];
  /** The SDK `evaluate()`-shaped row, which must stay out under either filter. */
  sdkEvaluateId: string;
  /** `source=sdk` WITH `created_from=playground` — excluded by the source half alone. */
  sdkFromPlaygroundId: string;
}

export interface PlaygroundLogsTracesFixtures {
  playgroundLogsTraces: PlaygroundLogsTracesRef;
}

/**
 * The matrix, one row per cell that distinguishes the two halves of the filter.
 *
 * Chosen so that EITHER half going missing changes the answer, which is what
 * makes the spec able to say both clauses earn their place:
 *
 *  - `sdk` + `created_from=playground` is excluded by the source clause only.
 *  - `experiment` with no `created_from` is excluded by the metadata clause
 *    only. This is the SDK `evaluate()` shape, and the row that makes "too
 *    broad" a detectable failure rather than a theoretical one.
 *  - `experiment` + `created_from=playground` is the row opik#8799 exists to
 *    ADMIT: a playground run against a dataset or a test suite is logged
 *    `source=experiment`, so the pre-fix `source = playground` filter hid it.
 *  - `optimization` + `created_from=playground` is a second non-`playground`
 *    source, so the admitted set cannot be mistaken for "experiment was
 *    special-cased".
 *
 * Seeded over REST rather than by running the Playground against a model: the
 * subject is which rows the filter returns, and a real run would make the row
 * set depend on a provider answering.
 */
const MATRIX: ReadonlyArray<Omit<PlaygroundLogsTraceRef, 'id' | 'name'> & { label: string }> = [
  { label: 'playground-free-mode', source: 'playground', createdFrom: 'playground', inSidebar: true },
  { label: 'experiment-dataset-run', source: 'experiment', createdFrom: 'playground', inSidebar: true },
  { label: 'optimization-run', source: 'optimization', createdFrom: 'playground', inSidebar: true },
  { label: 'sdk-evaluate', source: 'experiment', createdFrom: null, inSidebar: false },
  { label: 'sdk-plain', source: 'sdk', createdFrom: null, inSidebar: false },
  { label: 'sdk-claiming-playground', source: 'sdk', createdFrom: 'playground', inSidebar: false },
];

/**
 * The (source, created_from) matrix the Playground logs sidebar filters on
 * (opik#8799), seeded into a fresh project.
 *
 * A fixture rather than inline seeding for the reason every aggregate-or-set
 * assertion needs one: the sidebar renders whatever the filter returned, so a
 * row that silently failed to land looks exactly like a row the filter
 * correctly excluded. The population is proven readable here, before a browser
 * opens.
 *
 * No teardown of its own: traces cascade with the project the `project`
 * fixture owns and deletes. Nothing else is created — no dataset, no provider
 * key, no run — so there is nothing outside that cascade.
 */
export const test = baseTest.extend<PlaygroundLogsTracesFixtures>({
  playgroundLogsTraces: async ({ project, backendClient }, use, testInfo) => {
    const all: PlaygroundLogsTraceRef[] = [];
    for (const cell of MATRIX) {
      const id = uuid7();
      const name = `playground-logs-${cell.label}`;
      await backendClient.createTraceWithSource({
        id,
        projectName: project.name,
        name,
        source: cell.source,
        endTime: new Date(),
        ...(cell.createdFrom === null ? {} : { metadata: { created_from: cell.createdFrom } }),
      });
      all.push({
        id,
        name,
        source: cell.source,
        createdFrom: cell.createdFrom,
        inSidebar: cell.inSidebar,
      });
    }

    const byLabel = (label: string): PlaygroundLogsTraceRef => {
      const found = all.find((t) => t.name === `playground-logs-${label}`);
      if (!found) {
        throw new Error(`[playground-logs-traces fixture] no seeded row labelled '${label}'`);
      }
      return found;
    };

    await expect
      .poll(
        async () =>
          (await backendClient.searchTraceIdsPage({ projectId: project.id, size: 200 })).total,
        { timeout: 90_000, intervals: [1_000, 2_000, 5_000] },
      )
      .toBe(all.length);

    const expectedIds = all.filter((t) => t.inSidebar).map((t) => t.id);
    const preFixIds = all.filter((t) => t.source === 'playground').map((t) => t.id);

    await testInfo.attach('opik.playground-logs-traces', {
      body: JSON.stringify({ projectId: project.id, all }, null, 2),
      contentType: 'application/json',
    });

    await use({
      all,
      expectedIds,
      preFixIds,
      addedByFixIds: expectedIds.filter((id) => !preFixIds.includes(id)),
      sdkEvaluateId: byLabel('sdk-evaluate').id,
      sdkFromPlaygroundId: byLabel('sdk-claiming-playground').id,
    });
  },
});

export { expect } from './source-mix-traces.fixture';
