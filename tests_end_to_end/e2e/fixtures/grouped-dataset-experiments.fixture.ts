import { expect } from '@playwright/test';
import { test as baseTest } from './spans-empty-state-projects.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One experiment in a group, and the trace it reported against. */
export interface GroupedExperimentRef {
  id: string;
  name: string;
  traceId: string;
}

/** One dataset and the experiments grouped under it. */
export interface ExperimentGroupRef {
  datasetId: string;
  datasetName: string;
  experiments: GroupedExperimentRef[];
  /**
   * Whether this group's traces carry a priced LLM span.
   *
   * The variable the whole fixture exists to vary: a group with a cost and a
   * group with none render their Cost cell differently, and only the pair tells
   * "blank" apart from "zero".
   */
  priced: boolean;
}

export interface GroupedDatasetExperimentsRef {
  projectId: string;
  projectName: string;
  /** Two experiments, traces priced — the group whose Cost cell names an amount. */
  priced: ExperimentGroupRef;
  /** One experiment, trace unpriced — the group whose Cost cell is blank. */
  unpriced: ExperimentGroupRef;
  /** Both groups, for assertions about the group row set as a whole. */
  all: ExperimentGroupRef[];
  /** Every seeded trace's duration, in ms — so the group p50 is this exact value. */
  traceDurationMs: number;
}

export interface GroupedDatasetExperimentsFixtures {
  groupedDatasetExperiments: GroupedDatasetExperimentsRef;
}

/**
 * The model each group's span is logged on.
 *
 * `gpt-4o` because it is the control the estate's cost specs already use: it is
 * in `model_prices_and_context_window.json` under `openai` and resolves through
 * the plain exact-match rung of `findModelPriceUnderProvider`, so a group with
 * no cost here means the aggregate lost it rather than that the price lookup
 * missed. The unpriced group's model is namespaced per run so it can never
 * acquire a row: an id in no price table is how `total_estimated_cost` is left
 * absent rather than zero.
 */
const PRICED_MODEL = 'gpt-4o';

/**
 * Tokens per priced span. 1M + 1M at gpt-4o's published rates
 * ($2.50/M in, $10.00/M out) is $12.50 — far enough above the $0.01 display
 * floor that the rendered cell names an amount rather than "<$0.01", and far
 * enough from zero that a cell reading blank cannot be a rounding artifact.
 */
const PRICED_TOKENS = { prompt_tokens: 1_000_000, completion_tokens: 1_000_000, total_tokens: 2_000_000 };

/**
 * Every seeded trace's duration.
 *
 * The same value for every trace in both groups, deliberately: the group row
 * renders `duration.p50` over its own traces, and equal inputs make the median
 * that exact number instead of an interpolation between two. 4200ms rather than
 * a round 4000 so the rendered string ("4.2s") could not be produced by a cell
 * that had defaulted to something; and over 100ms, which is the bound
 * `formatMillisecondsAsSeconds` switches precision at.
 */
const TRACE_DURATION_MS = 4_200;

/** Experiments per group — different counts, so the "N exps" label discriminates. */
const PRICED_EXPERIMENTS = 2;
const UNPRICED_EXPERIMENTS = 1;

/** How long the group aggregations may take to report the seeded population. */
const AGGREGATION_TIMEOUT_MS = 180_000;

/**
 * One project holding two datasets' experiments, one group priced and one not —
 * the seed for the grouped Experiments list opik#9374 made the default
 * (OPIK-8654).
 *
 * The estate renders this view nowhere. `pom/experiments.page.ts` opens the
 * page with `groups=[]` on purpose, and says why in its own doc comment: the
 * grouped list gives every experiment row a composite `data-row-id`
 * (`<experimentId>|<groupRowId>|…`), so the id-based locators every existing
 * experiments spec uses only hold on the flat list. The consequence is that the
 * whole default path — the group rows, their aggregation cells, and the
 * auto-expansion — is unasserted, and 9374's own history shows the aggregation
 * cells churned four times.
 *
 * ## Why two groups, asymmetric
 *
 * Because the valuable claim is a DISTINCTION, and one group cannot pose it:
 *
 *  - The priced group's Cost cell must name the amount the aggregations
 *    endpoint reports. On its own that is satisfied by a cell that renders any
 *    number it is handed.
 *  - The unpriced group's Cost cell must be EMPTY while the experiment rows
 *    inside it render "-". That is the half a reader of the table cannot
 *    recover: a group row reading blank where it should read a number is
 *    indistinguishable from a group that genuinely has no cost, and only a
 *    comparison against the group's own aggregation can tell them apart.
 *
 * The experiment COUNTS differ (2 and 1) so the group label's "N exps" is a
 * verdict rather than a constant, and so `total_estimated_cost` and
 * `total_estimated_cost_avg` are different numbers in the priced group — the
 * table shows the second by default and a spec must not be satisfied by the
 * first.
 *
 * ## Why REST and not `evaluate()`
 *
 * The seed needs a trace whose span carries `usage` and NO `total_cost`, so the
 * server does its own price resolution — the same reason every cost fixture in
 * the estate writes spans this way. An `evaluate()` run would need a working
 * provider key (staging's are rejected upstream), would cost money, and its
 * durations and token counts would be properties of the run rather than facts
 * the spec knows.
 *
 * ## What the fixture proves before the browser opens
 *
 * That the aggregations endpoint reports exactly two groups, that the priced
 * one carries a cost and the unpriced one carries NO cost key at all, and that
 * both carry the p50 the seed set. Every UI assertion downstream is a
 * comparison against those numbers, so a seed that half-landed — or one whose
 * "unpriced" model had quietly acquired a price row — would otherwise make the
 * blank-cell assertion pass for the wrong reason, and read as coverage forever.
 *
 * Teardown deletes the experiments, then the datasets, then the traces.
 * Experiments do not cascade with their dataset and datasets do not cascade
 * with the project; the prefix sweep in `global-teardown.ts` covers experiments
 * and datasets as a backstop but not traces.
 */
export const test = baseTest.extend<GroupedDatasetExperimentsFixtures>({
  groupedDatasetExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const plan = [
      { key: 'priced', priced: true, count: PRICED_EXPERIMENTS },
      { key: 'unpriced', priced: false, count: UNPRICED_EXPERIMENTS },
    ] as const;

    const groups: ExperimentGroupRef[] = [];
    const traceIds: string[] = [];

    try {
      for (const spec of plan) {
        const datasetName = `${testNamespace}-grp-${spec.key}`;
        const dataset = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'grouped experiments list (opik#9374)',
          items: [{ input: `${spec.key} question`, expected_output: `${spec.key} answer` }],
        });
        const itemIds = await backendClient.listDatasetItemIds(dataset.id);
        if (itemIds.length !== 1) {
          throw new Error(
            `[groupedDatasetExperiments fixture] dataset '${datasetName}' stored ` +
              `${itemIds.length} items, expected 1`,
          );
        }

        const experiments: GroupedExperimentRef[] = [];
        for (let i = 0; i < spec.count; i += 1) {
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
          await backendClient.createSpansBatch({
            projectName: project.name,
            spans: [
              {
                id: uuid7(),
                traceId,
                name: `${testNamespace}-${spec.key}-span-${i}`,
                type: 'llm',
                source: 'sdk',
                provider: 'openai',
                // No `totalEstimatedCost`: the server must resolve it, which is
                // what makes "the unpriced group has none" a fact about the
                // price table rather than about the seed.
                model: spec.priced ? PRICED_MODEL : `${testNamespace}-unpriced-model`,
                usage: PRICED_TOKENS,
                startTime,
                endTime,
              },
            ],
          });

          const experimentId = uuid7();
          const experimentName = `${testNamespace}-${spec.key}-exp-${i}`;
          await backendClient.createExperiment({
            id: experimentId,
            name: experimentName,
            datasetName,
            projectName: project.name,
          });
          await backendClient.createExperimentItems([
            { experimentId, datasetItemId: itemIds[0], traceId },
          ]);
          experiments.push({ id: experimentId, name: experimentName, traceId });
        }

        groups.push({
          datasetId: dataset.id,
          datasetName,
          experiments,
          priced: spec.priced,
        });
      }

      const priced = groups.find((g) => g.priced)!;
      const unpriced = groups.find((g) => !g.priced)!;

      // The aggregations the UI renders out of, settled. Polled on the priced
      // group's cost specifically rather than on "two groups exist": the groups
      // appear as soon as the experiments do, while the cost arrives only once
      // the span has been priced and rolled up, and that is the number every
      // assertion downstream compares against.
      await expect
        .poll(
          async () => {
            const agg = await backendClient.getExperimentGroupAggregations({
              projectId: project.id,
            });
            return agg[priced.datasetId]?.totalEstimatedCostAvg ?? null;
          },
          {
            message:
              'the priced group must report a cost before the browser opens, or the blank-cell ' +
              'assertion downstream cannot be told from a seed that had not settled',
            timeout: AGGREGATION_TIMEOUT_MS,
            intervals: [2_000, 3_000, 5_000, 10_000],
          },
        )
        .toBeGreaterThan(0);

      const aggregations = await backendClient.getExperimentGroupAggregations({
        projectId: project.id,
      });

      // Exactly two, not at least two: the project is fresh, so a third group
      // means the grouping picked up something this fixture did not seed and
      // every count assertion downstream is about a different population.
      expect(
        Object.keys(aggregations).sort(),
        'the project must hold exactly the two seeded groups',
      ).toEqual([priced.datasetId, unpriced.datasetId].sort());

      for (const group of groups) {
        const entry = aggregations[group.datasetId];
        expect(entry, `group '${group.datasetName}' is reported`).toBeDefined();
        expect(entry.label, `group '${group.datasetName}' is labelled by its dataset name`).toBe(
          group.datasetName,
        );
        expect(
          entry.experimentCount,
          `group '${group.datasetName}' reports its experiment count`,
        ).toBe(group.experiments.length);
        expect(
          entry.durationP50,
          `group '${group.datasetName}' reports the p50 the seed set`,
        ).toBe(TRACE_DURATION_MS);
      }

      // The discriminating property, and the one most likely to rot: the
      // unpriced group must carry NO cost key. If its model ever acquired a
      // price row, the rendered-blank assertion would still pass — against a
      // cell that was blank for some other reason entirely.
      expect(
        aggregations[unpriced.datasetId].totalEstimatedCostAvg,
        `group '${unpriced.datasetName}' must report no average cost at all — an absent key is ` +
          'what the UI renders as a blank cell, and a zero would be rendered differently',
      ).toBeUndefined();
      expect(
        aggregations[unpriced.datasetId].totalEstimatedCost,
        `group '${unpriced.datasetName}' must report no total cost either`,
      ).toBeUndefined();

      const ref: GroupedDatasetExperimentsRef = {
        projectId: project.id,
        projectName: project.name,
        priced,
        unpriced,
        all: groups,
        traceDurationMs: TRACE_DURATION_MS,
      };

      await testInfo.attach('opik.groupedDatasetExperiments', {
        body: JSON.stringify({ ...ref, aggregations }, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        for (const group of groups) {
          for (const experiment of group.experiments) {
            try {
              await backendClient.deleteExperiment(experiment.id);
            } catch (err) {
              console.warn(
                `[groupedDatasetExperiments fixture] could not delete experiment ${experiment.name}:`,
                err,
              );
            }
          }
          try {
            await backendClient.deleteDataset(group.datasetId);
          } catch (err) {
            console.warn(
              `[groupedDatasetExperiments fixture] could not delete dataset ${group.datasetName}:`,
              err,
            );
          }
        }
        for (const traceId of traceIds) {
          try {
            await backendClient.deleteTraces([traceId]);
          } catch (err) {
            console.warn(
              `[groupedDatasetExperiments fixture] could not delete trace ${traceId}:`,
              err,
            );
          }
        }
      }
    }
  },
});

export { expect } from './spans-empty-state-projects.fixture';
