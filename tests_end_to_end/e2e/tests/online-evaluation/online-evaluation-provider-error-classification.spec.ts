import { test, expect } from '@e2e/fixtures';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';
import { buildConstantScoreMetric } from '@e2e/core/metrics';
import { mockAuthChatRequests, mockAuthSkipReason, mockAuthStats } from '@e2e/core/mock-auth';

/**
 * OPIK-8262: an LLM judge whose provider answers a PERMANENT status must give up on the
 * first call, while a TRANSIENT one must still consume the retry budget. The backend
 * decides that from the wire status — the 4xx family minus {408, 425, 429} — not from the
 * status family, so two statuses that used to behave identically now must not.
 *
 * The separation is only observable by counting UPSTREAM CALLS. Both rules write the same
 * three log lines (Evaluating / Sending / ERROR) whichever way they were classified,
 * because the in-process retries happen inside one delivery and log nothing extra. So the
 * assertion reads the mock gateway's own per-model request counters, which is also why
 * this spec cannot run against a remote deployment: the mock is bound to the test runner.
 */

/** Emitted once per delivery, immediately before the provider call. */
const SENDING_LINE = 'to LLM';
/** Emitted once per delivery, when the rule starts judging a sampled trace. */
const EVALUATING_LINE = 'sampled by rule';

/**
 * A client error the PR classifies as permanent: it can never succeed, so the caller
 * must not spend an attempt finding that out twice.
 */
const PERMANENT_STATUS = 409;
/**
 * A client error the PR keeps transient. Same family as 409, opposite handling — which is
 * precisely the distinction family-based classification could not express.
 */
const TRANSIENT_STATUS = 429;

/**
 * Lower bound on the calls a transient status must cost: `LLM_PROVIDER_CLIENT_MAX_ATTEMPTS`
 * defaults to 3 retries, so the outer policy makes 4 attempts.
 *
 * A floor rather than an equality because the langchain4j client nests retries of its own
 * inside each outer attempt (observed: 12 calls). That inner count is not what this change
 * governs, and pinning it would make the spec fail on an unrelated client upgrade. The
 * permanent side IS pinned exactly — "exactly one call" is the whole claim there.
 */
const MIN_TRANSIENT_ATTEMPTS = 4;

test.describe('Online Evaluation — LLM provider error classification', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  // The classification is asserted through the mock gateway's counters, so a backend that
  // cannot reach the mock would report zero calls rather than a connectivity problem.
  test.beforeAll(async () => {
    const reason = await mockAuthSkipReason();
    test.skip(reason !== null, reason ?? '');
  });

  test('A permanent provider status costs one call and a transient one consumes the retry budget, and neither scores the trace', { tag: ['@cap:online-evaluation.llm-judge-scores', '@cap:online-evaluation.automation-logs'] }, async ({
    page,
    project,
    sdkClient,
    backendClient,
    testNamespace,
    providerKeys,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    const providerName = `${testNamespace}-errclass`;
    // Model names are the axis: the mock keys both its counters and its forced status on
    // the model in the request body, so one provider serves two statuses and the two rules
    // differ in nothing else.
    const permanentModel = `${testNamespace}-permanent`;
    const transientModel = `${testNamespace}-transient`;

    const permanentRuleName = `${testNamespace}-judge-permanent`;
    const transientRuleName = `${testNamespace}-judge-transient`;
    const controlRuleName = `${testNamespace}-control`;

    await test.step('Register one mock provider serving a permanent and a transient status', async () => {
      await providerKeys.createOauth({
        providerName,
        modelNames: [permanentModel, transientModel],
      });
      await providerKeys.forceChatStatus(permanentModel, PERMANENT_STATUS);
      await providerKeys.forceChatStatus(transientModel, TRANSIENT_STATUS);
    });

    const rules = await test.step('Create one LLM-judge rule per model, plus a python control rule', async () => {
      const judge = (name: string, model: string) =>
        backendClient.createLlmJudgeAutomationRule({
          projectId: project.id,
          name,
          samplingRate: 1,
          model: `custom-llm/${providerName}/${model}`,
          prompt: 'Rate this output: {{output}}',
          variables: { output: 'output.output' },
          schema: [{ name: 'quality', type: 'INTEGER', description: '0 or 1' }],
        });
      return {
        permanent: await judge(permanentRuleName, permanentModel),
        transient: await judge(transientRuleName, transientModel),
        // A rule that cannot fail for provider reasons. Without it, two judges that logged
        // nothing would be indistinguishable from two judges that were never invoked.
        control: await backendClient.createAutomationRule({
          projectId: project.id,
          name: controlRuleName,
          samplingRate: 1,
          metric: buildConstantScoreMetric(controlRuleName),
          arguments: { output: 'output.output' },
        }),
      };
    });

    const trace = await test.step('Seed one trace for all three rules to judge', async () => {
      // One trace, three rules: the two judges are then provably given identical input, so
      // a difference in outcome is a difference in the provider's status and nothing else.
      return sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: 'whatever',
        output: 'seed output',
      });
    });

    await test.step('Control: the trace was sampled and the scoring pipeline fired', async () => {
      const score = await backendClient.pollTraceForFeedbackScore(trace.id, controlRuleName, {
        timeoutMs: 180_000,
      });
      expect(score.value, 'the control metric returns a constant 1.0').toBe(1.0);
    });

    const waitForTerminalError = async (ruleId: string, ruleName: string) => {
      await expect
        .poll(
          async () => {
            const logs = await backendClient.getAutomationRuleLogs(ruleId);
            return logs.filter((l) => l.level === 'ERROR').length;
          },
          {
            timeout: 120_000,
            intervals: [1_000, 2_000],
            message:
              `rule '${ruleName}' never reported a terminal failure — its provider always ` +
              'answers an error status, so a silent stream means the rule never ran',
          },
        )
        .toBeGreaterThan(0);
      return backendClient.getAutomationRuleLogs(ruleId);
    };

    const permanentLogs = await test.step('Both judges reach a terminal failure', async () => {
      const logs = await waitForTerminalError(rules.permanent, permanentRuleName);
      await waitForTerminalError(rules.transient, transientRuleName);
      return logs;
    });

    await test.step(
      `A ${PERMANENT_STATUS} costs exactly one call; a ${TRANSIENT_STATUS} costs the whole retry budget`,
      async () => {
        // Polled, not read once: the transient rule's last attempts can still be in flight
        // when its ERROR line lands, so a single read would race the retry loop. The
        // permanent count is re-asserted afterwards on the same settled snapshot, which is
        // what proves it did not merely lag behind.
        await expect
          .poll(
            async () => mockAuthChatRequests(await mockAuthStats(), transientModel),
            {
              timeout: 60_000,
              intervals: [1_000, 2_000],
              message:
                `a ${TRANSIENT_STATUS} must be retried: the subscriber classifies it as ` +
                'transient, so the provider client spends its attempts before giving up',
            },
          )
          .toBeGreaterThanOrEqual(MIN_TRANSIENT_ATTEMPTS);

        const stats = await mockAuthStats();
        expect(
          mockAuthChatRequests(stats, permanentModel),
          `a ${PERMANENT_STATUS} can never succeed, so the judge must call the provider once and stop`,
        ).toBe(1);
      },
    );

    await test.step(`The ${PERMANENT_STATUS} rule reported exactly one delivery and one failure`, async () => {
      // Complements the call count from the other side: the call count would also read 1 if
      // the rule had been dropped before the request, and these lines would also read 1 if
      // it had silently retried. Together they pin one delivery that made one call.
      expect(
        permanentLogs.filter((l) => l.message.includes(EVALUATING_LINE)),
        `rule '${permanentRuleName}' must be delivered exactly once`,
      ).toHaveLength(1);
      expect(
        permanentLogs.filter((l) => l.message.includes(SENDING_LINE)),
        `rule '${permanentRuleName}' must reach the provider exactly once`,
      ).toHaveLength(1);
      expect(
        permanentLogs.filter((l) => l.level === 'ERROR'),
        `rule '${permanentRuleName}' must report its terminal failure exactly once`,
      ).toHaveLength(1);
    });

    await test.step('A failed judge writes no feedback score', async () => {
      const detail = await backendClient.getTrace(trace.id);
      expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
      expect(
        detail!.feedbackScores.map((s) => s.name).sort(),
        'only the control rule may have written a score — a failed judge must store nothing',
      ).toEqual([controlRuleName]);
    });

    await test.step('The automation logs page renders the failed delivery', async () => {
      const logsPage = new AutomationLogsPage(page);
      await logsPage.goto(rules.permanent);
      await logsPage.waitForReady();

      // Each line is matched by the seeded trace id, so the page is proved to be rendering
      // THIS rule's stream rather than any stream at all.
      await expect(
        logsPage.rowsWithMessage(trace.id, 'INFO').filter({ hasText: EVALUATING_LINE }),
        'the page shows the trace being sampled by the rule',
      ).toHaveCount(1);
      await expect(
        logsPage.rowsWithMessage(trace.id, 'INFO').filter({ hasText: SENDING_LINE }),
        'the page shows the single provider call',
      ).toHaveCount(1);
      await expect(
        logsPage.rowsWithMessage(trace.id, 'ERROR'),
        'the page shows the terminal failure, and only one of them',
      ).toHaveCount(1);
    });
  });
});
