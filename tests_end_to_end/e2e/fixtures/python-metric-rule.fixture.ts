import { test as baseTest } from './evaluated-thread.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface PythonMetricRuleRef {
  id: string;
  /** The rule's name, as the Online evaluation list renders it. */
  name: string;
  /**
   * The name the metric stamps on its `ScoreResult`, which is the name the
   * feedback score lands under. The engine ignores the rule name here, so the
   * two are tracked separately and both are needed to assert on a scored trace.
   */
  scoreName: string;
}

export interface CreatePythonMetricRuleSeed {
  /** Suffix appended to the test namespace; keep it short and unique per rule. */
  suffix: string;
  /**
   * Metric kwarg name -> the trace JSON path it resolves against, e.g.
   * `{ q: 'input.x', answer: 'output.answer' }`. This is the mapping the
   * Online evaluation rule form calls "variables".
   */
  arguments: Record<string, string>;
  /**
   * Python source for the rule, given the score name to stamp on its
   * `ScoreResult`. Exactly one `BaseMetric` subclass may be declared: the
   * python_evaluator backend picks the first subclass in the module
   * alphabetically, so an extra import would shadow it.
   */
  buildMetric(scoreName: string): string;
  samplingRate?: number;
}

export interface PythonMetricRuleFactory {
  create(seed: CreatePythonMetricRuleSeed): Promise<PythonMetricRuleRef>;
}

export interface PythonMetricRuleFixtures {
  pythonMetricRules: PythonMetricRuleFactory;
}

/**
 * Creates `user_defined_metric_python` online-evaluation rules on the fixture
 * project, over the REST write rather than the create-rule dialog — the dialog
 * cannot set a rule's variable mapping, which is the thing a mapping test owns.
 *
 * A factory rather than a single rule because the interesting scenarios need
 * two or more rules scoring the same traces, and every rule has to exist
 * *before* its traces are seeded: the sampler only ever sees trace-created and
 * trace-updated events, so a rule created afterwards never backfills.
 *
 * Teardown is here rather than in a trailing `test.step`: automation rules do
 * not cascade when their project is deleted and `global-teardown`'s run-prefix
 * sweep does not know about them, so a rule left behind by a failing test
 * outlives the run. A step at the end of the test is skipped the moment an
 * earlier step throws — which is exactly when the leak happens.
 */
export const test = baseTest.extend<PythonMetricRuleFixtures>({
  pythonMetricRules: async ({ project, backendClient, testNamespace }, use, testInfo) => {
    const created: PythonMetricRuleRef[] = [];

    const factory: PythonMetricRuleFactory = {
      async create(seed) {
        const name = `${testNamespace}-${seed.suffix}`;
        const id = await backendClient.createPythonMetricRule({
          projectId: project.id,
          name,
          metric: seed.buildMetric(name),
          arguments: seed.arguments,
          samplingRate: seed.samplingRate,
        });
        const ref: PythonMetricRuleRef = { id, name, scoreName: name };
        created.push(ref);
        return ref;
      },
    };

    await use(factory);

    if (!shouldLeaveArtifacts(testInfo)) {
      for (const rule of created) {
        try {
          await backendClient.deleteAutomationRule(project.id, rule.id);
        } catch (err) {
          console.warn(`[pythonMetricRules fixture] delete warning for ${rule.name}:`, err);
        }
      }
    } else {
      await testInfo.attach('opik.pythonMetricRules', {
        body: JSON.stringify(created, null, 2),
        contentType: 'application/json',
      });
    }
  },
});

export { expect } from './evaluated-thread.fixture';
