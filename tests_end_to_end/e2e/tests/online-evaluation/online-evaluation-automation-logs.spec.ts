import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * A metric that scores 1.0 whenever it is reached at all.
 *
 * This spec is not about what the metric computes — it is about which traces
 * reach it. The `output` parameter has no default on purpose: the engine drops
 * a variable it cannot resolve, and a metric that tolerated a missing argument
 * would score the unresolvable trace anyway and erase the very distinction
 * under test.
 *
 * Do NOT import additional BaseMetric subclasses here: the python_evaluator
 * backend's get_metric_class iterates module classes alphabetically and picks
 * the first BaseMetric subclass, so an import would shadow this one.
 */
function buildReachedMetric(scoreName: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class ReachedRule(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)`;
}

test.describe(
  'Online Evaluation — automation logs',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test('A trace whose variable mapping cannot resolve fails on its own row while the rest of the batch scores, and the rule log shows both', { tag: ['@cap:online-evaluation.automation-logs'] }, async ({
      project,
      backendClient,
      testNamespace,
      page,
      automationRulesCleanup,
    }) => {
      test.setTimeout(180_000);

      const ruleName = `${testNamespace}-nested`;

      const rule = await test.step(
        'Create a Python rule with the nested mapping `output.output`',
        async () => {
          // A dotted path resolves to JsonPath `$.output`, which only walks an
          // OBJECT section. Against a bare-string output there is nothing to
          // walk, the variable drops, and the rule is asked to evaluate with no
          // arguments at all — the failure this spec is about.
          const created = await backendClient.createPythonAutomationRule({
            projectId: project.id,
            name: ruleName,
            metric: buildReachedMetric(ruleName),
            metricArguments: { output: 'output.output' },
          });
          expect(created.enabled, 'a disabled rule would score nothing').toBe(true);
          return created;
        },
      );

      const resolvable = { name: `${testNamespace}-resolvable`, output: { output: 'hello world' } };
      const unresolvable = { name: `${testNamespace}-unresolvable`, output: 'hello world' };

      const [resolvableId, unresolvableId] = await test.step(
        'Seed one object-output trace and one bare-string-output trace',
        async () =>
          Promise.all(
            [resolvable, unresolvable].map((seed) =>
              backendClient.createTraceWithRawOutput({
                projectName: project.name,
                name: seed.name,
                input: { question: 'whatever' },
                output: seed.output,
              }),
            ),
          ),
      );

      await test.step('The two traces really differ in output shape', async () => {
        // Without this the whole test could pass over two identically-shaped
        // traces that both happened to score — an assertion about containment
        // is meaningless if nothing was ever supposed to fail.
        expect(await backendClient.getTraceRawOutput(resolvableId)).toEqual(resolvable.output);
        expect(await backendClient.getTraceRawOutput(unresolvableId)).toEqual(
          unresolvable.output,
        );
      });

      await test.step('The object-output trace scores', async () => {
        const score = await backendClient.pollTraceForFeedbackScore(resolvableId, ruleName, {
          timeoutMs: 90_000,
        });
        expect(score.value).toBe(1.0);
      });

      const logs = await test.step(
        'The rule logs an error against the bare-string trace',
        async () => {
          // This is also the anchor for the absence assertion below. A trace
          // that has not been processed yet and a trace that failed both read
          // as "no score", so the negative is only safe once the engine has
          // said, on this trace's own row, that it is done with it.
          await expect
            .poll(
              async () =>
                (await backendClient.listAutomationRuleLogs(rule.id)).filter(
                  (l) => l.level === 'ERROR' && l.markers.trace_id === unresolvableId,
                ).length,
              {
                message: `rule ${ruleName} never logged an error for the unresolvable trace`,
                timeout: 90_000,
              },
            )
            .toBeGreaterThan(0);
          return backendClient.listAutomationRuleLogs(rule.id);
        },
      );

      await test.step('One bad trace did not take down the batch', async () => {
        const failed = await backendClient.getTrace(unresolvableId);
        expect(failed, 'the unresolvable trace must still exist').not.toBeNull();
        expect(
          failed!.feedbackScores,
          'a trace whose only variable could not resolve must not be scored on nothing',
        ).toEqual([]);

        // The whole log set, not just the row we went looking for: an error
        // logged against the trace that DID score would mean the failure leaked
        // across the batch, which is exactly the regression this asserts away.
        const errorTraceIds = logs
          .filter((l) => l.level === 'ERROR')
          .map((l) => l.markers.trace_id);
        expect(
          errorTraceIds,
          'the bare-string trace is the only one the rule errored on',
        ).toEqual([unresolvableId]);

        expect(
          logs.filter(
            (l) => l.markers.trace_id === resolvableId && l.message.includes('stored successfully'),
          ).length,
          'the object-output trace stored its score in the same batch',
        ).toBe(1);
      });

      await test.step('The Automation logs page shows the same story', async () => {
        const onlineEval = new OnlineEvaluationPage(page);
        await onlineEval.goto(project.id);
        await onlineEval.waitForReady();

        const logsPage = await onlineEval.openLogsForRule(ruleName);
        await logsPage.waitForReady();

        await expect(
          logsPage.rowsFor(unresolvableId, 'ERROR'),
          'the failing trace gets its own ERROR row',
        ).toHaveCount(1);
        await expect(
          logsPage.rowsFor(resolvableId, 'ERROR'),
          'the trace that scored must not appear as an error',
        ).toHaveCount(0);

        const storedRow = logsPage.rowsFor(resolvableId, 'INFO', {
          messageContains: 'stored successfully',
        });
        await expect(storedRow).toHaveCount(1);

        // The message column collapses to its first line, and everything that
        // says WHICH scores were stored lives after it. A page that rendered
        // only the headline would look identical to one rendering the whole
        // message, so the expand control is asserted from both sides.
        const message = logsPage.messageCell(storedRow);
        await expect(
          message,
          'the collapsed row shows the headline only',
        ).not.toContainText(ruleName);
        await logsPage.expandMessage(storedRow);
        await expect(
          message,
          'expanding reveals the score the rule actually stored',
        ).toContainText(ruleName);
      });
    });
  },
);
