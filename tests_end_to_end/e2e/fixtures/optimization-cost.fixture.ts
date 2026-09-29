import { test as baseTest, expect } from './export-comparison.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * The model the priced spans are logged against, and the usage that makes its
 * price exact.
 *
 * `claude-haiku-4-5-20251001` bills $1/M input and $5/M output in the shipped
 * price table, so 1M + 1M is $6.00 on the nose — a whole number the rendered
 * `formatAsCurrency` shows as `$12.00` once two of them are summed, with no
 * rounding to reason about. Deliberately NO `total_cost` on the span: a seed
 * that supplies its own cost never exercises server-side pricing, and this
 * fixture's whole point is that the number under test is the server's.
 */
const PRICED_MODEL = 'claude-haiku-4-5-20251001';
const PRICED_PROVIDER = 'anthropic';
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;

/** What one seeded LLM span costs once the backend resolves its price. */
export const SPAN_COST = 6;

/**
 * The run's cost with both contributions in scope: one optimizer-internal
 * trace attributed by tag, plus one trial experiment's traced item.
 */
export const EXPECTED_TOTAL_COST = SPAN_COST * 2;

export interface OptimizationCostRef {
  optimizationId: string;
  optimizationName: string;
  projectId: string;
  projectName: string;
  datasetName: string;
  /**
   * The optimizer-internal trace: attributed to the run ONLY by carrying the
   * run id in its `tags`, with no experiment item anywhere. Removing that tag
   * is what must drop its $6.00 out of the total.
   */
  taggedTraceId: string;
  /**
   * The trial's evaluation trace: attributed through its experiment item, not
   * through a tag. Tagging it as well must NOT double-charge it.
   */
  trialTraceId: string;
  trialExperimentId: string;
  /**
   * A project in the same workspace with no optimization runs at all — the
   * empty state's subject. Seeded here rather than in the test so it is torn
   * down on a mid-test failure like everything else.
   */
  emptyProjectId: string;
  emptyProjectName: string;
}

export interface OptimizationCostFixtures {
  optimizationCost: OptimizationCostRef;
}

/**
 * A completed optimization run whose cost comes from exactly two places, one
 * per attribution path (OPIK-8333 / OPIK-7521).
 *
 * The rewrite this seeds for moved the tag test AFTER the experiment-item
 * dedup, so a tag a later write removes stops counting and a trace that is
 * already in an experiment item is never charged twice. Both halves are only
 * observable if the two paths contribute separately and the expected total is
 * known exactly — hence one $6.00 span on each side of the union and a model
 * whose price makes $12.00 exact.
 *
 * Seeded through the REST client rather than the SDK bridge for the same
 * reason `optimizationRun` is: the bridge only emits `source=sdk`, and it
 * normalises usage keys, while these spans need `source=optimization` and a
 * verbatim `usage` map with no `total_cost`. Ids are minted with `uuid7()`
 * up front because the REST writes answer 201 with no body and the spec
 * PATCHes these traces by id.
 *
 * Deliberately NOT extended to the week-floor drop-out case. Staging runs
 * `UuidV7TimestampValidator` in reject mode with a PT24H window, so a
 * backdated UUIDv7 is refused with 400 `too_old` and a trace old enough to
 * fall under the floor cannot be written through the public API at all. A spec
 * for it would not run here.
 *
 * Teardown deletes more than the project does: `ProjectService.delete` removes
 * only the project row, so traces do not cascade, and the run-prefix sweep in
 * `global-teardown` does not know about optimizations.
 */
export const test = baseTest.extend<OptimizationCostFixtures>({
  optimizationCost: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-ds`;
    const optimizationId = uuid7();
    const optimizationName = `${testNamespace}-opt`;
    const trialExperimentId = uuid7();
    const taggedTraceId = uuid7();
    const trialTraceId = uuid7();

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'optimization total cost attribution',
      items: [{ text: 'first review', label: 'positive' }] as unknown as Array<
        Record<string, unknown>
      >,
    });
    const datasetItemIds = (await backendClient.getDatasetItems(dataset.id)).map((i) => i.id);

    const emptyProjectName = `${testNamespace}-empty`;
    const emptyProject = await sdkClient.python.createProject({ name: emptyProjectName });

    /** One trace carrying exactly one priced LLM span, both written by id. */
    const seedPricedTrace = async (id: string, label: string, tags?: string[]): Promise<void> => {
      await backendClient.createTraceWithSource({
        id,
        projectName: project.name,
        name: `${testNamespace}-${label}`,
        source: 'optimization',
        input: { text: `${label} input` },
        output: { label: `${label} output` },
        endTime: new Date(),
      });
      await backendClient.createSpan({
        id: uuid7(),
        traceId: id,
        projectName: project.name,
        name: `${testNamespace}-${label}-llm`,
        source: 'optimization',
        type: 'llm',
        model: PRICED_MODEL,
        provider: PRICED_PROVIDER,
        usage: {
          prompt_tokens: PROMPT_TOKENS,
          completion_tokens: COMPLETION_TOKENS,
          total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
        },
      });
      if (tags?.length) {
        await backendClient.updateTraceTags({ traceId: id, projectName: project.name, tags });
      }
    };

    await backendClient.createOptimization({
      id: optimizationId,
      name: optimizationName,
      datasetName,
      projectName: project.name,
      objectiveName: 'equals',
      status: 'completed',
    });

    // Tagged with the run id and nothing else, so the tag is unambiguously
    // what puts this trace's cost in scope.
    await seedPricedTrace(taggedTraceId, 'optimizer-internal', [optimizationId]);
    await seedPricedTrace(trialTraceId, 'trial-eval');

    await backendClient.createExperiment({
      id: trialExperimentId,
      name: `${testNamespace}-trial-1`,
      datasetName,
      projectName: project.name,
      type: 'trial',
      optimizationId,
      metadata: {
        step_index: 1,
        candidate_id: `${testNamespace}-cand-1`,
        parent_candidate_ids: [],
      },
    });
    await backendClient.createExperimentItems([
      {
        experimentId: trialExperimentId,
        datasetItemId: datasetItemIds[0],
        traceId: trialTraceId,
      },
    ]);

    const ref: OptimizationCostRef = {
      optimizationId,
      optimizationName,
      projectId: project.id,
      projectName: project.name,
      datasetName,
      taggedTraceId,
      trialTraceId,
      trialExperimentId,
      emptyProjectId: emptyProject.id,
      emptyProjectName: emptyProject.name,
    };
    await testInfo.attach('opik.optimizationCost', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[optimizationCost fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiment before the optimization it belongs to, traces before the
      // dataset, so nothing is removed from under a still-referencing parent.
      await safe(`experiment ${trialExperimentId}`, () =>
        backendClient.deleteExperiment(trialExperimentId),
      );
      await safe(`optimization ${optimizationId}`, () =>
        backendClient.deleteOptimization(optimizationId),
      );
      await safe('2 traces', () =>
        backendClient.deleteTraces([taggedTraceId, trialTraceId]),
      );
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
      await safe(`project ${emptyProjectName}`, () =>
        backendClient.deleteProject(emptyProject.id),
      );
    }
  },
});

export { expect };
