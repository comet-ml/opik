import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

const REFERENCE_OUTPUT = 'seed output';

test.describe('Online Evaluation — delete rule', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('Deleting a rule removes it from the list and stops it scoring new traces', { tag: ['@cap:online-evaluation.delete-rule'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(240_000);

    // Two deterministic Python rules: one is the delete target, the other
    // survives as a control. The control is what makes the post-delete
    // assertion a real negative instead of a timing guess — once the control's
    // score lands on a trace created after the deletion, the engine has
    // demonstrably processed that trace, so the target's absent score means
    // "not scored", not "not scored yet".
    const targetRule = `${testNamespace}-target`;
    const controlRule = `${testNamespace}-control`;

    const onlineEval = new OnlineEvaluationPage(page);

    await test.step('Create the target and control rules via the UI', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();

      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogPythonEquals({
        name: targetRule,
        referenceValue: REFERENCE_OUTPUT,
      });
      await expect(onlineEval.ruleRow(targetRule)).toBeVisible();

      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogPythonEquals({
        name: controlRule,
        referenceValue: REFERENCE_OUTPUT,
      });
      await expect(onlineEval.ruleRow(controlRule)).toBeVisible();
    });

    const preDeleteTrace = await test.step('Seed a trace while both rules are live', async () =>
      sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-pre-delete`,
        input: 'whatever',
        output: REFERENCE_OUTPUT,
      }));

    await test.step('Both rules score the pre-delete trace', async () => {
      const [targetScore, controlScore] = await Promise.all([
        backendClient.pollTraceForFeedbackScore(preDeleteTrace.id, targetRule, {
          timeoutMs: 90_000,
        }),
        backendClient.pollTraceForFeedbackScore(preDeleteTrace.id, controlRule, {
          timeoutMs: 90_000,
        }),
      ]);
      expect(targetScore.value, 'target rule scores a matching trace').toBe(1.0);
      expect(controlScore.value, 'control rule scores a matching trace').toBe(1.0);
    });

    await test.step('Delete the target rule and verify the list', async () => {
      await onlineEval.deleteRuleByName(targetRule);
      await expect(onlineEval.ruleRow(targetRule)).toHaveCount(0);
      await expect(
        onlineEval.ruleRow(controlRule),
        'deleting one rule must not touch the others',
      ).toBeVisible();
    });

    await test.step('The deleted rule is gone from the backend too', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      expect(rules.map((r) => r.name)).toEqual([controlRule]);
    });

    const postDeleteTrace = await test.step('Seed a trace after the deletion', async () =>
      sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-post-delete`,
        input: 'whatever',
        output: REFERENCE_OUTPUT,
      }));

    await test.step('Control rule scores the new trace; the deleted rule does not', async () => {
      const controlScore = await backendClient.pollTraceForFeedbackScore(
        postDeleteTrace.id,
        controlRule,
        { timeoutMs: 90_000 },
      );
      expect(controlScore.value, 'control rule still scores new traces').toBe(1.0);

      const trace = await backendClient.getTrace(postDeleteTrace.id);
      const scoreNames = (trace?.feedbackScores ?? []).map((fs) => fs.name);
      expect(
        scoreNames,
        'the deleted rule must not score traces created after its deletion',
      ).not.toContain(targetRule);
    });

    await test.step('Cleanup: delete the control rule (project teardown does not cascade)', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      for (const rule of rules) {
        await backendClient.deleteAutomationRule(project.id, rule.id);
      }
    });
  });
});
