import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';

/**
 * A metric that exits 0 without ever printing its result line.
 *
 * `os._exit` is deliberate: it terminates the interpreter immediately, so the
 * runner's own "print the ScoreResult" step never happens and the process still
 * reports success. That is the exact shape the python evaluator used to
 * mis-handle — `parse_execution_result` indexed the empty output list, and the
 * IndexError surfaced as an opaque 500 that the backend then RETRIED. It is now
 * classified as a client-side 400 naming the cause.
 *
 * A metric that merely raised would not reproduce this: a non-zero exit code
 * takes a different branch entirely.
 */
function buildSilentMetric(scoreName: string): string {
  return `import os
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class SilentMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        os._exit(0)`;
}

/**
 * A metric that exits 0 having printed something that is not the result JSON.
 *
 * The sibling branch of the same fix: exit code 0 whose LAST line does not
 * parse as JSON. `flush=True` matters — `os._exit` skips interpreter shutdown,
 * so an unflushed stdout buffer would be discarded and this metric would
 * degenerate into the silent one above, testing the same branch twice.
 */
function buildUnparseableMetric(scoreName: string): string {
  return `import os
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class UnparseableMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        print("this line is not a score result", flush=True)
        os._exit(0)`;
}

/** The control: a well-behaved metric, proving the pipeline works at all. */
function buildControlMetric(scoreName: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class ControlMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)`;
}

/**
 * The 500-era wording. The whole point of the fix is that a metric which
 * misbehaves gets told WHAT went wrong instead of this.
 */
const OPAQUE_FAILURE_MESSAGE = 'An unexpected error occurred';

/** Emitted once per evaluator call, immediately before the HTTP request. */
const EVALUATOR_CALL_LINE = 'to Python evaluator';

test.describe('Online Evaluation — python metric failure classification', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A python metric that exits 0 without a result line fails with a 400 naming the cause, and is not re-attempted', { tag: ['@cap:online-evaluation.python-rule-scores'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    // No page: the subject is how the backend classifies an evaluator outcome,
    // and the rule log stream is where that classification is stated. Driving a
    // browser to read the same lines second-hand would be slower and would add
    // a rendering failure mode to an assertion that has nothing to do with
    // rendering.

    const controlRuleName = `${testNamespace}-control`;
    const silentRuleName = `${testNamespace}-silent`;
    const unparseableRuleName = `${testNamespace}-unparseable`;

    const rules = await test.step('Create one healthy and two failing python rules', async () => {
      const create = (name: string, metric: string) =>
        backendClient.createAutomationRule({
          projectId: project.id,
          name,
          samplingRate: 1,
          metric,
          // A resolvable mapping is mandatory: the backend refuses to call the
          // evaluator with an empty argument map, which would fail these rules
          // before their metric ever ran.
          arguments: { output: 'output.output' },
        });
      return {
        control: await create(controlRuleName, buildControlMetric(controlRuleName)),
        silent: await create(silentRuleName, buildSilentMetric(silentRuleName)),
        unparseable: await create(
          unparseableRuleName,
          buildUnparseableMetric(unparseableRuleName),
        ),
      };
    });

    const trace = await test.step('Seed one trace for all three rules to judge', async () => {
      // One trace, three rules: the control and the failures are then provably
      // judging identical input, so a difference in outcome is a difference in
      // the metric and not in what it was given.
      return sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: 'whatever',
        output: 'seed output',
      });
    });

    await test.step('Control: the healthy rule scored the trace', async () => {
      // Establishes that the python evaluator is reachable and this project's
      // rules are firing. Without it, two rules that logged nothing would be
      // indistinguishable from two rules that were never invoked.
      const score = await backendClient.pollTraceForFeedbackScore(trace.id, controlRuleName, {
        timeoutMs: 180_000,
      });
      expect(score.value, 'the control metric returns a constant 1.0').toBe(1.0);
    });

    const waitForRuleLogs = async (ruleId: string, ruleName: string) => {
      let logs: AutomationRuleLogRef[] = [];
      await expect
        .poll(
          async () => {
            logs = await backendClient.getAutomationRuleLogs(ruleId);
            // The failure line is written last in the scorer's chain, so its
            // arrival is what makes the stream complete for this rule.
            return logs.some((l) => l.level === 'ERROR');
          },
          {
            timeout: 180_000,
            intervals: [2_000, 5_000],
            message: `rule '${ruleName}' never reported a failure — its metric cannot succeed, so a silent stream means the rule was never invoked`,
          },
        )
        .toBe(true);
      return logs;
    };

    const silentLogs = await test.step(
      'The no-output rule reports a 400 that names the missing result line',
      async () => {
        const logs = await waitForRuleLogs(rules.silent, silentRuleName);
        const errors = logs.filter((l) => l.level === 'ERROR');
        expect(
          errors.map((l) => l.message).join('\n---\n'),
          'the failure must state the classified cause, at 400 rather than 500',
        ).toContain('400 Bad Request: Execution failed: the metric produced no output');
        return logs;
      },
    );

    const unparseableLogs = await test.step(
      'The non-JSON-output rule reports a 400 that names the unparseable result',
      async () => {
        const logs = await waitForRuleLogs(rules.unparseable, unparseableRuleName);
        const errors = logs.filter((l) => l.level === 'ERROR');
        expect(
          errors.map((l) => l.message).join('\n---\n'),
          'a last line that is not the result JSON is the client metric being wrong, not the server',
        ).toContain('400 Bad Request: Execution failed: the metric returned an unparseable result');
        return logs;
      },
    );

    await test.step('Neither failure fell back to the opaque 500 wording', async () => {
      for (const [name, logs] of [
        [silentRuleName, silentLogs],
        [unparseableRuleName, unparseableLogs],
      ] as const) {
        for (const line of logs) {
          expect(
            line.message,
            `rule '${name}' must not report the pre-classification catch-all`,
          ).not.toContain(OPAQUE_FAILURE_MESSAGE);
        }
      }
    });

    await test.step('Each failing rule called the evaluator once and reported once', async () => {
      // A 400 is a terminal answer: the caller must not re-run the metric
      // hoping for a different one. Both counts are asserted because they fail
      // differently — a re-queued message repeats the call line, while a retry
      // loop that eventually gives up repeats only the error line.
      for (const [name, logs] of [
        [silentRuleName, silentLogs],
        [unparseableRuleName, unparseableLogs],
      ] as const) {
        expect(
          logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)),
          `rule '${name}' must send the trace to the evaluator exactly once`,
        ).toHaveLength(1);
        expect(
          logs.filter((l) => l.level === 'ERROR'),
          `rule '${name}' must report its terminal failure exactly once`,
        ).toHaveLength(1);
      }
    });

    await test.step('A failed evaluation writes no score', async () => {
      // The complement of the control. A rule that failed but still stored
      // something would be worse than one that failed loudly.
      const detail = await backendClient.getTrace(trace.id);
      expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
      expect(
        detail!.feedbackScores.map((s) => s.name).sort(),
        'only the control rule may have written a score',
      ).toEqual([controlRuleName]);
    });
  });
});
