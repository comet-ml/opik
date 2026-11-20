import { expect } from '@playwright/test';
import { test as baseTest } from './grouped-dataset-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One experiment in the group, and the traces it reported against. */
export interface ScoredGroupExperimentRef {
  id: string;
  name: string;
  traceIds: string[];
  /**
   * The value every one of this experiment's traces carries under
   * `SCORED_GROUP_SCORE_NAME`, or `null` when none of them is scored at all.
   */
  scoreValue: number | null;
}

export interface ScoredGroupExperimentsRef {
  projectId: string;
  projectName: string;
  datasetId: string;
  datasetName: string;
  /** Five traces, every one scored 1.0. */
  high: ScoredGroupExperimentRef;
  /** Two traces, every one scored 0.0 — deliberately a different count from `high`. */
  low: ScoredGroupExperimentRef;
  /** One trace, carrying no feedback score of any kind. */
  unscored: ScoredGroupExperimentRef;
  /** All three, for assertions about the group's population as a whole. */
  all: ScoredGroupExperimentRef[];
  /** Every trace the group holds — `high` + `low` + `unscored`. */
  traceCount: number;
  /**
   * The average the group row must report: the mean of the per-experiment
   * averages over the experiments that HAVE the score. See the file comment for
   * the two other numbers this seed could have produced.
   */
  expectedGroupAverage: number;
}

export interface ScoredGroupExperimentsFixtures {
  scoredGroupExperiments: ScoredGroupExperimentsRef;
}

/**
 * The one score name every seeded trace is scored under.
 *
 * Short and fixed rather than namespaced per run, because the spec asserts the
 * group row's Feedback Scores cell EXACTLY — `"<name> (avg): <value>"` — and a
 * name built from `testNamespace` would make that string a hundred characters
 * of worker and test title. Collision-free regardless: the aggregation is
 * scoped to one project and the project is seeded fresh per test, so the only
 * scores in it are these.
 */
export const SCORED_GROUP_SCORE_NAME = 'group-avg-probe';

/** Traces per experiment. The two scored counts DIFFER on purpose — see below. */
const HIGH_TRACES = 5;
const LOW_TRACES = 2;
const UNSCORED_TRACES = 1;

/** The value every trace of the respective experiment carries. */
const HIGH_VALUE = 1;
const LOW_VALUE = 0;

/** Dataset items, one per trace of the largest experiment. */
const DATASET_ITEMS = HIGH_TRACES;

/** Every seeded trace's duration, so the seed is deterministic end to end. */
const TRACE_DURATION_MS = 1_500;

/** How long the per-experiment and group aggregations may take to settle. */
const AGGREGATION_TIMEOUT_MS = 180_000;

/**
 * One dataset's group of three experiments — two scored at opposite ends over
 * DIFFERENT numbers of traces, one not scored at all — for the Feedback Scores
 * aggregate on a group row of the default grouped Experiments list.
 *
 * ## Why this is not `groupedDatasetExperiments`
 *
 * `grouped-dataset-experiments.fixture.ts` seeds the same page and its spec
 * says in as many words what it could not reach: "Deliberately NOT asserted:
 * Pass rate and Feedback scores on group rows. Both need scored experiment
 * items, and both render empty here." The taxonomy note for
 * `experiments.group-row-aggregates` repeats it as NOT covered. That is the
 * hole this fills, and it needs a seed the other fixture cannot be adjusted
 * into without changing what its own blank-versus-dash assertions mean: there,
 * every group must render an EMPTY Feedback Scores cell.
 *
 * ## The three numbers this seed tells apart
 *
 * `FIND_GROUPS_AGGREGATIONS` builds one feedback-score map per experiment
 * (`feedback_scores_agg`, an `avg` over that experiment's own scored traces) and
 * then rolls the group up with ClickHouse `avgMap`, whose `-Map` combinator
 * averages a key only over the rows that CARRY it. So the group's average is
 * the mean of per-experiment means over the scored experiments — and the counts
 * are asymmetric precisely so that the three plausible implementations give
 * three different, non-coincidental answers:
 *
 *  - mean of per-experiment means, unscored excluded: `(1.0 + 0.0) / 2` = 0.5
 *  - mean over scored TRACES: `5 / 7` = 0.714…, which the cell renders "0.71"
 *  - mean over ALL experiments, unscored counted as zero: `1.0 / 3` = "0.33"
 *
 * A symmetric seed (five and five, which is what the release pass explored by
 * hand) collapses the first two to the same 0.5 and can only rule out the
 * third. The exploration's own `evaluate()` run confirmed the third is already
 * ruled out on staging; this shape is what also separates per-experiment from
 * per-trace, and 0.5 remains the answer either way the exploration's numbers
 * are read.
 *
 * ## What it proves before the browser opens
 *
 * Every input to that arithmetic, independently of the group read:
 *
 *  - each experiment's OWN aggregated score, off `GET /experiments/{id}` — 1.0,
 *    0.0, and none at all;
 *  - the group reports all three experiments and all eight traces, so the
 *    unscored experiment is demonstrably IN the group while being out of the
 *    mean. Without that half, "the unscored one is excluded" is indistinguish-
 *    able from "the unscored one was never ingested";
 *  - exactly one score name in the group's map, so the exact-text assertion on
 *    the rendered cell downstream is comparing a whole answer and not a
 *    substring of a longer one.
 *
 * Polling settles the population (counts, and the score key being present), not
 * the value: a poll that waited for 0.5 would report a build that computes
 * 0.714 as a timeout in the fixture rather than as a failed comparison naming
 * both numbers.
 *
 * ## Why REST rather than `evaluate()`
 *
 * The same reason the sibling fixture gives: `evaluate()` needs a working
 * provider key (staging's are rejected upstream), costs money, and would make
 * the per-trace scores a property of a judge's output rather than facts the
 * spec knows. Scores go on through `PUT /v1/private/traces/feedback-scores`,
 * which is scoped by project name and merges, so it is stable immediately after
 * the trace write and needs no poll of its own.
 *
 * Teardown deletes the experiments, then the dataset, then the traces — none of
 * the three cascades with the project, and `global-teardown.ts`'s prefix sweep
 * covers experiments and datasets but not traces.
 */
export const test = baseTest.extend<ScoredGroupExperimentsFixtures>({
  scoredGroupExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const plan = [
      { key: 'high', traces: HIGH_TRACES, score: HIGH_VALUE as number | null },
      { key: 'low', traces: LOW_TRACES, score: LOW_VALUE as number | null },
      { key: 'unscored', traces: UNSCORED_TRACES, score: null },
    ] as const;

    const seeded: ScoredGroupExperimentRef[] = [];
    const allTraceIds: string[] = [];
    let datasetId: string | null = null;
    const datasetName = `${testNamespace}-scored-grp`;

    try {
      const dataset = await sdkClient.python.createDataset({
        project_name: project.name,
        name: datasetName,
        description: 'feedback-score aggregate on a grouped experiments row (opik#9492)',
        items: Array.from({ length: DATASET_ITEMS }, (_unused, index) => ({
          input: `scored group question ${index}`,
          expected_output: `scored group answer ${index}`,
        })),
      });
      datasetId = dataset.id;

      const itemIds = await backendClient.listDatasetItemIds(dataset.id);
      if (itemIds.length !== DATASET_ITEMS) {
        throw new Error(
          `[scoredGroupExperiments fixture] dataset '${datasetName}' stored ${itemIds.length} ` +
            `items, expected ${DATASET_ITEMS}`,
        );
      }

      for (const spec of plan) {
        const experimentId = uuid7();
        const experimentName = `${testNamespace}-${spec.key}-exp`;
        const traceIds: string[] = [];

        for (let i = 0; i < spec.traces; i += 1) {
          const endTime = new Date();
          const startTime = new Date(endTime.getTime() - TRACE_DURATION_MS);
          const traceId = uuid7();
          await backendClient.createTraceWithSource({
            id: traceId,
            projectName: project.name,
            name: `${testNamespace}-${spec.key}-trace-${i}`,
            source: 'sdk',
            input: { question: `${spec.key} ${i}` },
            output: { answer: `${spec.key} ${i}` },
            startTime,
            endTime,
          });
          traceIds.push(traceId);
          allTraceIds.push(traceId);
        }

        await backendClient.createExperiment({
          id: experimentId,
          name: experimentName,
          datasetName,
          projectName: project.name,
        });
        await backendClient.createExperimentItems(
          traceIds.map((traceId, i) => ({
            experimentId,
            // Each trace against its own dataset item, so the experiment's
            // items are distinct rows rather than repeats of one.
            datasetItemId: itemIds[i],
            traceId,
          })),
        );

        if (spec.score !== null) {
          await backendClient.setTraceFeedbackScores({
            projectName: project.name,
            scores: traceIds.map((traceId) => ({
              traceId,
              name: SCORED_GROUP_SCORE_NAME,
              value: spec.score as number,
            })),
          });
        }

        seeded.push({
          id: experimentId,
          name: experimentName,
          traceIds,
          scoreValue: spec.score,
        });
      }

      const high = seeded.find((e) => e.scoreValue === HIGH_VALUE)!;
      const low = seeded.find((e) => e.scoreValue === LOW_VALUE)!;
      const unscored = seeded.find((e) => e.scoreValue === null)!;
      const traceCount = HIGH_TRACES + LOW_TRACES + UNSCORED_TRACES;
      const expectedGroupAverage = (HIGH_VALUE + LOW_VALUE) / 2;

      // Each experiment's OWN aggregate first. These are the inputs to the
      // group's arithmetic, and settling them separately is what makes the
      // group comparison downstream a statement about the ROLL-UP rather than
      // about whether the scores had landed.
      //
      // `...OrNone` and not `getExperimentFeedbackScores`: the unscored
      // experiment reporting NO `feedback_scores` array is the state this seed
      // is for, and the strict helper throws on it by design — so using it here
      // would turn the seed's own proof into a fixture error.
      await expect
        .poll(
          async () => {
            const perExperiment = await Promise.all(
              seeded.map(async (experiment) => {
                const scores = await backendClient.getExperimentFeedbackScoresOrNone(
                  experiment.id,
                );
                // The whole map, not just the seeded name: an experiment that
                // had acquired a second score would change what the group's
                // rendered cell says, and this is where that is visible.
                return scores === null ? null : scores;
              }),
            );
            return JSON.stringify(perExperiment);
          },
          {
            message:
              'each seeded experiment must report its own average under ' +
              `'${SCORED_GROUP_SCORE_NAME}' — ${HIGH_VALUE} for the high one, ${LOW_VALUE} for ` +
              'the low one and no score array at all for the unscored one — before the group ' +
              'roll-up can be read as anything but a half-landed seed',
            timeout: AGGREGATION_TIMEOUT_MS,
            intervals: [2_000, 3_000, 5_000, 10_000],
          },
        )
        .toBe(
          JSON.stringify(
            seeded.map((e) =>
              e.scoreValue === null ? null : { [SCORED_GROUP_SCORE_NAME]: e.scoreValue },
            ),
          ),
        );

      // Then the group's population, settled on the counts and on the score key
      // being present at all — never on its value.
      await expect
        .poll(
          async () => {
            const agg = await backendClient.getExperimentGroupAggregations({
              projectId: project.id,
            });
            const entry = agg[dataset.id];
            if (entry === undefined) return 'no group for the seeded dataset';
            if (entry.feedbackScores?.[SCORED_GROUP_SCORE_NAME] === undefined) {
              return `group reports no '${SCORED_GROUP_SCORE_NAME}' yet`;
            }
            return `${entry.experimentCount}/${entry.traceCount}`;
          },
          {
            message:
              `the group must report all ${seeded.length} experiments and all ${traceCount} ` +
              'traces, and a score under the seeded name, before the browser opens',
            timeout: AGGREGATION_TIMEOUT_MS,
            intervals: [2_000, 3_000, 5_000, 10_000],
          },
        )
        .toBe(`${seeded.length}/${traceCount}`);

      const aggregations = await backendClient.getExperimentGroupAggregations({
        projectId: project.id,
      });

      // Exactly one group, not at least one: the project is seeded fresh, so a
      // second group means the grouping picked up something this fixture did
      // not seed and every number below is about a different population.
      expect(
        Object.keys(aggregations),
        'the fresh project must hold exactly the one seeded group',
      ).toEqual([dataset.id]);

      const entry = aggregations[dataset.id];
      expect(entry.label, 'the group is labelled by its dataset name').toBe(datasetName);

      // The half that makes "the unscored experiment is excluded from the mean"
      // a claim about the AVERAGE rather than about ingestion: it is in the
      // group, and the group counts its trace.
      expect(entry.experimentCount, 'the group counts the unscored experiment too').toBe(
        seeded.length,
      );
      expect(entry.traceCount, "the group counts the unscored experiment's trace too").toBe(
        traceCount,
      );

      expect(
        entry.feedbackScores,
        'the group must report a feedback-score map at all',
      ).toBeDefined();
      // Exactly one name. The rendered cell joins the WHOLE map, so a spec
      // comparing it against one name would pass against a cell that also
      // named scores this seed never wrote.
      expect(
        Object.keys(entry.feedbackScores!),
        'the group must report exactly the one seeded score name',
      ).toEqual([SCORED_GROUP_SCORE_NAME]);

      const ref: ScoredGroupExperimentsRef = {
        projectId: project.id,
        projectName: project.name,
        datasetId: dataset.id,
        datasetName,
        high,
        low,
        unscored,
        all: seeded,
        traceCount,
        expectedGroupAverage,
      };

      await testInfo.attach('opik.scoredGroupExperiments', {
        body: JSON.stringify({ ...ref, aggregations }, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        for (const experiment of seeded) {
          try {
            await backendClient.deleteExperiment(experiment.id);
          } catch (err) {
            console.warn(
              `[scoredGroupExperiments fixture] could not delete experiment ${experiment.name}:`,
              err,
            );
          }
        }
        if (datasetId !== null) {
          try {
            await backendClient.deleteDataset(datasetId);
          } catch (err) {
            console.warn(
              `[scoredGroupExperiments fixture] could not delete dataset ${datasetName}:`,
              err,
            );
          }
        }
        for (const traceId of allTraceIds) {
          try {
            await backendClient.deleteTraces([traceId]);
          } catch (err) {
            console.warn(
              `[scoredGroupExperiments fixture] could not delete trace ${traceId}:`,
              err,
            );
          }
        }
      }
    }
  },
});

export { expect } from './grouped-dataset-experiments.fixture';
