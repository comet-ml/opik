import { test as baseTest, expect } from './optimization-cost.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * More experiments than one page holds, by enough that the last one is
 * unambiguously off page 1. Twelve at a page size of 10 leaves exactly two on
 * page 2 — a pinned row fetched by id and prepended to page 1 is then visibly
 * an extra row rather than one the page had already loaded.
 */
export const EXPERIMENT_COUNT = 12;

/** The page size the pinning specs drive, passed as a `size` query param. */
export const PAGE_SIZE = 10;

export interface PromptExperimentsRef {
  promptId: string;
  promptName: string;
  promptVersionId: string;
  projectId: string;
  projectName: string;
  datasetName: string;
  /** Experiment ids in SEEDED order — index 0 was written first. */
  experimentIds: string[];
  /** Experiment names in seeded order, `…-exp-01` … `…-exp-12`. */
  experimentNames: string[];
}

export interface PromptExperimentsFixtures {
  promptExperiments: PromptExperimentsRef;
}

/**
 * A prompt with one committed version and twelve experiments run from it —
 * the shape the prompt detail page's Experiments tab lists (OPIK-3345).
 *
 * The experiments are linked through `promptVersions`, not through the
 * project: that tab queries by prompt id, so an experiment seeded without the
 * version link lands in the project's Experiments page and nowhere else, and a
 * spec built on it would open an empty tab and assert nothing.
 *
 * Seeded over REST rather than by running anything: what these specs assert is
 * which row the list puts first and how many times it appears, which is
 * independent of whether an evaluation ever produced a score. That keeps the
 * fixture deterministic, LLM-free and fast enough to seed twelve of them.
 *
 * Teardown deletes the experiments and the prompt explicitly. Neither cascades
 * with the project, and `global-teardown`'s run-prefix sweep does cover both —
 * but only six hours later, and a spec that asserts "exactly twelve rows"
 * cannot share a workspace with the leftovers of the last run.
 *
 * The prompt id comes from `findPromptIdByName`, never from the create
 * response: `createPromptVersion` answers with the VERSION id, and
 * `DELETE /v1/private/prompts/{id}` answers 404 for one of those, so teardown
 * registered with it would leak in silence.
 */
export const test = baseTest.extend<PromptExperimentsFixtures>({
  promptExperiments: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const promptName = `${testNamespace}-prompt`;
    const datasetName = `${testNamespace}-exp-ds`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'prompt experiments tab pinning',
      items: [{ question: 'seeded', answer: 'seeded' }] as unknown as Array<
        Record<string, unknown>
      >,
    });

    const version = await backendClient.createPromptVersion({
      name: promptName,
      template: 'Answer {{question}}.',
      projectId: project.id,
      changeDescription: 'seeded for the Experiments tab',
    });
    const promptId = await backendClient.findPromptIdByName(promptName, project.id);
    if (!promptId) {
      throw new Error(`[promptExperiments fixture] no prompt id resolved for '${promptName}'`);
    }

    const experimentIds: string[] = [];
    const experimentNames: string[] = [];
    for (let n = 1; n <= EXPERIMENT_COUNT; n++) {
      // Zero-padded so the names sort the same way lexicographically and
      // numerically — `exp-10` must not read as older than `exp-2`.
      const name = `${testNamespace}-exp-${String(n).padStart(2, '0')}`;
      const id = uuid7();
      await backendClient.createExperiment({
        id,
        name,
        datasetName,
        projectName: project.name,
        promptVersionIds: [version.id],
      });
      experimentIds.push(id);
      experimentNames.push(name);
    }

    const ref: PromptExperimentsRef = {
      promptId,
      promptName,
      promptVersionId: version.id,
      projectId: project.id,
      projectName: project.name,
      datasetName,
      experimentIds,
      experimentNames,
    };
    await testInfo.attach('opik.promptExperiments', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[promptExperiments fixture] delete warning for ${what}:`, err);
        }
      };
      for (const id of experimentIds) {
        await safe(`experiment ${id}`, () => backendClient.deleteExperiment(id));
      }
      await safe(`prompt ${promptName}`, () => backendClient.deletePrompt(promptId));
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect };
