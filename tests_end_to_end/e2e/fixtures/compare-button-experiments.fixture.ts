import { test as baseTest } from './raw-branch-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** Three experiments on dataset A — enough for "one", "two" and "three selected". */
const DATASET_A_EXPERIMENTS = 3;

export interface CompareButtonExperimentRef {
  id: string;
  name: string;
}

export interface CompareButtonExperimentsRef {
  projectId: string;
  projectName: string;
  /** The dataset every `datasetA` experiment shares. */
  datasetAId: string;
  datasetAName: string;
  /** A second dataset, so a selection can legitimately span two. */
  datasetBId: string;
  datasetBName: string;
  datasetA: CompareButtonExperimentRef[];
  datasetB: CompareButtonExperimentRef;
}

export interface CompareButtonExperimentsFixtures {
  compareButtonExperiments: CompareButtonExperimentsRef;
}

const LISTED_TIMEOUT_MS = 60_000;
const LISTED_POLL_MS = 1_000;

/**
 * Three experiments over one dataset and a fourth over another.
 *
 * Shaped for the three branches of the experiments list's Compare handler
 * (opik#8612): one selected raises the same-dataset picker, two-or-more on one
 * dataset navigates straight to the compare view, and a selection spanning two
 * datasets raises the mixed-dataset guard instead. The fourth experiment exists
 * only to make that last case reachable — with a single dataset in the
 * workspace the guard is unreachable and the branch untestable.
 *
 * No traces and no experiment items: the subject is a button's branching on
 * SELECTION COUNT and dataset identity, which the list decides from the
 * experiment rows alone. Seeding runs would cost minutes and change nothing
 * the handler reads.
 *
 * Names are namespaced and mutually non-prefixing (`-a1`, `-a2`, `-a3`, `-b1`),
 * because the compare picker's rows are matched by name: a name that were a
 * prefix of another would resolve to two rows and the count assertions would
 * be reading the wrong thing.
 *
 * Teardown deletes the experiments and both datasets; neither cascades with
 * the project.
 */
export const test = baseTest.extend<CompareButtonExperimentsFixtures>({
  compareButtonExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetAName = `${testNamespace}-cmp-ds-a`;
    const datasetBName = `${testNamespace}-cmp-ds-b`;

    const datasetA = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetAName,
      description: 'compare-button branches: the shared dataset',
    });
    const datasetB = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetBName,
      description: 'compare-button branches: the second dataset',
    });

    const aExperiments: CompareButtonExperimentRef[] = Array.from(
      { length: DATASET_A_EXPERIMENTS },
      (_, i) => ({ id: uuid7(), name: `${testNamespace}-cmp-a${i + 1}` }),
    );
    const bExperiment: CompareButtonExperimentRef = {
      id: uuid7(),
      name: `${testNamespace}-cmp-b1`,
    };

    try {
      for (const experiment of aExperiments) {
        await backendClient.createExperiment({
          id: experiment.id,
          name: experiment.name,
          datasetName: datasetAName,
          projectName: project.name,
        });
      }
      await backendClient.createExperiment({
        id: bExperiment.id,
        name: bExperiment.name,
        datasetName: datasetBName,
        projectName: project.name,
      });

      // Every experiment listed, and on the dataset it was meant for, before
      // any test opens the browser. The handler branches on `dataset_id`, so an
      // experiment that landed on the wrong dataset would send the spec down a
      // different branch than the one it names — which would read as the
      // regression rather than as the seed fault it is.
      await waitForListed(backendClient, datasetA.id, aExperiments.length, datasetAName);
      await waitForListed(backendClient, datasetB.id, 1, datasetBName);

      const ref: CompareButtonExperimentsRef = {
        projectId: project.id,
        projectName: project.name,
        datasetAId: datasetA.id,
        datasetAName,
        datasetBId: datasetB.id,
        datasetBName,
        datasetA: aExperiments,
        datasetB: bExperiment,
      };

      await testInfo.attach('opik.compareButtonExperiments', {
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
            console.warn(`[compareButtonExperiments fixture] delete warning for ${what}:`, err);
          }
        };
        for (const experiment of [...aExperiments, bExperiment]) {
          await safe(`experiment ${experiment.name}`, () =>
            backendClient.deleteExperiment(experiment.id),
          );
        }
        await safe(`dataset ${datasetAName}`, () => backendClient.deleteDataset(datasetA.id));
        await safe(`dataset ${datasetBName}`, () => backendClient.deleteDataset(datasetB.id));
      }
    }
  },
});

/** Block until a dataset lists exactly `expected` experiments. */
async function waitForListed(
  backendClient: {
    listExperimentsForDataset: (datasetId: string) => Promise<Array<{ id: string }>>;
  },
  datasetId: string,
  expected: number,
  label: string,
): Promise<void> {
  const start = Date.now();
  let seen = -1;
  while (Date.now() - start < LISTED_TIMEOUT_MS) {
    seen = (await backendClient.listExperimentsForDataset(datasetId)).length;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, LISTED_POLL_MS));
  }
  throw new Error(
    `[compareButtonExperiments fixture] dataset ${label} lists ${seen} experiments, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

export { expect } from './raw-branch-experiment.fixture';
