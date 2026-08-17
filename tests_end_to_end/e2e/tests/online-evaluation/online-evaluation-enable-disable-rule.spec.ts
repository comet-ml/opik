import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

const REFERENCE_OUTPUT = 'seed output';

test.describe('Online Evaluation — enable/disable rule', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('Disabling a rule stops it scoring new traces; re-enabling resumes it', { tag: ['@cap:online-evaluation.enable-disable-rule'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(300_000);

    // Two deterministic Python rules: one gets toggled, the other stays enabled
    // as a control. The control is what turns "no score from the target" into a
    // real negative rather than a timing guess — once the control's score lands
    // on a trace created while the target was disabled, the engine has
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

    await test.step('Both rules start out Enabled in the list', async () => {
      await expect(onlineEval.ruleStatusCell(targetRule, 'Enabled')).toBeVisible();
      await expect(onlineEval.ruleStatusCell(controlRule, 'Enabled')).toBeVisible();
    });

    const preDisableTrace = await test.step('Seed a trace while both rules are enabled', async () =>
      sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-pre-disable`,
        input: 'whatever',
        output: REFERENCE_OUTPUT,
      }));

    await test.step('Both rules score the pre-disable trace', async () => {
      const [targetScore, controlScore] = await Promise.all([
        backendClient.pollTraceForFeedbackScore(preDisableTrace.id, targetRule, {
          timeoutMs: 90_000,
        }),
        backendClient.pollTraceForFeedbackScore(preDisableTrace.id, controlRule, {
          timeoutMs: 90_000,
        }),
      ]);
      expect(targetScore.value, 'target rule scores a matching trace while enabled').toBe(1.0);
      expect(controlScore.value, 'control rule scores a matching trace').toBe(1.0);
    });

    await test.step('Disable the target rule and verify the list', async () => {
      await onlineEval.setRuleEnabledByName(targetRule, false);
      await expect(onlineEval.ruleStatusCell(targetRule, 'Disabled')).toBeVisible();
      await expect(
        onlineEval.ruleStatusCell(controlRule, 'Enabled'),
        'disabling one rule must not touch the others',
      ).toBeVisible();
    });

    await test.step('The disabled state is persisted in the backend', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const byName = new Map(rules.map((r) => [r.name, r.enabled]));
      expect(byName.get(targetRule), 'target rule persisted as disabled').toBe(false);
      expect(byName.get(controlRule), 'control rule stays enabled').toBe(true);
    });

    const disabledTrace = await test.step('Seed a trace while the target is disabled', async () =>
      sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-while-disabled`,
        input: 'whatever',
        output: REFERENCE_OUTPUT,
      }));

    await test.step('Control rule scores the new trace; the disabled rule does not', async () => {
      const controlScore = await backendClient.pollTraceForFeedbackScore(
        disabledTrace.id,
        controlRule,
        { timeoutMs: 90_000 },
      );
      expect(controlScore.value, 'control rule still scores new traces').toBe(1.0);

      const trace = await backendClient.getTrace(disabledTrace.id);
      const scoreNames = (trace?.feedbackScores ?? []).map((fs) => fs.name);
      expect(
        scoreNames,
        'a disabled rule must not score traces created while it was disabled',
      ).not.toContain(targetRule);
    });

    await test.step('Re-enable the target rule', async () => {
      await onlineEval.setRuleEnabledByName(targetRule, true);
      await expect(onlineEval.ruleStatusCell(targetRule, 'Enabled')).toBeVisible();

      const rules = await backendClient.listAutomationRulesForProject(project.id);
      expect(
        rules.find((r) => r.name === targetRule)?.enabled,
        'target rule persisted as enabled again',
      ).toBe(true);
    });

    const reEnabledTrace = await test.step('Seed a trace after re-enabling', async () =>
      sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-post-enable`,
        input: 'whatever',
        output: REFERENCE_OUTPUT,
      }));

    await test.step('The re-enabled rule scores the new trace again', async () => {
      const targetScore = await backendClient.pollTraceForFeedbackScore(
        reEnabledTrace.id,
        targetRule,
        { timeoutMs: 90_000 },
      );
      expect(targetScore.value, 'scoring resumes once the rule is re-enabled').toBe(1.0);
    });

    await test.step('The trace seeded while disabled is still unscored by the target rule', async () => {
      const trace = await backendClient.getTrace(disabledTrace.id);
      const scoreNames = (trace?.feedbackScores ?? []).map((fs) => fs.name);
      expect(
        scoreNames,
        're-enabling must not backfill scores onto traces seen while disabled',
      ).not.toContain(targetRule);
    });

    await test.step('Cleanup: delete both rules (project teardown does not cascade)', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      for (const rule of rules) {
        await backendClient.deleteAutomationRule(project.id, rule.id);
      }
    });
  });
});
