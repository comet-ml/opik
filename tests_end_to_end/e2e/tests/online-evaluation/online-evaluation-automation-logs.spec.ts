import { test, expect } from '@e2e/fixtures';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';
import type { AutomationRuleLogRef } from '@e2e/core/backend';

const MODERATION_SCORE_NAME = 'Moderation'; // canned template's schema name

/** Escape a value for embedding in a RegExp source (trace ids and rule names). */
function rx(literal: string): string {
  return literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The engine's own structural log lines for one LLM-judge evaluation of one
 * trace, in the order it emits them (OnlineScoringLlmAsJudgeScorer).
 *
 * These are deliberately the *structural* lines only — none of them contains
 * LLM-produced text, so the assertions stay deterministic. "Received response
 * for traceId '<id>': '<the judge's answer>'" is emitted between the last two
 * and is intentionally not asserted on: its body is model output.
 */
function lifecyclePatterns(traceId: string, ruleName: string, modelName: string) {
  return [
    {
      phase: 'Evaluating',
      pattern: new RegExp(`^\\s*Evaluating traceId '${rx(traceId)}' sampled by rule '${rx(ruleName)}'\\s*$`),
    },
    {
      phase: 'Sending to LLM',
      // AgenticScoringService.summarizeRequest:
      //   "model='<id>', messages=<n>, tools=<n>, toolsEnabled=<bool>"
      // The model id is asserted exactly — it is the whole point of the line —
      // while the counts are left as \d+ because they track prompt shape, not
      // the claim under test.
      pattern: new RegExp(
        `^\\s*Sending traceId '${rx(traceId)}' to LLM: model='${rx(modelName)}', ` +
          `messages=\\d+, tools=\\d+, toolsEnabled=(?:true|false)\\s*$`,
      ),
    },
    {
      phase: 'Scores stored',
      // The full message continues "\n\n{Moderation=[0.0]}"; the score VALUE is
      // the judge's, so only the structural first line is matched here — and
      // the first line is also all the collapsed UI cell renders.
      pattern: new RegExp(`^\\s*Scores for traceId '${rx(traceId)}' stored successfully:\\s*$`),
    },
  ];
}

/** First line of a log message — what both the pattern and the UI cell show. */
function firstLine(message: string): string {
  return message.split('\n')[0];
}

test.describe('Online Evaluation — automation logs', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('An LLM-judge rule records its full scoring lifecycle in the automation log, at INFO, with no errors', { tag: ['@cap:online-evaluation.automation-logs'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    test.setTimeout(240_000);

    const modelDisplayName = await test.step(
      'Ensure an LLM provider is available (Anthropic or OpenAI from env)',
      async () => ensureModelAvailable(page),
    );

    const ruleName = `${testNamespace}-logs`;

    await test.step('Create the LLM-judge Moderation rule via the UI', async () => {
      const onlineEval = new OnlineEvaluationPage(page);
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

    const { ruleId, modelName } = await test.step(
      'Read the rule back: its id, and the model id the engine will log',
      async () => {
        const rules = await backendClient.listAutomationRulesForProject(project.id);
        expect(
          rules.map((r) => r.name),
          'the project must carry exactly the rule this test created',
        ).toEqual([ruleName]);
        const id = rules[0].id;
        return { ruleId: id, modelName: await backendClient.getLlmJudgeModelName(project.id, id) };
      },
    );

    // Seeded only AFTER the rule exists: rules do not score retroactively, so a
    // trace created first would never appear in the log at all.
    const trace = await test.step('Seed one trace via the SDK, after the rule exists', async () =>
      sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: 'evaluate this content',
        output: 'The capital of France is Paris.',
      }));

    // The poll IS the assertion: it throws with a diagnostic if no Moderation
    // score ever lands, which is the precondition for the log to hold a
    // completed lifecycle at all. The score's VALUE is the judge's opinion and
    // is asserted by online-evaluation-smoke.spec.ts, not here.
    await test.step('Wait for the judge to score the trace', async () => {
      await backendClient.pollTraceForFeedbackScore(trace.id, MODERATION_SCORE_NAME, {
        timeoutMs: 120_000,
      });
    });

    const expected = lifecyclePatterns(trace.id, ruleName, modelName);

    // API half. The log is written through a batching ClickHouse appender, so it
    // lands slightly after the score does — poll for the last lifecycle line
    // rather than assuming a settled score means a settled log.
    const logs: AutomationRuleLogRef[] = await test.step(
      'Poll the rule log API until the whole lifecycle has landed',
      async () => {
        const stored = expected[expected.length - 1].pattern;
        await expect
          .poll(
            async () => {
              const rows = await backendClient.getAutomationRuleLogs(ruleId);
              return rows.some((r) => stored.test(firstLine(r.message)));
            },
            { timeout: 60_000, intervals: [1000, 2000, 3000, 5000] },
          )
          .toBe(true);
        return backendClient.getAutomationRuleLogs(ruleId);
      },
    );

    await test.step('Each lifecycle line appears exactly once, in emission order', async () => {
      const matched = expected.map(({ phase, pattern }) => {
        const matches = logs.filter((r) => pattern.test(firstLine(r.message)));
        expect(
          matches.length,
          `exactly one "${phase}" line, got ${matches.length} in:\n` +
            logs.map((r) => `  [${r.level}] ${firstLine(r.message)}`).join('\n'),
        ).toBe(1);
        expect(matches[0].level, `"${phase}" is logged at INFO`).toBe('INFO');
        return { phase, row: matches[0] };
      });

      // Non-strict (`>=`), on timestamps rather than array position: two lines
      // emitted inside the same clock tick are a tie, not an inversion, and
      // ordering ties by array index would make this assertion depend on how
      // the backend happened to page the rows. A genuinely out-of-order log
      // still fails.
      for (let i = 1; i < matched.length; i++) {
        const previous = matched[i - 1];
        const current = matched[i];
        expect(
          current.row.timestamp >= previous.row.timestamp,
          `"${current.phase}" (${current.row.timestamp}) must not precede ` +
            `"${previous.phase}" (${previous.row.timestamp})`,
        ).toBe(true);
      }
    });

    await test.step('Nothing else is in this rule log: no ERROR, and no foreign trace', async () => {
      expect(
        logs.filter((r) => r.level !== 'INFO').map((r) => `[${r.level}] ${firstLine(r.message)}`),
        'a healthy scoring run logs nothing above INFO',
      ).toEqual([]);
      expect(
        logs
          .filter((r) => !r.message.includes(trace.id))
          .map((r) => firstLine(r.message)),
        'every line in this rule log belongs to the one trace the rule scored',
      ).toEqual([]);
    });

    // UI half. Same three lines, read back through the view an operator would
    // actually open — this is the only surface where a judge that fails at
    // scoring time is visible at all.
    await test.step('The Automation logs view renders the same lifecycle at INFO', async () => {
      const logsPage = new AutomationLogsPage(page);
      await logsPage.goto(ruleId);
      await logsPage.waitForReady();

      for (const { phase, pattern } of expected) {
        const row = logsPage.rowsWithMessage(pattern);
        await expect(row, `"${phase}" renders exactly one row`).toHaveCount(1);
        await expect(logsPage.levelCell(row), `"${phase}" renders at INFO`).toHaveText('INFO');
      }

      await expect(
        logsPage.rows(),
        'the view shows every row the API returned',
      ).toHaveCount(logs.length);
      await expect(
        logsPage.allLevelCells(),
        'no row in the view is above INFO',
      ).toHaveText(Array(logs.length).fill('INFO'));
    });
  });
});
