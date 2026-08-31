import type { TestInfo } from '@playwright/test';
import { test as baseTest } from './dashboard-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { BackendClient } from '../core/backend';
import { uuid7 } from '../core/backend';
import type { SdkClient } from '../core/sdk';

/**
 * What one optimization candidate (the baseline, or a numbered trial) is seeded
 * to look like. Every number here is chosen, not measured: the point of these
 * fixtures is that the score, latency and cost the runs list rolls up are
 * arithmetic over values the test wrote, so a wrong cell is unambiguous.
 */
export interface RollupCandidateSeed {
  /** Label the run page's trials table renders — "Baseline" or "Trial #N". */
  label: string;
  /** 0 is the run's baseline; the DAO takes the full-evaluation threshold from it. */
  stepIndex: number;
  /**
   * How many of the dataset's items this candidate was evaluated on. Fewer than
   * the baseline makes it *partially* evaluated, which is what must keep it out
   * of the "best" pool (OPIK-8060).
   */
  evaluatedItems: number;
  /** Wall duration of each of this candidate's traces, in seconds. */
  durationSeconds: number;
  /** Explicit span cost per trace, in USD — set, never derived from a price table. */
  costPerTrace: number;
  /**
   * Feedback-score value written on every one of this candidate's traces, under
   * the run's `scoreName` — which is the objective for a scored run, and
   * deliberately something else for the unscored-objective run.
   */
  score: number;
}

export interface RollupCandidateRef extends RollupCandidateSeed {
  experimentId: string;
  traceIds: string[];
}

export interface OptimizationRollupRef {
  optimizationId: string;
  optimizationName: string;
  /** Feedback-score name the run's `objective_name` points at. */
  objectiveName: string;
  datasetId: string;
  datasetName: string;
  projectId: string;
  projectName: string;
  /** Baseline first, then the numbered trials, in seeded (and created) order. */
  candidates: RollupCandidateRef[];
  /** Every trial's spend added up — what the "Optimization cost" column totals. */
  totalCost: number;
}

export interface OptimizationRollupFixtures {
  fullyEvaluatedRun: OptimizationRollupRef;
  partiallyEvaluatedRun: OptimizationRollupRef;
  unscoredObjectiveRun: OptimizationRollupRef;
}

/** The metric these runs optimize; also the feedback-score name on each trace. */
export const ROLLUP_OBJECTIVE = 'equals';

/** A score under a name the run does NOT optimize, for the unscored-objective run. */
export const ROLLUP_OFF_OBJECTIVE_SCORE = 'coverage';

/** Every run is evaluated against a dataset of this many items. */
const DATASET_ITEM_COUNT = 3;

const DATASET_ITEMS = [
  { text: 'first review', label: 'positive' },
  { text: 'second review', label: 'negative' },
  { text: 'third review', label: 'positive' },
];

const SPAN_MODEL = 'gpt-4o-mini';
const SPAN_PROVIDER = 'openai';

/**
 * A run whose best trial beat the baseline on all three axes, evaluated on every
 * dataset item. Latency improves by 75% and cost by 50% — deliberately DIFFERENT
 * percentages, so a cell that read its neighbour's value could not pass.
 */
const FULLY_EVALUATED: RollupCandidateSeed[] = [
  { label: 'Baseline', stepIndex: 0, evaluatedItems: 3, durationSeconds: 4, costPerTrace: 0.008, score: 0.2 },
  { label: 'Trial #1', stepIndex: 1, evaluatedItems: 3, durationSeconds: 1, costPerTrace: 0.004, score: 0.9 },
];

/**
 * The shape OPIK-8060 exists for: the top-scoring candidate was evaluated on
 * 1 of the 3 items the baseline covered, so its 1.0 is a partial average and
 * must not win. Trial #2 is the best *fully* evaluated candidate, and every
 * "best" cell — score, latency and cost — has to come from it.
 */
const PARTIALLY_EVALUATED: RollupCandidateSeed[] = [
  { label: 'Baseline', stepIndex: 0, evaluatedItems: 3, durationSeconds: 3, costPerTrace: 0.009, score: 0.3 },
  { label: 'Trial #1', stepIndex: 1, evaluatedItems: 1, durationSeconds: 1, costPerTrace: 0.001, score: 1 },
  { label: 'Trial #2', stepIndex: 2, evaluatedItems: 3, durationSeconds: 2, costPerTrace: 0.0045, score: 0.6 },
];

/**
 * A run that spent money and scored its traces, but never under the name its
 * `objective_name` points at. The three "best" cells have nothing to report;
 * the run's total spend still does.
 */
const UNSCORED_OBJECTIVE: RollupCandidateSeed[] = [
  { label: 'Baseline', stepIndex: 0, evaluatedItems: 3, durationSeconds: 2, costPerTrace: 0.003, score: 0.5 },
  { label: 'Trial #1', stepIndex: 1, evaluatedItems: 3, durationSeconds: 1, costPerTrace: 0.002, score: 0.7 },
];

interface SeedArgs {
  sdkClient: SdkClient;
  backendClient: BackendClient;
  projectId: string;
  projectName: string;
  namespace: string;
  slug: string;
  candidates: RollupCandidateSeed[];
  /**
   * Feedback-score name written on every trace. Equal to the objective for a
   * scored run; something else for the unscored-objective run, which is what
   * proves the Best score cell is keyed on the objective rather than on "any
   * score this run happens to carry".
   */
  scoreName: string;
}

/**
 * Seed one completed optimization run: a dataset, an optimization row, and one
 * trial experiment per candidate whose traces carry a fixed duration, a fixed
 * span cost and a fixed feedback score.
 *
 * Seeded rather than launched, so nothing here depends on an optimizer or an
 * LLM. The rollups under test are pure arithmetic over these traces, so every
 * expected cell is knowable before the browser opens.
 *
 * Order is load-bearing twice over. The baseline's experiment is created FIRST
 * because `OptimizationDAO` takes the run's full-evaluation threshold from the
 * earliest-created candidate (`argMin(evaluated_count, earliest_created_at)`),
 * and the `baseline_*` rollups from the same candidate. And traces are linked to
 * dataset items from the first item onwards, so a candidate evaluated on fewer
 * items covers a strict *subset* of the baseline's — a partial evaluation, not a
 * different one.
 *
 * The optimization carries `project_name`: without it the run is absent from the
 * project-scoped list even though the REST collection returns it.
 */
async function seedRollupRun(args: SeedArgs): Promise<OptimizationRollupRef> {
  const { sdkClient, backendClient, projectName, projectId, namespace, slug, candidates } = args;

  const datasetName = `${namespace}-${slug}-ds`;
  const optimizationName = `${namespace}-${slug}-opt`;
  const optimizationId = uuid7();

  const dataset = await sdkClient.python.createDataset({
    project_name: projectName,
    name: datasetName,
    description: 'optimization runs-list rollups',
    items: DATASET_ITEMS as unknown as Array<Record<string, unknown>>,
  });

  const items = await backendClient.getDatasetItems(dataset.id);
  const datasetItemIds = items.map((i) => i.id);
  if (datasetItemIds.length !== DATASET_ITEM_COUNT) {
    throw new Error(
      `[optimizationRollup] expected ${DATASET_ITEM_COUNT} dataset items, got ${datasetItemIds.length}`,
    );
  }

  await backendClient.createOptimization({
    id: optimizationId,
    name: optimizationName,
    datasetName,
    projectName,
    objectiveName: ROLLUP_OBJECTIVE,
    status: 'completed',
  });

  const seeded: RollupCandidateRef[] = [];
  for (const candidate of candidates) {
    if (candidate.evaluatedItems > datasetItemIds.length) {
      throw new Error(
        `[optimizationRollup] ${candidate.label} evaluates ${candidate.evaluatedItems} items ` +
          `but the dataset only has ${datasetItemIds.length}`,
      );
    }

    const traceIds: string[] = [];
    for (let i = 0; i < candidate.evaluatedItems; i++) {
      const created = await sdkClient.python.createNestedTrace({
        project_name: projectName,
        name: `${namespace}-${slug}-${candidate.stepIndex}-${i + 1}`,
        input: { text: DATASET_ITEMS[i].text },
        output: { label: DATASET_ITEMS[i].label },
        duration_seconds: candidate.durationSeconds,
        feedback_scores: [{ name: args.scoreName, value: candidate.score }],
        spans: [
          {
            name: `${namespace}-${slug}-${candidate.stepIndex}-${i + 1}-llm`,
            type: 'llm',
            model: SPAN_MODEL,
            provider: SPAN_PROVIDER,
            // An explicit total_cost overrides the usage-derived estimate, so the
            // cost columns assert a number this fixture chose rather than one the
            // backend's price table produced — which would drift with the table.
            total_cost: candidate.costPerTrace,
          },
        ],
      });
      traceIds.push(created.id);
    }

    const experimentId = uuid7();
    const isBaseline = candidate.stepIndex === 0;
    await backendClient.createExperiment({
      id: experimentId,
      name: `${namespace}-${slug}-step-${candidate.stepIndex}`,
      datasetName,
      projectName,
      type: 'trial',
      optimizationId,
      metadata: {
        step_index: candidate.stepIndex,
        candidate_id: `${namespace}-${slug}-cand-${candidate.stepIndex}`,
        parent_candidate_ids: isBaseline ? [] : [`${namespace}-${slug}-cand-0`],
      },
    });
    await backendClient.createExperimentItems(
      traceIds.map((traceId, i) => ({
        experimentId,
        datasetItemId: datasetItemIds[i],
        traceId,
      })),
    );

    seeded.push({ ...candidate, experimentId, traceIds });
  }

  return {
    optimizationId,
    optimizationName,
    objectiveName: ROLLUP_OBJECTIVE,
    datasetId: dataset.id,
    datasetName,
    projectId,
    projectName,
    candidates: seeded,
    totalCost: seeded.reduce((acc, c) => acc + c.costPerTrace * c.evaluatedItems, 0),
  };
}

/**
 * Teardown for a seeded run. Experiments go before the optimization they belong
 * to and traces before the dataset, so nothing is removed from under a still-
 * referencing parent. It deletes more than the project fixture does:
 * `ProjectService.delete` removes only the project row — traces do NOT cascade —
 * and the run-prefix sweep in global-teardown knows nothing about optimizations.
 */
async function teardownRollupRun(
  backendClient: BackendClient,
  ref: OptimizationRollupRef,
): Promise<void> {
  const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
    try {
      await fn();
    } catch (err) {
      console.warn(`[optimizationRollup fixture] delete warning for ${what}:`, err);
    }
  };

  for (const candidate of ref.candidates) {
    await safe(`experiment ${candidate.experimentId}`, () =>
      backendClient.deleteExperiment(candidate.experimentId),
    );
  }
  await safe(`optimization ${ref.optimizationId}`, () =>
    backendClient.deleteOptimization(ref.optimizationId),
  );
  const traceIds = ref.candidates.flatMap((c) => c.traceIds);
  await safe(`${traceIds.length} traces`, () => backendClient.deleteTraces(traceIds));
  await safe(`dataset ${ref.datasetName}`, () => backendClient.deleteDataset(ref.datasetId));
}

/** Seed, hand to the test, then tear down — the body every run fixture shares. */
async function withRollupRun(
  args: SeedArgs,
  use: (ref: OptimizationRollupRef) => Promise<void>,
  testInfo: TestInfo,
): Promise<void> {
  const ref = await seedRollupRun(args);

  await testInfo.attach(`opik.${args.slug}Run`, {
    body: JSON.stringify(ref, null, 2),
    contentType: 'application/json',
  });

  await use(ref);

  if (!shouldLeaveArtifacts(testInfo)) {
    await teardownRollupRun(args.backendClient, ref);
  }
}

/**
 * Three completed optimization runs, each in its own test's project, covering
 * the three states the runs list has to report differently: a clean improvement,
 * a run whose top scorer is only partially evaluated, and a run whose objective
 * was never scored.
 *
 * They are separate fixtures rather than one that seeds all three, so a test
 * only pays for the run it reads.
 */
export const test = baseTest.extend<OptimizationRollupFixtures>({
  fullyEvaluatedRun: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    await withRollupRun(
      {
        sdkClient,
        backendClient,
        projectId: project.id,
        projectName: project.name,
        namespace: testNamespace,
        slug: 'full',
        candidates: FULLY_EVALUATED,
        scoreName: ROLLUP_OBJECTIVE,
      },
      use,
      testInfo,
    );
  },

  partiallyEvaluatedRun: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    await withRollupRun(
      {
        sdkClient,
        backendClient,
        projectId: project.id,
        projectName: project.name,
        namespace: testNamespace,
        slug: 'partial',
        candidates: PARTIALLY_EVALUATED,
        scoreName: ROLLUP_OBJECTIVE,
      },
      use,
      testInfo,
    );
  },

  unscoredObjectiveRun: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    await withRollupRun(
      {
        sdkClient,
        backendClient,
        projectId: project.id,
        projectName: project.name,
        namespace: testNamespace,
        slug: 'unscored',
        candidates: UNSCORED_OBJECTIVE,
        scoreName: ROLLUP_OFF_OBJECTIVE_SCORE,
      },
      use,
      testInfo,
    );
  },
});

export { expect } from './dashboard-cleanup.fixture';
