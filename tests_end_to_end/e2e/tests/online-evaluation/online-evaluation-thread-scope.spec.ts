import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { LogsPage } from '@e2e/pom/logs.page';
import { resolveJudgeModel } from '@e2e/pom/model-availability';

const FRUSTRATION_SCORE_NAME = 'User frustration'; // canned thread template's schema name

/**
 * A conversation that gets steadily less helpful, so the thread as a whole is
 * what carries the signal — a trace-scoped rule looking at any single turn
 * would not see it. Plain strings so the Threads view renders them verbatim.
 */
const TURNS = [
  {
    input: 'How do I reverse a list in Python?',
    output: 'You can reverse a list.',
  },
  {
    input: 'Yes, but how? Show me the code.',
    output: 'There are several approaches to reversing sequences.',
  },
  {
    input: 'You still have not given me any code. Please just show me the code.',
    output: 'Reversing a list is a common operation in many languages.',
  },
  {
    input:
      'This is the fourth time I have asked. Why is it so hard to get a straight answer out of you?',
    output: 'I understand your concern about list reversal.',
  },
] as const;

test.describe('Online Evaluation — thread scope', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A thread-scoped LLM-judge rule scores the whole conversation once the thread is closed', { tag: ['@cap:online-evaluation.rule-scope-thread-span'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(300_000);

    const modelDisplayName = await resolveJudgeModel(page);

    const ruleName = `${testNamespace}-thread-judge`;
    const onlineEval = new OnlineEvaluationPage(page);

    await test.step('Create a Thread-scoped User frustration rule via the UI', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogLLMJudge({
        name: ruleName,
        scope: 'Thread',
        template: 'User frustration',
        modelDisplayName,
        // Thread templates take no per-trace variable mapping: their single
        // {{context}} is the conversation the engine injects itself, and the
        // dialog renders no Variable mapping section at this scope.
        variableMappings: {},
      });
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
    });

    const ruleId = await test.step('The stored rule is thread-scoped and declares a DOUBLE score', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const rule = rules.find((r) => r.name === ruleName);
      expect(rule, `rule "${ruleName}" must exist after creation`).toBeDefined();
      expect(rule!.type, 'the rule is stored at thread scope').toBe('trace_thread_llm_as_judge');
      // DOUBLE, not INTEGER: an integer schema silently floors every fractional
      // verdict the judge produces, which is exactly the kind of wrongness that
      // leaves a plausible-looking score behind.
      expect(rule!.schema).toEqual([{ name: FRUSTRATION_SCORE_NAME, type: 'DOUBLE' }]);
      return rule!.id;
    });

    const threadId = `${testNamespace}-thread`;

    await test.step(`Seed a ${TURNS.length}-turn thread via the SDK`, async () => {
      for (const [index, turn] of TURNS.entries()) {
        await sdkClient.python.createTrace({
          project_name: project.name,
          name: `${testNamespace}-turn-${index + 1}`,
          input: turn.input,
          output: turn.output,
          thread_id: threadId,
        });
      }
    });

    await test.step('Wait for the thread to be readable, then close it', async () => {
      await expect
        .poll(
          async () =>
            (await backendClient.getThread({ projectName: project.name, threadId }))
              ?.numberOfMessages ?? 0,
          {
            message: `thread ${threadId} should group all ${TURNS.length} turns`,
            timeout: 60_000,
            intervals: [1000, 2000],
          },
        )
        .toBe(TURNS.length * 2);

      // Thread-scoped rules fire when a thread goes inactive. Closing it
      // explicitly is what makes this bounded — the alternative is waiting out
      // the workspace's inactivity timeout, which is minutes long and not a
      // property of this rule at all.
      await backendClient.closeThread({ projectName: project.name, threadId });
    });

    const score = await test.step('Poll the thread until the rule scores it', async () => {
      await expect
        .poll(
          async () => {
            const thread = await backendClient.getThread({
              projectName: project.name,
              threadId,
            });
            return (thread?.feedbackScores ?? []).map((fs) => fs.name);
          },
          {
            message: `thread ${threadId} should carry a "${FRUSTRATION_SCORE_NAME}" score after closing`,
            timeout: 180_000,
            intervals: [2000, 5000],
          },
        )
        .toContain(FRUSTRATION_SCORE_NAME);

      const thread = await backendClient.getThread({ projectName: project.name, threadId });
      return thread!.feedbackScores.find((fs) => fs.name === FRUSTRATION_SCORE_NAME)!;
    });

    await test.step('The score is a real number inside the template\'s rubric', async () => {
      // Asserted structurally, not against a fixed value: the judge's exact
      // verdict is not reproducible, but the shape it must arrive in is. A
      // fractional value surviving at all is the DOUBLE schema working.
      expect(Number.isFinite(score.value), `score value "${score.value}" is numeric`).toBe(true);
      expect(score.value, 'the template grades on a 0..1 rubric').toBeGreaterThanOrEqual(0);
      expect(score.value, 'the template grades on a 0..1 rubric').toBeLessThanOrEqual(1);
      expect(score.reason, 'the judge must justify a thread score').toBeTruthy();
    });

    await test.step('The score renders in the thread panel', async () => {
      const logs = new LogsPage(page);
      await logs.gotoThreads(project.id);
      await logs.waitForThreadsReady(threadId);

      const panel = await logs.openThreadById(threadId);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();
      expect(
        await panel.readFeedbackScoreValue(FRUSTRATION_SCORE_NAME),
        'the panel renders the value the API reported',
      ).toBeCloseTo(score.value, 1);
    });

    await test.step('Cleanup: delete the rule (project teardown does not cascade)', async () => {
      await backendClient.deleteAutomationRule(project.id, ruleId);
    });
  });
});
