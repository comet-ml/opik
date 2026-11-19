import { test as baseTest } from './environment-split-thread.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import type { AssertionResultSeed } from '../core/backend';

/**
 * One item source (one group on the Experiments page) and the pass rate its
 * group row must therefore render.
 */
export interface PassRateGroupRef {
  datasetId: string;
  /** The item source's name — how the group row identifies itself on screen. */
  datasetName: string;
  /** This group's experiments, in seed order. */
  experiments: PassRateExperimentRef[];
  /**
   * `pass_rate_avg` the aggregations endpoint must answer for this group: the
   * MEAN of its experiments' pass rates, or null when the group holds no
   * evaluation-suite experiment and the key is absent entirely.
   */
  expectedPassRateAvg: number | null;
  /** `passed_count_sum` / `total_count_sum`, pooled over the group's items. */
  expectedPassedSum: number | null;
  expectedTotalSum: number | null;
  /** What the group row's Pass rate cell must read, exactly. */
  expectedGroupLabel: string;
}

export interface PassRateExperimentRef {
  id: string;
  name: string;
  /** Items in this experiment; one dataset item and one trace each. */
  totalCount: number;
  /** How many of them passed every assertion on their trace. */
  passedCount: number;
  evaluationMethod: 'dataset' | 'evaluation_suite';
  /**
   * The stored `pass_rate` in units of 1e-9 — the decimal the backend keeps,
   * carried alongside the number so a group's mean can be taken without
   * binary-float drift. Null for the dataset method, which has no pass rate.
   */
  expectedPassRateUnits: number | null;
  /** `pass_rate` the experiment list must answer, or null for the dataset method. */
  expectedPassRate: number | null;
  /** What this experiment's own row must read in the Pass rate column. */
  expectedRowLabel: string;
}

export interface GroupedPassRateExperimentsRef {
  projectId: string;
  projectName: string;
  /** Every experiment below is linked to this prompt's one version. */
  promptId: string;
  promptName: string;
  promptVersionId: string;
  /** A group of two suite runs with DIFFERENT item counts: 1/1 and 1/3. */
  mixed: PassRateGroupRef;
  /** A single 199/200 suite run — `pass_rate` 0.995, which plain rounding reads as 100%. */
  cap: PassRateGroupRef;
  /** A single 2/2 suite run — `pass_rate` exactly 1. */
  green: PassRateGroupRef;
  /** A single dataset-method experiment, so the group has no pass rate at all. */
  datasetOnly: PassRateGroupRef;
  /** The four groups in declaration order, for callers that sweep them. */
  groups: PassRateGroupRef[];
}

export interface GroupedPassRateExperimentsFixtures {
  groupedPassRateExperiments: GroupedPassRateExperimentsRef;
}

/** The one assertion name every seeded trace is judged on. */
const ASSERTION_NAME = 'contains-pass';

/**
 * `pass_rate` is stored as `toDecimal64(passed / total, 9)`, so the backend's
 * arithmetic is DECIMAL, not binary floating point, and the expectations here
 * have to be modelled the same way or they drift in the last digit.
 *
 * Both steps are modelled in units of 1e-9 and divided only at the end:
 * `storedPassRateUnits` is the stored value, and a group's `avg(pass_rate)` is
 * the mean of those. Summing the rates as doubles instead gets this wrong —
 * `1 + 0.333333333` is 1.3333333330000001 in binary, so the mean of {1, 1/3}
 * comes out as 0.6666666665000001 where the endpoint answers 0.6666666665.
 *
 * Modelling it rather than hard-coding the four numbers is what keeps the
 * expectations derived from the seeded ratios: the cap — a rate below 1 never
 * rounding above 99% — is the behaviour under test, so the fixture has to be
 * able to say what the seed should produce without quietly agreeing with
 * whatever the product does.
 */
const DECIMAL_SCALE = 1_000_000_000;

const storedPassRateUnits = (passed: number, total: number): number =>
  Math.round((passed / total) * DECIMAL_SCALE);

/**
 * Four item sources under one project and one prompt version, holding five
 * experiments whose pass rates pin every branch of the Experiments group row's
 * Pass rate cell (opik#9646 / OPIK-9234).
 *
 * **Why the group must hold runs with DIFFERENT item counts.** The backend's
 * group pass rate is `avg(pass_rate)` — the mean of the experiments' rates —
 * while `passed_count_sum` / `total_count_sum` are the pooled items. Those two
 * figures coincide whenever the runs have equal item counts, so a cell wired to
 * the pooled ratio would pass such a fixture. The `mixed` group holds 1/1 and
 * 1/3: the mean is 0.6667 and the pooled ratio is 2/4 = 0.5, so the cell can
 * only read 67% if it took the mean.
 *
 * **Why 199/200.** `Math.round(0.995 * 100)` is 100 in JS, so before the
 * `formatPercent` cap this PR added, a group one item short of perfect rendered
 * "100%" beside a red dot. 199/200 is the smallest single run that reproduces
 * it (`k/n >= 0.995` with `k < n` needs `n >= 200`), and it pins the cap on the
 * group row AND on the experiment row inside the group.
 *
 * **Why REST and not a run.** `opik.run_tests` decides pass/fail with an LLM
 * judge: it cannot be steered to a chosen ratio, it is not deterministic, and
 * this OSS deployment has no provider key. The verdicts are written straight to
 * `PUT /v1/private/assertion-results`, which is the same table
 * `ExperimentDAO.pass_rate_agg` reads for a judged run — so the aggregate under
 * test is the real query over real rows, only the verdicts are chosen. What this
 * deliberately does NOT cover is `opik.run_tests` producing those rows, which
 * opik#9646 does not touch.
 *
 * **The precondition check is load-bearing.** Before any browser opens, the
 * fixture reads the aggregations endpoint back and refuses to hand over a seed
 * whose numbers are not the ones the specs assert — including that the mean and
 * the pooled ratio really disagree on `mixed`, and that `datasetOnly` carries no
 * pass-rate keys at all. A UI assertion over a seed that silently failed to set
 * up is a test that cannot fail, and would read as coverage forever.
 *
 * Teardown deletes the experiments, the datasets, the traces and the prompt —
 * none of them cascades with the project, and `global-teardown`'s run-prefix
 * sweep knows nothing about prompts and only reaches the rest six hours later,
 * which is no use to a spec asserting "exactly these four groups".
 */
export const test = baseTest.extend<GroupedPassRateExperimentsFixtures>({
  groupedPassRateExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const promptName = `${testNamespace}-prompt`;
    const version = await backendClient.createPromptVersion({
      name: promptName,
      template: 'Answer {{question}}.',
      projectId: project.id,
      changeDescription: 'seeded for the Experiments group pass rate',
    });
    // Never the id `createPromptVersion` answers with: that is the VERSION id,
    // and `DELETE /v1/private/prompts/{id}` 404s for it, so teardown
    // registered with it would leak behind the `catch`.
    const promptId = await backendClient.findPromptIdByName(promptName, project.id);
    if (!promptId) {
      throw new Error(
        `[groupedPassRateExperiments fixture] no prompt id resolved for '${promptName}'`,
      );
    }

    const datasetIds: string[] = [];
    const experimentIds: string[] = [];
    const traceIds: string[] = [];

    /** One item source, created empty; its items arrive with each experiment. */
    const seedDataset = async (suffix: string): Promise<{ id: string; name: string }> => {
      const name = `${testNamespace}-${suffix}`;
      const created = await sdkClient.python.createDataset({
        project_name: project.name,
        name,
        description: 'Experiments group pass rate',
      });
      datasetIds.push(created.id);
      return { id: created.id, name };
    };

    /**
     * One experiment: `totalCount` dataset items, one trace each, one
     * experiment item per pair, and — for the suite method — one assertion
     * verdict per trace, the first `passedCount` of them passing.
     */
    const seedExperiment = async (args: {
      dataset: { id: string; name: string };
      suffix: string;
      totalCount: number;
      passedCount: number;
      evaluationMethod: 'dataset' | 'evaluation_suite';
    }): Promise<PassRateExperimentRef> => {
      const { dataset, suffix, totalCount, passedCount, evaluationMethod } = args;
      const name = `${testNamespace}-${suffix}`;
      const experimentId = uuid7();
      const itemIds = Array.from({ length: totalCount }, () => uuid7());
      const traces = Array.from({ length: totalCount }, () => uuid7());

      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        items: itemIds.map((id, index) => ({
          id,
          data: { question: `${suffix}-q${index}` },
        })),
      });

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: traces.map((id, index) => ({
          id,
          name: `${name}-t${index}`,
          input: { question: `${suffix}-q${index}` },
          output: { answer: index < passedCount ? 'PASS' : 'FAIL' },
        })),
      });
      traceIds.push(...traces);

      await backendClient.createExperiment({
        id: experimentId,
        name,
        datasetName: dataset.name,
        projectName: project.name,
        evaluationMethod,
        // The link that puts this experiment on the prompt detail page's
        // Experiments tab, which queries by prompt rather than by project — the
        // second call site of the same aggregated cell.
        promptVersionIds: [version.id],
      });
      experimentIds.push(experimentId);

      await backendClient.createExperimentItems(
        traces.map((traceId, index) => ({
          experimentId,
          datasetItemId: itemIds[index],
          traceId,
        })),
      );

      if (evaluationMethod === 'evaluation_suite') {
        const results: AssertionResultSeed[] = traces.map((traceId, index) => ({
          traceId,
          name: ASSERTION_NAME,
          status: index < passedCount ? 'passed' : 'failed',
        }));
        await backendClient.writeAssertionResults({
          projectName: project.name,
          results,
        });
      }

      const expectedPassRateUnits =
        evaluationMethod === 'evaluation_suite'
          ? storedPassRateUnits(passedCount, totalCount)
          : null;
      const expectedPassRate =
        expectedPassRateUnits === null ? null : expectedPassRateUnits / DECIMAL_SCALE;
      return {
        id: experimentId,
        name,
        totalCount,
        passedCount,
        evaluationMethod,
        expectedPassRateUnits,
        expectedPassRate,
        expectedRowLabel:
          expectedPassRate === null ? '-' : formatPassRatePercent(expectedPassRate),
      };
    };

    try {
      const mixedDataset = await seedDataset('src-mixed');
      const mixedExperiments = [
        await seedExperiment({
          dataset: mixedDataset,
          suffix: 'mixed-all-pass',
          totalCount: 1,
          passedCount: 1,
          evaluationMethod: 'evaluation_suite',
        }),
        await seedExperiment({
          dataset: mixedDataset,
          suffix: 'mixed-one-of-three',
          totalCount: 3,
          passedCount: 1,
          evaluationMethod: 'evaluation_suite',
        }),
      ];

      const capDataset = await seedDataset('src-cap');
      const capExperiments = [
        await seedExperiment({
          dataset: capDataset,
          suffix: 'cap-199-of-200',
          totalCount: 200,
          passedCount: 199,
          evaluationMethod: 'evaluation_suite',
        }),
      ];

      const greenDataset = await seedDataset('src-green');
      const greenExperiments = [
        await seedExperiment({
          dataset: greenDataset,
          suffix: 'green-all-pass',
          totalCount: 2,
          passedCount: 2,
          evaluationMethod: 'evaluation_suite',
        }),
      ];

      const datasetOnlyDataset = await seedDataset('src-dsonly');
      const datasetOnlyExperiments = [
        await seedExperiment({
          dataset: datasetOnlyDataset,
          suffix: 'dsonly',
          totalCount: 2,
          passedCount: 0,
          evaluationMethod: 'dataset',
        }),
      ];

      const mixed = buildGroup(mixedDataset, mixedExperiments);
      const cap = buildGroup(capDataset, capExperiments);
      const green = buildGroup(greenDataset, greenExperiments);
      const datasetOnly = buildGroup(datasetOnlyDataset, datasetOnlyExperiments);
      const groups = [mixed, cap, green, datasetOnly];

      await assertSeedDiscriminates(backendClient, project.id, groups);

      const ref: GroupedPassRateExperimentsRef = {
        projectId: project.id,
        projectName: project.name,
        promptId,
        promptName,
        promptVersionId: version.id,
        mixed,
        cap,
        green,
        datasetOnly,
        groups,
      };

      await testInfo.attach('opik.groupedPassRateExperiments', {
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
            console.warn(
              `[groupedPassRateExperiments fixture] delete warning for ${what}:`,
              err,
            );
          }
        };
        for (const id of experimentIds) {
          await safe(`experiment ${id}`, () => backendClient.deleteExperiment(id));
        }
        for (const id of datasetIds) {
          await safe(`dataset ${id}`, () => backendClient.deleteDataset(id));
        }
        if (traceIds.length > 0) {
          await safe(`${traceIds.length} traces`, () => backendClient.deleteTraces(traceIds));
        }
        await safe(`prompt ${promptName}`, () => backendClient.deletePrompt(promptId));
      }
    }
  },
});

/**
 * `formatPercent` from `PassRateCell.tsx`, which is what the cell renders.
 *
 * Restated here rather than left as a hard-coded "99%" per case so the
 * expectations in the specs stay derived from the seeded ratio: the cap — a
 * rate below 1 never rounding above 99% — IS the behaviour under test, so the
 * fixture has to be able to say what the seed should produce without the spec
 * quietly agreeing with whatever the product does.
 */
function formatPassRatePercent(passRate: number): string {
  const capped = passRate < 1 ? Math.min(passRate, 0.99) : passRate;
  return `${Math.round(Math.max(0, Math.min(1, capped)) * 100)}%`;
}

/**
 * The group-level expectations, computed from the experiments rather than
 * written down: `pass_rate_avg` is the mean of the suite runs' rates,
 * `passed_count_sum` / `total_count_sum` are pooled over those runs, and a
 * group with no suite run has none of the three.
 */
function buildGroup(
  dataset: { id: string; name: string },
  experiments: PassRateExperimentRef[],
): PassRateGroupRef {
  const suiteRuns = experiments.filter((e) => e.evaluationMethod === 'evaluation_suite');
  if (suiteRuns.length === 0) {
    return {
      datasetId: dataset.id,
      datasetName: dataset.name,
      experiments,
      expectedPassRateAvg: null,
      expectedPassedSum: null,
      expectedTotalSum: null,
      expectedGroupLabel: '',
    };
  }
  const units = suiteRuns.map((run) => {
    if (run.expectedPassRateUnits === null) {
      throw new Error(
        `[groupedPassRateExperiments fixture] suite run ${run.name} has no expected pass rate`,
      );
    }
    return run.expectedPassRateUnits;
  });
  // `avg(pass_rate)`, summed in 1e-9 units and divided once at the end — see
  // `storedPassRateUnits`. 0.6666666665 for {1, 1/3}.
  const mean =
    units.reduce((sum, value) => sum + value, 0) / units.length / DECIMAL_SCALE;
  return {
    datasetId: dataset.id,
    datasetName: dataset.name,
    experiments,
    expectedPassRateAvg: mean,
    expectedPassedSum: suiteRuns.reduce((sum, run) => sum + run.passedCount, 0),
    expectedTotalSum: suiteRuns.reduce((sum, run) => sum + run.totalCount, 0),
    expectedGroupLabel: formatPassRatePercent(mean),
  };
}

/** The window the aggregations read is given to agree with the seed. */
const AGGREGATIONS_TIMEOUT_MS = 60_000;
const AGGREGATIONS_POLL_MS = 1_500;

/**
 * Refuse to hand over a seed the specs could not fail against.
 *
 * Every claim a group row assertion rests on is checked here first, server-side:
 * each group's `pass_rate_avg` / `passed_count_sum` / `total_count_sum`, that the
 * `mixed` group's mean and pooled ratio genuinely DISAGREE (equal counts would
 * make a wrongly-wired cell indistinguishable from a right one), and that the
 * dataset-method group carries none of the three keys — so an empty cell can
 * only mean the product left it empty, never that the seed never landed.
 */
async function assertSeedDiscriminates(
  backendClient: {
    experimentGroupAggregations: (args: {
      projectId: string;
    }) => Promise<Record<string, { label: string | null; aggregations: Record<string, unknown> }>>;
  },
  projectId: string,
  groups: PassRateGroupRef[],
): Promise<void> {
  const start = Date.now();
  let lastMismatch = '<never read>';

  while (Date.now() - start < AGGREGATIONS_TIMEOUT_MS) {
    const answer = await backendClient.experimentGroupAggregations({ projectId });
    const mismatch = describeMismatch(answer, groups);
    if (mismatch === null) {
      assertMeanAndPooledDisagree(groups);
      return;
    }
    lastMismatch = mismatch;
    await new Promise((r) => setTimeout(r, AGGREGATIONS_POLL_MS));
  }

  throw new Error(
    `[groupedPassRateExperiments fixture] the aggregations endpoint never agreed with the seed ` +
      `after ${Date.now() - start}ms: ${lastMismatch}`,
  );
}

/** The first disagreement between the endpoint and the seed, or null. */
function describeMismatch(
  answer: Record<string, { label: string | null; aggregations: Record<string, unknown> }>,
  groups: PassRateGroupRef[],
): string | null {
  if (Object.keys(answer).length !== groups.length) {
    return `expected ${groups.length} groups, got ${Object.keys(answer).length}`;
  }
  for (const group of groups) {
    const node = answer[group.datasetId];
    if (!node) return `no group for item source '${group.datasetName}' (${group.datasetId})`;
    if (node.label !== group.datasetName) {
      return `group ${group.datasetId} is labelled '${node.label}', expected '${group.datasetName}'`;
    }
    const checks: Array<[string, number | null]> = [
      ['pass_rate_avg', group.expectedPassRateAvg],
      ['passed_count_sum', group.expectedPassedSum],
      ['total_count_sum', group.expectedTotalSum],
    ];
    for (const [key, expected] of checks) {
      const actual = node.aggregations[key];
      if (expected === null) {
        // Absent, not zero: the endpoint omits these keys for a group holding
        // no evaluation-suite experiment, and a zero would render as "0%".
        if (key in node.aggregations) {
          return `group '${group.datasetName}' carries ${key}=${JSON.stringify(actual)}, expected the key to be absent`;
        }
        continue;
      }
      if (actual !== expected) {
        return `group '${group.datasetName}' answered ${key}=${JSON.stringify(actual)}, expected ${expected}`;
      }
    }
  }
  return null;
}

/**
 * The seed's whole premise: at least one group where the mean of the pass rates
 * and the pooled passed/total are different numbers. Without it a cell reading
 * the pooled ratio renders the same text as one reading the mean, and the spec
 * asserts nothing.
 */
function assertMeanAndPooledDisagree(groups: PassRateGroupRef[]): void {
  const discriminating = groups.filter((group) => {
    if (
      group.expectedPassRateAvg === null ||
      group.expectedPassedSum === null ||
      group.expectedTotalSum === null ||
      group.expectedTotalSum === 0
    ) {
      return false;
    }
    const pooled = group.expectedPassedSum / group.expectedTotalSum;
    return formatPassRatePercent(pooled) !== group.expectedGroupLabel;
  });
  if (discriminating.length === 0) {
    throw new Error(
      '[groupedPassRateExperiments fixture] no group renders a different label for the mean ' +
        'than for the pooled passed/total — the seed cannot tell a correctly-wired group ' +
        'pass rate from one computed as passed/total',
    );
  }
}

export { expect } from './environment-split-thread.fixture';
