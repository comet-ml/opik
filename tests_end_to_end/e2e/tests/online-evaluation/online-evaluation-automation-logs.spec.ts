import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';
import {
  permanentFailureSkipReason,
  permanentFailureStatus,
} from '@e2e/core/failing-provider';

/** Sampled, sent, failed — the whole stream for one trace judged once. */
const EXPECTED_ROWS = 3;

test.describe('Online Evaluation — automation logs page', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('The automation logs page renders a rule\'s log stream and reveals the provider error on expand', { tag: ['@cap:online-evaluation.automation-logs'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    providerKeys,
    automationRulesCleanup,
    page,
  }) => {
    test.setTimeout(300_000);

    // The seed is written through the API and read back through the page: a
    // disagreement between the two is exactly what this spec exists to catch,
    // and it is the only reason to drive a browser at a backend log stream.
    // The failure is a permanent 4xx from a destination on the deployment
    // itself, so the message body is fixed text rather than LLM output.

    const skipReason = await permanentFailureSkipReason();
    test.skip(skipReason !== null, skipReason ?? '');
    const expectedStatus = await permanentFailureStatus();

    const providerName = `${testNamespace}-perm4xx`;
    const ruleName = `${testNamespace}-judge`;

    const ruleId = await test.step('Seed a rule that will fail deterministically', async () => {
      const model = await providerKeys.createPermanentFailure({
        providerName,
        modelName: 'judge',
      });
      return backendClient.createLlmJudgeRule({
        projectId: project.id,
        name: ruleName,
        samplingRate: 1,
        model,
        prompt: 'Rate the quality of this output from 0 to 1: {{output}}',
        variables: { output: 'output.output' },
        schema: [{ name: 'Quality', type: 'INTEGER', description: '0 or 1' }],
      });
    });

    const trace = await test.step('Seed the one trace the rule will judge', async () =>
      sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: 'whatever',
        output: 'seed output',
      }));

    const seededLogs = await test.step(
      'Confirm server-side that the stream is complete before opening the page',
      async () => {
        // Opening the browser first would make an empty table ambiguous between
        // "the page does not render logs" and "there were none yet". The page
        // does not poll, so what it must render has to already exist.
        let collected: AutomationRuleLogRef[] = [];
        await expect
          .poll(
            async () => {
              collected = await backendClient.getAutomationRuleLogs(ruleId);
              return collected.some((l) => l.level === 'ERROR');
            },
            {
              timeout: 180_000,
              intervals: [2_000, 5_000],
              message:
                `rule '${ruleName}' never reported a failure — there is nothing for the page ` +
                'to render, so a UI assertion here could not fail for the right reason',
            },
          )
          .toBe(true);
        expect(
          collected.map((l) => l.level).sort(),
          'the page is asserted against exactly this stream',
        ).toEqual(['ERROR', 'INFO', 'INFO']);
        return collected;
      },
    );

    const logsPage = new AutomationLogsPage(page);

    await test.step("Open the rule's logs and verify the stream renders", async () => {
      await logsPage.goto(ruleId);
      await logsPage.waitForReady();
      await expect(
        logsPage.rows,
        'the page must render the seeded stream and nothing else',
      ).toHaveCount(EXPECTED_ROWS);
      await expect(
        logsPage.row({ traceId: trace.id, level: 'INFO' }),
        'both INFO lines must be attributed to the seeded trace',
      ).toHaveCount(seededLogs.filter((l) => l.level === 'INFO').length);
    });

    const errorRow = logsPage.row({ traceId: trace.id, level: 'ERROR' });

    await test.step('The ERROR row carries the level and trace id of the failure', async () => {
      // toHaveCount(1) rather than .first(): an ambiguous match must fail loudly
      // instead of silently asserting about whichever row came back first.
      await expect(errorRow, 'exactly one row is the failure').toHaveCount(1);
      await expect(
        logsPage.cell(errorRow, 'level'),
        'the Level column is how a user finds the failure among the informational lines',
      ).toHaveText('ERROR');
      await expect(
        logsPage.cell(errorRow, 'traceId'),
        'the Trace Id column is what ties the failure back to the trace it was judging',
      ).toHaveText(trace.id);
    });

    await test.step('Expanding the ERROR row reveals the provider error body', async () => {
      // Compared against what the API said rather than against a literal: the
      // engine writes "…with rule '<name>': \n\n<what the provider said>", and
      // the cell shows the first line until expanded. Taking the detail from the
      // seeded stream is what makes this a read-back of the write above instead
      // of two independent guesses at the same wording.
      const errorLine = seededLogs.find((l) => l.level === 'ERROR');
      expect(errorLine, 'the ERROR line polled for above must still be here').toBeDefined();
      const providerDetail = errorLine!.message.split('\n\n').slice(1).join('\n\n').trim();
      expect(
        providerDetail,
        'the failure must carry a provider body for the expand control to reveal',
      ).not.toBe('');
      expect(
        providerDetail,
        "the provider's own status is what the expanded row has to show",
      ).toContain(String(expectedStatus));

      const message = logsPage.cell(errorRow, 'message');
      await expect(
        message,
        'the collapsed cell must show the summary line, not the provider body',
      ).not.toContainText(providerDetail);
      await expect(
        logsPage.expandButton(errorRow),
        'the cell renders the Expand control only for a multi-line message, so its absence ' +
          'means the provider body never reached the log line',
      ).toBeVisible();

      await logsPage.expandRow(errorRow);

      await expect(
        message,
        "the expanded row is the only place a user sees the provider's own error",
      ).toContainText(providerDetail);
      await expect(message, 'the summary line stays visible when expanded').toContainText(
        `with rule '${ruleName}'`,
      );
    });

    await test.step('The route without a rule_id shows its empty state, not an unfiltered dump', async () => {
      await logsPage.goto();
      await logsPage.waitForReady();
      await expect(logsPage.noRuleParametersMessage).toBeVisible();
      await expect(
        logsPage.rows,
        'logs are rule-scoped; the bare route must not list another rule\'s lines',
      ).toHaveCount(0);
    });
  });
});
