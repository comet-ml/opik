import { expect } from '@playwright/test';
import { test as baseTest } from './playground-span-loads.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One seeded experiment and the `evaluation_method` the server stored for it. */
export interface EvaluationMethodExperimentRef {
  id: string;
  name: string;
  /**
   * What the write asked for, or `null` when the request carried no
   * `evaluation_method` key at all — which is a different thing from the value
   * the read then reports for it, and the reason this is separate from
   * `storedMethod`.
   */
  declaredMethod: 'evaluation_suite' | null;
  /**
   * What `GET /v1/private/experiments/{id}` reports, read back by the fixture
   * rather than assumed. For the two default experiments this is whatever
   * `EvaluationMethod` resolves an absent value to, which a spec about the
   * filter must not hard-code a guess at.
   */
  storedMethod: string | null;
}

export interface EvaluationMethodExperimentsRef {
  datasetId: string;
  datasetName: string;
  projectId: string;
  projectName: string;
  /** The two experiments on the default method. */
  defaultMethod: EvaluationMethodExperimentRef[];
  /** The one experiment written with `evaluation_method: 'evaluation_suite'`. */
  suiteMethod: EvaluationMethodExperimentRef[];
  /** All three, in seed order. */
  all: EvaluationMethodExperimentRef[];
  /** The value the two default experiments actually stored, e.g. `dataset`. */
  defaultMethodValue: string;
  /** The value the suite experiment stored, as the enum spells it on the wire. */
  suiteMethodValue: string;
}

export interface EvaluationMethodExperimentsFixtures {
  evaluationMethodExperiments: EvaluationMethodExperimentsRef;
}

/** How long a just-created experiment may take to become readable. */
const READABLE_TIMEOUT_MS = 60_000;

/**
 * The wire value the suite method is written and expected to read back as.
 *
 * `EvaluationMethod.TEST_SUITE` carries a `@JsonValue` of `evaluation_suite`
 * and a TODO to migrate the DB value to `test_suite`. The filter matches the
 * STORED string, so when that migration lands this constant is the one edit the
 * fixture and its spec need — and a rename that changed only one of the two
 * ends would fail here rather than somewhere inside an assertion.
 */
const SUITE_METHOD_VALUE = 'evaluation_suite';

/**
 * Three experiments over ONE dataset, differing only in `evaluation_method`:
 * two on the default and one on `evaluation_suite`.
 *
 * All three over the same dataset on purpose. The filter under test
 * (`EVALUATION_METHOD`, opik#9456) composes with the endpoint's `datasetId`
 * scope, so a closed-set assertion ("this dataset holds exactly these two")
 * only means something while every candidate row is inside the same scope — in
 * a dataset of its own the suite experiment would be excluded by `datasetId`
 * and prove nothing about the filter.
 *
 * TWO on the default and ONE on the suite method, deliberately asymmetric: the
 * expected size of each answer then differs from the other's, so a filter that
 * returned the wrong set but the right count cannot agree by luck, and `!=` has
 * a complement that is not the same shape in both directions.
 *
 * No traces, no experiment items and no scores: `evaluation_method` is a column
 * on the experiment row, and the filter never reaches the items. Seeding runs
 * would make this fixture minutes slower and the assertions no sharper.
 *
 * Written through the backend client's raw experiment create — the pinned SDK
 * has no field for `evaluation_method` and would silently drop it, leaving all
 * three experiments on the default and a spec that could not fail.
 *
 * Teardown deletes the experiments and the dataset; neither cascades with the
 * project.
 */
export const test = baseTest.extend<EvaluationMethodExperimentsFixtures>({
  evaluationMethodExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-eval-method-ds`;
    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'experiments differing only in evaluation_method',
    });

    const seeded: EvaluationMethodExperimentRef[] = [];
    try {
      for (const [label, method] of [
        ['default-a', null],
        ['default-b', null],
        ['suite', SUITE_METHOD_VALUE],
      ] as const) {
        const ref: EvaluationMethodExperimentRef = {
          id: uuid7(),
          name: `${testNamespace}-${label}-exp`,
          declaredMethod: method === null ? null : 'evaluation_suite',
          storedMethod: null,
        };
        await backendClient.createExperiment({
          id: ref.id,
          name: ref.name,
          datasetName,
          projectName: project.name,
          ...(method === null ? {} : { evaluationMethod: 'evaluation_suite' as const }),
        });
        // Registered as each write lands so a rejection from a later create
        // still tears down the rows already written.
        seeded.push(ref);
      }

      // Every experiment readable, and its stored method recorded, BEFORE any
      // filter is sent. Both halves are load-bearing: the create answers 201
      // over an eventually-consistent write, and a seed that failed to store
      // the method it declared would make "the filter returned the suite
      // experiment" a claim about a row that was never a suite experiment —
      // which reads as coverage forever.
      for (const ref of seeded) {
        await expect
          .poll(async () => backendClient.getExperimentEvaluationMethod(ref.id), {
            message: `experiment ${ref.name} must be readable before the filter is asserted`,
            timeout: READABLE_TIMEOUT_MS,
            intervals: [500, 1_000, 2_000],
          })
          .not.toBeNull();
        ref.storedMethod = await backendClient.getExperimentEvaluationMethod(ref.id);
      }

      const defaultMethod = seeded.filter((e) => e.declaredMethod === null);
      const suiteMethod = seeded.filter((e) => e.declaredMethod !== null);

      // The seed's discriminating property, asserted rather than assumed: the
      // two groups must read back as DIFFERENT methods. If the suite write were
      // dropped they would all read the same, every filter below would return
      // all three or none, and the spec would be asserting nothing about a
      // filter at all.
      const defaultValues = [...new Set(defaultMethod.map((e) => e.storedMethod))];
      expect(
        defaultValues,
        'both experiments written with no evaluation_method stored the same single value',
      ).toHaveLength(1);
      expect(
        suiteMethod.map((e) => e.storedMethod),
        'the suite experiment stored the method its write declared',
      ).toEqual([SUITE_METHOD_VALUE]);
      expect(
        defaultValues[0],
        'the default method differs from the suite one, or there is nothing to filter apart',
      ).not.toBe(SUITE_METHOD_VALUE);

      const ref: EvaluationMethodExperimentsRef = {
        datasetId: dataset.id,
        datasetName,
        projectId: project.id,
        projectName: project.name,
        defaultMethod,
        suiteMethod,
        all: seeded,
        defaultMethodValue: defaultValues[0] as string,
        suiteMethodValue: SUITE_METHOD_VALUE,
      };

      await testInfo.attach('opik.evaluationMethodExperiments', {
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
            console.warn(`[evaluationMethodExperiments fixture] delete warning for ${what}:`, err);
          }
        };
        // Experiments before the dataset they reference.
        for (const experiment of seeded) {
          await safe(`experiment ${experiment.name}`, () =>
            backendClient.deleteExperiment(experiment.id),
          );
        }
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
      }
    }
  },
});

export { expect } from './playground-span-loads.fixture';
