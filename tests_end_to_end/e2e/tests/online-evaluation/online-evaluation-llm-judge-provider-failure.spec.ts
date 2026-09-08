import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

/** Written once per delivery, immediately before the provider call. */
const LLM_CALL_LINE = 'to LLM';

/** Written when the rule is sampled, before anything can go wrong. */
const SAMPLED_LINE = 'sampled by rule';

/**
 * The provider's own refusal, quoted verbatim into the scorer's error line.
 *
 * `notFoundProviderBaseUrl` points the provider at a route the backend does not
 * serve, so this is the backend's standard 404 body coming back to it as an LLM
 * response. Asserting on the status is the point: the scorer reports the reason
 * the provider gave, not a generic "something went wrong".
 */
const PROVIDER_REFUSAL = '{"code":404,"message":"HTTP 404 Not Found"}';

/** A collapsed message cell shows only the first line. */
const firstLine = (message: string) => message.split('\n')[0]!.trim();

test.describe('Online Evaluation — LLM-judge provider failure', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A judge whose provider refuses reports the refusal on the automation logs page and writes no score', { tag: ['@cap:online-evaluation.automation-logs'] }, async ({
    page,
    project,
    sdkClient,
    backendClient,
    testNamespace,
    providerKeys,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    // Scope: what the engine SAYS when a judge cannot reach its provider, and
    // where a user reads it. Deliberately NOT how many times it retries first —
    // telling a permanent refusal from a transient one takes two re-delivery
    // windows, which is 20 minutes at the shipped
    // REDIS_SCORING_PENDING_MESSAGE_DURATION default and so cannot be asserted
    // against a deployed environment.

    const judgeRuleName = `${testNamespace}-judge`;
    const controlRuleName = `${testNamespace}-control`;

    const judgeRuleId = await test.step(
      'Create a judge rule pointed at a provider that always refuses, and a python control rule',
      async () => {
        const model = await providerKeys.createPermanentlyFailing({
          providerName: `${testNamespace}-provider`,
        });
        const judgeRuleId = await backendClient.createLlmJudgeRule({
          projectId: project.id,
          name: judgeRuleName,
          model,
          messages: [{ role: 'USER', content: 'Rate this output out of 10: {{output}}' }],
          samplingRate: 1,
        });
        // The control needs no provider at all, so it can only fail for reasons
        // that would fail the judge too. Without it, a silent judge stream would
        // be indistinguishable from online scoring being down on this
        // environment — which would read as a green run asserting nothing.
        await backendClient.createAutomationRule({
          projectId: project.id,
          name: controlRuleName,
          samplingRate: 1,
          metric: buildConstantScoreMetric(controlRuleName),
          arguments: { output: 'output.output' },
        });
        return judgeRuleId;
      },
    );

    const trace = await test.step('Seed one trace for both rules to judge', async () => {
      // One trace, two rules: the judge and the control are then provably
      // judging identical input, so the difference in outcome is the provider
      // and not what each was given.
      return sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: 'whatever',
        output: 'seed output',
      });
    });

    await test.step('Control: the provider-free rule scored the trace', async () => {
      const score = await backendClient.pollTraceForFeedbackScore(trace.id, controlRuleName, {
        timeoutMs: 180_000,
      });
      expect(score.value, 'the control metric returns a constant 1.0').toBe(1.0);
    });

    const judgeLogs = await test.step(
      'The judge rule reports the provider refusal in its log stream',
      async () => {
        let logs: AutomationRuleLogRef[] = [];
        await expect
          .poll(
            async () => {
              logs = await backendClient.getAutomationRuleLogs(judgeRuleId);
              // The error line is written last, so its arrival is what makes
              // this rule's stream complete for the seeded trace.
              return logs.some((l) => l.level === 'ERROR');
            },
            {
              timeout: 180_000,
              intervals: [2_000, 5_000],
              message:
                `rule '${judgeRuleName}' never reported a failure — its provider cannot succeed, ` +
                'so a silent stream means the rule was never invoked',
            },
          )
          .toBe(true);

        expect(
          logs.filter((l) => l.message.includes(SAMPLED_LINE)),
          'the judge must have been sampled for the seeded trace',
        ).toHaveLength(1);
        expect(
          logs.filter((l) => l.message.includes(LLM_CALL_LINE)),
          'the judge must have reached the point of calling its provider',
        ).toHaveLength(1);

        const errors = logs.filter((l) => l.level === 'ERROR');
        expect(errors, 'the refusal is reported exactly once').toHaveLength(1);
        expect(
          errors[0]!.message,
          'the error must quote the status the provider answered with, not a generic failure',
        ).toContain(PROVIDER_REFUSAL);
        expect(
          errors[0]!.message,
          'the error must name the rule that failed, so a workspace of rules is separable',
        ).toContain(judgeRuleName);
        return logs;
      },
    );

    const logsPage = new AutomationLogsPage(page, judgeRuleId);

    await test.step('The automation logs page renders exactly the stream the API reports', async () => {
      await logsPage.goto();
      await logsPage.waitForReady();
      // Settle on the count the API already reported before reading the DOM:
      // waitForReady returns on the first row, and readRows does not retry, so
      // without this a table caught mid-render would be compared as if it were
      // the whole stream.
      await logsPage.waitForRowCount(judgeLogs.length);

      const rendered = await logsPage.readRows();
      // Compare the whole stream, not just "our error line is in there": a page
      // that also rendered another rule's lines, or dropped one, is the failure
      // this assertion exists to catch. Collapsed cells show the first line
      // only, so the API side is reduced the same way.
      //
      // Positional, not set-wise: the API already returns newest-first
      // (AutomationRuleEvaluatorLogsDAO's FIND_ALL is `ORDER BY timestamp DESC`)
      // and the page re-sorts on the same key with a stable sort, so the two
      // sequences must agree line for line. Sorting both sides before comparing
      // would accept a page that shuffled the stream.
      expect(
        rendered.map((r) => `${r.level}|${r.message}`),
        'every API log line renders, in the order the API reports it, and nothing else does',
      ).toEqual(judgeLogs.map((l) => `${l.level}|${firstLine(l.message)}`));

      expect(
        new Set(rendered.map((r) => r.traceId)),
        'every line is attributed to the seeded trace',
      ).toEqual(new Set([trace.id]));

      // Newest first: the refusal is the last line written, so it heads the
      // table. A page that ordered oldest-first would bury a failure below the
      // routine lines on a busy rule. Not implied by the comparison above —
      // that one only proves the page did not reorder what the API gave it, and
      // would still pass if the API itself started serving oldest-first.
      expect(rendered[0]?.level, 'the newest line is the ERROR, at the top').toBe('ERROR');
    });

    await test.step('Expanding the error row reveals the provider status', async () => {
      const errorRow = logsPage.rowsWithLevel('ERROR');
      await expect(errorRow, 'exactly one ERROR row to expand').toHaveCount(1);
      await logsPage.expandRow(errorRow);
      await expect(
        errorRow.locator('[data-cell-id$="_message"]'),
        'the reason a user reads must be the provider status, not an empty tail',
      ).toContainText(PROVIDER_REFUSAL);
    });

    await test.step('A judge whose provider refused it writes no score', async () => {
      // The complement of the control. A rule that failed and still stored
      // something would be worse than one that failed loudly. The judge did
      // reach its provider — the `to LLM` line above says so — and was refused;
      // what must not survive that is a score.
      const detail = await backendClient.getTrace(trace.id);
      expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
      expect(
        detail!.feedbackScores.map((s) => s.name).sort(),
        'only the control rule may have written a score',
      ).toEqual([controlRuleName]);
    });
  });
});
