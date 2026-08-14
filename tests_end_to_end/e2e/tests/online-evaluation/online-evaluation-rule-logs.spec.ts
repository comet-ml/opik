import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { resolveJudgeModel } from '@e2e/pom/model-availability';

const MODERATION_SCORE_NAME = 'Moderation'; // canned template's schema name

/**
 * The rule's Logs surface is the only place that distinguishes "the rule never
 * fired" from "the rule fired and the judge's answer could not be read". These
 * assertions are on the *engine's* log lines — emitted by
 * OnlineScoringEngine / OnlineScoringLlmAsJudgeScorer, not by the judge — so
 * the expected text is deterministic even though the score value is not.
 */
test.describe('Online Evaluation — rule logs', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A rule that scores a trace records sampling and score-storage entries in its logs, in the API and on the page', { tag: ['@cap:online-evaluation.automation-logs'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(240_000);

    const modelDisplayName = await resolveJudgeModel(page);

    const ruleName = `${testNamespace}-logs`;
    const onlineEval = new OnlineEvaluationPage(page);

    await test.step('Create an LLM-judge rule via the UI', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogLLMJudge({
        name: ruleName,
        template: 'Moderation',
        modelDisplayName,
      });
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
    });

    const ruleId = await test.step('Resolve the stored rule id', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const rule = rules.find((r) => r.name === ruleName);
      expect(rule, `rule "${ruleName}" must exist after creation`).toBeDefined();
      expect(rule!.schema.map((s) => s.name), 'the rule declares the template score').toEqual([
        MODERATION_SCORE_NAME,
      ]);
      return rule!.id;
    });

    const trace = await test.step('Seed one trace via the SDK', async () =>
      sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: 'evaluate this content',
        output: 'The capital of France is Paris.',
      }));

    // The two lines the engine emits per evaluated trace. Anchored on the
    // trace id so a log entry from another rule/trace can't satisfy them.
    const sampledLine = new RegExp(`Evaluating traceId '${trace.id}' sampled by rule '${ruleName}'`);
    const storedLine = new RegExp(`Scores for traceId '${trace.id}' stored successfully`);

    await test.step('Poll the rule logs until both entries appear', async () => {
      await expect
        .poll(
          async () => {
            const logs = await backendClient.getAutomationRuleLogs(ruleId);
            return {
              sampled: logs.some((l) => sampledLine.test(l.message)),
              stored: logs.some((l) => storedLine.test(l.message)),
            };
          },
          {
            message: `rule ${ruleId} should log sampling and storage for trace ${trace.id}`,
            timeout: 150_000,
            intervals: [2000, 5000],
          },
        )
        .toEqual({ sampled: true, stored: true });
    });

    await test.step('The storage entry names the score the rule declared', async () => {
      const logs = await backendClient.getAutomationRuleLogs(ruleId);
      const stored = logs.find((l) => storedLine.test(l.message))!;
      // The engine appends the stored scores grouped by name, so a rule that
      // fired but attributed the judge's answer to the wrong name is visible
      // here rather than silently producing nothing.
      expect(
        stored.message,
        'the stored-scores entry must name the declared score',
      ).toContain(MODERATION_SCORE_NAME);
      expect(stored.level, 'a successful evaluation logs at INFO').toBe('INFO');
    });

    await test.step('No entry reports an error for this trace', async () => {
      const logs = await backendClient.getAutomationRuleLogs(ruleId);
      const errors = logs.filter((l) => l.level === 'ERROR');
      expect(errors.map((l) => l.message), 'a clean evaluation logs no errors').toEqual([]);
    });

    await test.step('The rule\'s "Show logs" page renders the same entries', async () => {
      const logsPage = await onlineEval.openLogsForRule(ruleName);
      await logsPage.waitForReady();
      // Only the message's first line is rendered until a row is expanded, and
      // both engine lines carry the trace id on that first line.
      await expect(logsPage.rowsContaining(sampledLine)).toHaveCount(1);
      await expect(logsPage.rowsContaining(storedLine)).toHaveCount(1);
    });

    await test.step('Cleanup: delete the rule (project teardown does not cascade)', async () => {
      await backendClient.deleteAutomationRule(project.id, ruleId);
    });
  });
});
