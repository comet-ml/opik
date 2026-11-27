import { test as baseTest } from './duplicate-trace-id-projects.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface MixedSuiteExperimentRef {
  id: string;
  name: string;
}

export interface MixedSuiteExperimentsRef {
  projectId: string;
  projectName: string;
  /** The suite holding TWO experiments, so a same-suite pair is selectable. */
  suiteOneId: string;
  suiteOneName: string;
  /** A second suite, so a selection can legitimately span two of them. */
  suiteTwoId: string;
  suiteTwoName: string;
  suiteOne: [MixedSuiteExperimentRef, MixedSuiteExperimentRef];
  suiteTwo: MixedSuiteExperimentRef;
}

export interface MixedSuiteExperimentsFixtures {
  mixedSuiteExperiments: MixedSuiteExperimentsRef;
}

const LISTED_TIMEOUT_MS = 60_000;
const LISTED_POLL_MS = 1_000;

/**
 * Two experiments on one test suite and a third on another, all three stored
 * with `evaluation_method: 'evaluation_suite'`.
 *
 * The suite-side twin of `compareButtonExperiments`, which seeds plain
 * datasets. The Compare guard names the item-source TYPE in its tooltip —
 * "dataset", "test suite" or "item source" — and that label is chosen from the
 * selected experiments' `evaluation_method` (`isTestSuiteExperiment`), so the
 * "test suite" branch needs experiments actually stored under that method.
 * Without it the Experiments page's Test suites tab, which filters on
 * `evaluation_method = evaluation_suite`, lists nothing at all.
 *
 * Two on suite one rather than one each: a tooltip spec that only ever drives
 * a mixed selection cannot tell "Compare is disabled because the sources
 * differ" from "Compare is disabled, full stop". The same-suite pair is the
 * discriminator, and it has to come from the same seed to be one.
 *
 * No experiment items and no runs: the panel decides everything it does from
 * the selected rows' `dataset_id` and `evaluation_method`, both of which the
 * list already holds. A real suite run would judge its assertions with an LLM,
 * which is neither free nor deterministic.
 *
 * Names are mutually non-prefixing (`-s1a`, `-s1b`, `-s2a`) so a name-based
 * lookup in the compare picker cannot resolve to two rows.
 *
 * Teardown deletes the experiments and then both suites; test suites share
 * storage with datasets and neither cascades with the project.
 */
export const test = baseTest.extend<MixedSuiteExperimentsFixtures>({
  mixedSuiteExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const suiteOneName = `${testNamespace}-mix-s1`;
    const suiteTwoName = `${testNamespace}-mix-s2`;

    const suiteOne = await sdkClient.python.createTestSuite({
      project_name: project.name,
      name: suiteOneName,
      description: 'compare guard across item sources: the shared test suite',
      items: [{ data: { question: 'suite one question' } }],
    });
    const suiteTwo = await sdkClient.python.createTestSuite({
      project_name: project.name,
      name: suiteTwoName,
      description: 'compare guard across item sources: the second test suite',
      items: [{ data: { question: 'suite two question' } }],
    });

    const suiteOneExperiments: [MixedSuiteExperimentRef, MixedSuiteExperimentRef] = [
      { id: uuid7(), name: `${testNamespace}-mix-s1a` },
      { id: uuid7(), name: `${testNamespace}-mix-s1b` },
    ];
    const suiteTwoExperiment: MixedSuiteExperimentRef = {
      id: uuid7(),
      name: `${testNamespace}-mix-s2a`,
    };

    try {
      for (const experiment of suiteOneExperiments) {
        await backendClient.createExperiment({
          id: experiment.id,
          name: experiment.name,
          datasetName: suiteOneName,
          projectName: project.name,
          evaluationMethod: 'evaluation_suite',
        });
      }
      await backendClient.createExperiment({
        id: suiteTwoExperiment.id,
        name: suiteTwoExperiment.name,
        datasetName: suiteTwoName,
        projectName: project.name,
        evaluationMethod: 'evaluation_suite',
      });

      // Prove the seed can discriminate before any browser opens.
      //
      // `evaluation_method` is sent through a raw POST because the pinned SDK
      // has no field for it, and an omitted one is stamped `dataset`. If it
      // did not land, every experiment here would be a DATASET experiment: the
      // Test suites tab would list none of them, and the tooltip assertion
      // would be reading the "dataset" branch while claiming to test the other
      // one.
      for (const experiment of [...suiteOneExperiments, suiteTwoExperiment]) {
        const rendered = await backendClient.getExperimentRenderFields(experiment.id);
        if (rendered.evaluationMethod !== 'evaluation_suite') {
          throw new Error(
            `[mixedSuiteExperiments fixture] experiment ${experiment.name} stored ` +
              `evaluation_method=${rendered.evaluationMethod}, not 'evaluation_suite' — the ` +
              "Experiments page's Test suites tab would not list it at all",
          );
        }
      }

      // And that each one landed on the suite it was meant for: the guard
      // branches on `dataset_id`, so an experiment on the wrong suite would
      // send the spec down the other branch than the one it names.
      await waitForListed(backendClient, suiteOne.id, 2, suiteOneName);
      await waitForListed(backendClient, suiteTwo.id, 1, suiteTwoName);

      const ref: MixedSuiteExperimentsRef = {
        projectId: project.id,
        projectName: project.name,
        suiteOneId: suiteOne.id,
        suiteOneName,
        suiteTwoId: suiteTwo.id,
        suiteTwoName,
        suiteOne: suiteOneExperiments,
        suiteTwo: suiteTwoExperiment,
      };

      await testInfo.attach('opik.mixedSuiteExperiments', {
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
            console.warn(`[mixedSuiteExperiments fixture] delete warning for ${what}:`, err);
          }
        };
        for (const experiment of [...suiteOneExperiments, suiteTwoExperiment]) {
          await safe(`experiment ${experiment.name}`, () =>
            backendClient.deleteExperiment(experiment.id),
          );
        }
        await safe(`test suite ${suiteOneName}`, () => backendClient.deleteDataset(suiteOne.id));
        await safe(`test suite ${suiteTwoName}`, () => backendClient.deleteDataset(suiteTwo.id));
      }
    }
  },
});

/** Block until a suite lists exactly `expected` experiments. */
async function waitForListed(
  backendClient: {
    listExperimentsForDataset: (datasetId: string) => Promise<Array<{ id: string }>>;
  },
  suiteId: string,
  expected: number,
  label: string,
): Promise<void> {
  const start = Date.now();
  let seen = -1;
  while (Date.now() - start < LISTED_TIMEOUT_MS) {
    seen = (await backendClient.listExperimentsForDataset(suiteId)).length;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, LISTED_POLL_MS));
  }
  throw new Error(
    `[mixedSuiteExperiments fixture] test suite ${label} lists ${seen} experiments, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

export { expect } from './duplicate-trace-id-projects.fixture';
