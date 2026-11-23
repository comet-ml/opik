import { test as baseTest } from './same-name-datasets.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import type { BackendClient } from '../core/backend';

/** One item source and the experiments recorded against it. */
export interface FilterChipSourceRef {
  datasetId: string;
  datasetName: string;
  /** This source's experiments, in seed order. */
  experimentIds: string[];
}

export interface ExperimentsFilterChipRef {
  projectId: string;
  projectName: string;
  promptId: string;
  promptName: string;
  promptVersionId: string;
  /**
   * The BULK source — 12 experiments. Never the narrowing target: it is what a
   * filter must exclude, and its rows are what tell a narrowing apart from a
   * page that simply rendered fewer rows.
   */
  bulk: FilterChipSourceRef;
  /** The narrowing TARGET — 2 experiments. */
  target: FilterChipSourceRef;
  /** Both sources' experiments: the whole population either page lists unfiltered. */
  allExperimentIds: string[];
}

export interface ExperimentsFilterChipFixtures {
  experimentsFilterChip: ExperimentsFilterChipRef;
}

/** Experiments on the bulk source. */
const BULK_COUNT = 12;
/** Experiments on the target source. */
const TARGET_COUNT = 2;

/**
 * Two item sources under one project and one prompt version, holding fourteen
 * experiments, for the "+ Add filter" chip opik#9691 (OPIK-9252) put on the
 * Experiments page and the prompt detail page's Experiments tab.
 *
 * **Why two sources with DIFFERENT counts.** The claim under test is that
 * picking an item source narrows the list to that source's experiments. Three
 * numbers have to be pairwise distinct for the assertion to be able to fail:
 * the unfiltered population (14), the target's rows (2) and the bulk source's
 * (12). Two sources of equal size would make "narrowed to the right source"
 * indistinguishable from "narrowed to the wrong one", and a target holding
 * every experiment would make it indistinguishable from no filter at all. The
 * fixture asserts that distinctness rather than trusting these constants, so
 * editing them cannot quietly make the specs vacuous.
 *
 * **Why the prompt version.** The same shared `FiltersButton` is opted in at
 * two call sites, and the second is the prompt detail page's Experiments tab,
 * which queries by PROMPT rather than by project. Linking every experiment to
 * one version is what puts the identical population on both pages, so the two
 * specs assert the same narrowing through two different reads instead of two
 * differently-seeded ones.
 *
 * **No traces and no items.** Neither page's read needs them — an experiment is
 * tied to its project by `createExperiment` directly, not through its items
 * (which is why the sibling `experimentsTabSplit` fixture also leaves most of
 * its experiments itemless) — and the subject is which ROWS a filter selects,
 * not what any row's cells contain. Seeding 14 traces and 14 experiment items
 * for nothing would only add ingestion lag the spec would then have to wait on.
 *
 * **The precondition check is load-bearing.** Before any browser opens, the
 * fixture reads back all four populations the specs rest on — each source's own
 * experiments, the project's Datasets-tab population, the prompt's population,
 * and the prompt's population ALREADY NARROWED to the target source — and
 * refuses a seed whose numbers are not the ones the specs assert. The narrowed
 * read is the one that matters most: it proves the server really does answer
 * two rows for the filter the UI is about to build, so a UI assertion of "two
 * rows" cannot pass over a filter that was never applied.
 *
 * Teardown deletes the experiments, the datasets and the prompt. None of them
 * cascades with the project, and `global-teardown`'s run-prefix sweep knows
 * nothing about prompts and only reaches the rest six hours later, which is no
 * use to a spec asserting "exactly these fourteen rows".
 */
export const test = baseTest.extend<ExperimentsFilterChipFixtures>({
  experimentsFilterChip: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const promptName = `${testNamespace}-prompt`;
    const version = await backendClient.createPromptVersion({
      name: promptName,
      template: 'Answer {{question}}.',
      projectId: project.id,
      changeDescription: 'seeded for the Experiments Add filter chip',
    });
    // Never the id `createPromptVersion` answers with: that is the VERSION id,
    // and `DELETE /v1/private/prompts/{id}` 404s for it, so teardown registered
    // with it would leak behind the `catch`.
    const promptId = await backendClient.findPromptIdByName(promptName, project.id);
    if (!promptId) {
      throw new Error(`[experimentsFilterChip fixture] no prompt id resolved for '${promptName}'`);
    }

    const datasetIds: string[] = [];
    const experimentIds: string[] = [];

    const seedSource = async (
      suffix: string,
      count: number,
    ): Promise<FilterChipSourceRef> => {
      const datasetName = `${testNamespace}-${suffix}`;
      const created = await sdkClient.python.createDataset({
        project_name: project.name,
        name: datasetName,
        description: 'Experiments Add filter chip',
      });
      datasetIds.push(created.id);

      const ids: string[] = [];
      for (let index = 0; index < count; index += 1) {
        const id = uuid7();
        await backendClient.createExperiment({
          id,
          name: `${testNamespace}-${suffix}-exp${index}`,
          datasetName,
          projectName: project.name,
          // Explicit rather than defaulted: the Datasets tab selects its rows
          // with `evaluation_method != evaluation_suite` (opik#9611), so the
          // whole population only lands on the tab these specs open if every
          // experiment really is a dataset-method one.
          evaluationMethod: 'dataset',
          // What puts the same fourteen on the prompt detail page's Experiments
          // tab, which queries by prompt rather than by project.
          promptVersionIds: [version.id],
        });
        experimentIds.push(id);
        ids.push(id);
      }
      return { datasetId: created.id, datasetName, experimentIds: ids };
    };

    try {
      const bulk = await seedSource('src-bulk', BULK_COUNT);
      const target = await seedSource('src-target', TARGET_COUNT);
      const allExperimentIds = [...bulk.experimentIds, ...target.experimentIds];

      assertCountsDiscriminate(bulk, target, allExperimentIds.length);
      await assertSeedReadsBack(backendClient, {
        projectId: project.id,
        promptId,
        bulk,
        target,
        allExperimentIds,
      });

      const ref: ExperimentsFilterChipRef = {
        projectId: project.id,
        projectName: project.name,
        promptId,
        promptName,
        promptVersionId: version.id,
        bulk,
        target,
        allExperimentIds,
      };

      await testInfo.attach('opik.experimentsFilterChip', {
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
            console.warn(`[experimentsFilterChip fixture] delete warning for ${what}:`, err);
          }
        };
        for (const id of experimentIds) {
          await safe(`experiment ${id}`, () => backendClient.deleteExperiment(id));
        }
        for (const id of datasetIds) {
          await safe(`dataset ${id}`, () => backendClient.deleteDataset(id));
        }
        await safe(`prompt ${promptName}`, () => backendClient.deletePrompt(promptId));
      }
    }
  },
});

/**
 * The seed's whole premise, stated rather than trusted: the unfiltered
 * population, the target's rows and the bulk source's rows are three DIFFERENT
 * numbers.
 *
 * Without this, editing `TARGET_COUNT` to match `BULK_COUNT` — or to match the
 * total — would leave every spec passing while no longer able to tell a filter
 * that selected the right source from one that selected the wrong source, or
 * from one that was never applied.
 */
function assertCountsDiscriminate(
  bulk: FilterChipSourceRef,
  target: FilterChipSourceRef,
  total: number,
): void {
  const counts = {
    total,
    bulk: bulk.experimentIds.length,
    target: target.experimentIds.length,
  };
  const distinct = new Set(Object.values(counts));
  if (distinct.size !== 3) {
    throw new Error(
      '[experimentsFilterChip fixture] the three row counts must be pairwise distinct for a ' +
        'narrowing assertion to be able to fail, got ' +
        `${JSON.stringify(counts)} — a target the same size as the bulk source cannot tell ` +
        'the right source from the wrong one, and one the size of the whole population cannot ' +
        'tell a filter from no filter',
    );
  }
}

/** How long the experiment reads are given to agree with the seed. */
const READBACK_TIMEOUT_MS = 60_000;
const READBACK_POLL_MS = 1_500;

/**
 * Refuse to hand over a seed the specs could not fail against.
 *
 * Every population a spec asserts is checked here first, server-side and as a
 * CLOSED set (the `total` and the ids, never "mine are in there"):
 *
 *  - each item source's own experiments, so a row attributed to the wrong
 *    source is caught before any filter is driven;
 *  - the project's Datasets-tab population under the tab's own
 *    `evaluation_method != evaluation_suite`, so "fourteen rows" is a claim
 *    about a closed server-side set rather than about a page size;
 *  - the prompt's population, so a prompt-version link that silently failed
 *    fails HERE rather than as a mysterious row count later;
 *  - the prompt's population already narrowed by the Item source filter the UI
 *    is about to build. This is the discriminating one: it proves the server
 *    answers exactly the target's two rows for that filter, so a UI assertion
 *    of two rows cannot be satisfied by a filter that never reached the wire.
 */
async function assertSeedReadsBack(
  backendClient: BackendClient,
  seed: {
    projectId: string;
    promptId: string;
    bulk: FilterChipSourceRef;
    target: FilterChipSourceRef;
    allExperimentIds: string[];
  },
): Promise<void> {
  const start = Date.now();
  let lastMismatch = '<never read>';

  while (Date.now() - start < READBACK_TIMEOUT_MS) {
    const mismatch = await describeReadbackMismatch(backendClient, seed);
    if (mismatch === null) return;
    lastMismatch = mismatch;
    await new Promise((resolve) => setTimeout(resolve, READBACK_POLL_MS));
  }

  throw new Error(
    '[experimentsFilterChip fixture] the experiment reads never agreed with the seed after ' +
      `${Date.now() - start}ms: ${lastMismatch}`,
  );
}

/** The first disagreement between the server and the seed, or null. */
async function describeReadbackMismatch(
  backendClient: BackendClient,
  seed: {
    projectId: string;
    promptId: string;
    bulk: FilterChipSourceRef;
    target: FilterChipSourceRef;
    allExperimentIds: string[];
  },
): Promise<string | null> {
  for (const source of [seed.bulk, seed.target]) {
    const listed = await backendClient.listExperimentsForDataset(source.datasetId);
    const got = listed.map((experiment) => experiment.id).sort();
    const want = [...source.experimentIds].sort();
    if (got.length !== want.length || got.some((id, index) => id !== want[index])) {
      return `item source '${source.datasetName}' holds ${JSON.stringify(got)}, expected ${JSON.stringify(want)}`;
    }
  }

  // The Datasets tab's own server-side selection, not a bare project read: the
  // tab sends `!=` so that an experiment stored under any non-suite method
  // still lands somewhere (opik#9611).
  const tabPopulation = await backendClient.listExperimentsForProject({
    projectId: seed.projectId,
    evaluationMethod: { operator: '!=', value: 'evaluation_suite' },
  });
  const tabMismatch = describeSetMismatch(
    "the project's Datasets-tab population",
    tabPopulation.total,
    tabPopulation.experiments.map((experiment) => experiment.id),
    seed.allExperimentIds,
  );
  if (tabMismatch !== null) return tabMismatch;

  const promptPopulation = await backendClient.listExperimentsForPrompt({
    projectId: seed.projectId,
    promptId: seed.promptId,
  });
  const promptMismatch = describeSetMismatch(
    "the prompt tab's population",
    promptPopulation.total,
    promptPopulation.ids,
    seed.allExperimentIds,
  );
  if (promptMismatch !== null) return promptMismatch;

  const narrowed = await backendClient.listExperimentsForPrompt({
    projectId: seed.projectId,
    promptId: seed.promptId,
    datasetId: seed.target.datasetId,
  });
  return describeSetMismatch(
    `the prompt tab's population filtered to item source '${seed.target.datasetName}'`,
    narrowed.total,
    narrowed.ids,
    seed.target.experimentIds,
  );
}

/** A population compared as a closed set — its `total` and its ids. */
function describeSetMismatch(
  what: string,
  total: number,
  ids: string[],
  expected: string[],
): string | null {
  if (total !== expected.length) {
    return `${what} answered total ${total}, expected ${expected.length}`;
  }
  const got = [...ids].sort();
  const want = [...expected].sort();
  if (got.length !== want.length || got.some((id, index) => id !== want[index])) {
    return `${what} answered ${JSON.stringify(got)}, expected ${JSON.stringify(want)}`;
  }
  return null;
}

export { expect } from './same-name-datasets.fixture';
