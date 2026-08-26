import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * Reserved variables are auto-filled to a sentinel and their row is then
 * DROPPED from the Variable mapping list — so "no row appeared" is the success
 * signal here, not a failure.
 *
 * That makes a hidden row indistinguishable from a variable the parser lost,
 * unless something else in the same prompt proves the parser ran. Every case
 * below therefore carries a near-miss control: a name one character away from a
 * reserved one, which must keep its row. Do not remove them — without a control
 * these tests pass against a dialog that has stopped detecting variables at all.
 */
const NEAR_MISS_JUDGE = 'tracey';
const NEAR_MISS_PYTHON = 'spansy';

/** A python metric whose `score()` signature drives the variable-mapping rows. */
function buildMetricWithSignature(scoreName: string, params: string[]): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}


class SignatureProbeMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, ${params.join(', ')}, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)`;
}

test.describe(
  'Online Evaluation — reserved template variables',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'The rule dialog auto-fills the reserved variables of the chosen scope and editor, and only those',
      { tag: ['@cap:online-evaluation.rule-scope-thread-span'] },
      async ({ project, page }) => {
        test.setTimeout(180_000);

        // What this pins: which reserved set the dialog hands each editor is
        // scope-dependent, and getting it wrong is silent — an un-auto-filled
        // `{{trace}}` maps to nothing and the judge is handed no structure,
        // with no error anywhere. The decisive cases are at span scope, which
        // no other spec reaches.
        //
        // No rule is created, so there is nothing to tear down: this test is
        // about the form, and stops at the form.

        const onlineEval = new OnlineEvaluationPage(page);
        await onlineEval.goto(project.id);
        await onlineEval.waitForReady();
        await onlineEval.openCreateRuleDialog();

        await test.step('LLM-judge at trace scope reserves {{trace}} and {{spans}}', async () => {
          await onlineEval.setEditorContent(
            `Judge {{trace}} and {{spans}} against {{input}}, unlike {{${NEAR_MISS_JUDGE}}}.`,
          );

          await expect(
            onlineEval.variableMappingHeader,
            'four variables in the prompt, two of them reserved and auto-filled away',
          ).toHaveText('Variable mapping (2)');
          await expect(onlineEval.variableMappingRow('input')).toHaveCount(1);
          await expect(
            onlineEval.variableMappingRow(NEAR_MISS_JUDGE),
            'the control proves the parser ran — without it, hidden and lost look alike',
          ).toHaveCount(1);
          await expect(onlineEval.variableMappingRow('trace')).toHaveCount(0);
          await expect(onlineEval.variableMappingRow('spans')).toHaveCount(0);
        });

        await test.step('LLM-judge at span scope reserves {{span}} — and stops reserving {{trace}}', async () => {
          await onlineEval.setScope('Span');
          // The scope change resets the editor, so the prompt is re-entered.
          await onlineEval.setEditorContent('Judge {{span}} against {{input}} and {{trace}}.');

          await expect(
            onlineEval.variableMappingHeader,
            'only {{span}} is reserved here, so two of the three rows survive',
          ).toHaveText('Variable mapping (2)');
          await expect(onlineEval.variableMappingRow('input')).toHaveCount(1);
          await expect(
            onlineEval.variableMappingRow('trace'),
            'trace is reserved at TRACE scope only — a span has no trace structure to inject',
          ).toHaveCount(1);
          await expect(onlineEval.variableMappingRow('span')).toHaveCount(0);
        });

        await test.step('The Code metric reserves `spans` — and not `trace`, which its backend ignores', async () => {
          await onlineEval.setScope('Trace');
          await onlineEval.setRuleType('Code metric');
          await onlineEval.setEditorContent(
            buildMetricWithSignature('probe', ['spans', 'trace', 'output', NEAR_MISS_PYTHON]),
          );

          await expect(
            onlineEval.variableMappingHeader,
            'the python reserved set is `spans` alone, so three of the four rows survive',
          ).toHaveText('Variable mapping (3)');
          await expect(onlineEval.variableMappingRow('output')).toHaveCount(1);
          await expect(onlineEval.variableMappingRow(NEAR_MISS_PYTHON)).toHaveCount(1);
          await expect(
            onlineEval.variableMappingRow('trace'),
            'the python scorer only injects `spans`, so auto-mapping `trace` would ' +
              'hand the metric a value nothing ever fills',
          ).toHaveCount(1);
          await expect(onlineEval.variableMappingRow('spans')).toHaveCount(0);
        });
      },
    );

    test(
      'An auto-filled reserved variable is persisted onto the created rule, not merely hidden',
      { tag: ['@cap:online-evaluation.rule-scope-thread-span'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        test.setTimeout(180_000);

        // The complement of the test above, and the half that actually matters
        // to a running rule. Hiding the row and writing the sentinel are two
        // different things: a dialog that hid the row without writing
        // `spans -> spans` would look identical in the form and hand the
        // evaluator an unmappable argument at scoring time.
        //
        // The rule does not cascade with its project, so `automationRulesCleanup`
        // owns its deletion.

        const ruleName = `${testNamespace}-reserved-spans`;

        await test.step('Create a Code-metric rule whose signature takes `spans` and `output`', async () => {
          const onlineEval = new OnlineEvaluationPage(page);
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRuleDialog();

          await onlineEval.dialog.getByRole('textbox', { name: 'Rule name' }).fill(ruleName);
          await onlineEval.setRuleType('Code metric');
          await onlineEval.setEditorContent(buildMetricWithSignature(ruleName, ['spans', 'output']));

          await expect(
            onlineEval.variableMappingHeader,
            '`spans` is auto-filled away, leaving `output` as the only row to map',
          ).toHaveText('Variable mapping (1)');
          // `output` is mapped by hand to the bare string rather than left at
          // the default whole-JSON-node path, so the two arguments asserted
          // below differ in origin: one the form filled, one the user did.
          await onlineEval.setVariableMapping('output', 'output.output');

          await onlineEval.submitRuleDialog();
          await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
        });

        await test.step('The persisted rule carries the sentinel mapping', async () => {
          const rules = await backendClient.listAutomationRulesForProject(project.id);
          const rule = rules.find((r) => r.name === ruleName);
          expect(rule, `the dialog's rule '${ruleName}' must exist server-side`).toBeDefined();

          const code = await backendClient.getAutomationRuleCode(rule!.id);
          // The whole map, not just the key we care about: an extra argument
          // here would be a mapping the form invented, which is as wrong as a
          // missing one.
          expect(code.arguments, 'the auto-filled sentinel was written, not just hidden').toEqual({
            spans: 'spans',
            output: 'output.output',
          });
        });
      },
    );
  },
);
