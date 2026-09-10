import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';

/** Emitted once per thread, when the scorer starts preparing that thread's request. */
const EVALUATING_LINE = 'Evaluating threadId';
/** Emitted once per thread, immediately before the provider call. */
const SENDING_LINE = 'Sending threadId';
/** The thread scorer's own wording for a failure raised while scoring a thread. */
const SCORING_FAILURE_LINE = 'Unexpected error while scoring threadId';

/** Both the INFO and the ERROR lines name the thread they are about, in quotes. */
const THREAD_ID_IN_MESSAGE = /threadId '([^']+)'/;

function threadIdOf(line: AutomationRuleLogRef): string {
  const match = THREAD_ID_IN_MESSAGE.exec(line.message);
  if (!match) {
    // Not a soft assertion and not a filter: a line that should name a thread
    // and does not is either a wording change or a different code path, and
    // either way the counts below would be computed over the wrong lines.
    throw new Error(`log line names no threadId: ${line.level} ${line.message}`);
  }
  return match[1];
}

test.describe('Online Evaluation — thread-scope LLM judge over a failing provider', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('Two threads closed in one call each report their own provider failure, and neither is scored', { tag: ['@cap:online-evaluation.rule-scope-thread-span', '@cap:online-evaluation.automation-logs'] }, async ({
    project,
    sdkClient,
    backendClient,
    providerKeys,
    testNamespace,
    automationRulesCleanup,
    page,
  }) => {
    test.setTimeout(240_000);

    // Closing several threads in ONE call is the whole point: a thread-scope
    // rule then fans a single stream message out over both thread ids, and the
    // two evaluations are siblings of that one message. Each sibling must
    // report its own failure — a fold that keeps only one of them, or one that
    // never terminates the message, is invisible with a single thread.
    const threadIds = [`${testNamespace}-thread-a`, `${testNamespace}-thread-b`];
    const ruleName = `${testNamespace}-thread-judge`;
    const scoreName = `${testNamespace}-thread-score`;

    const model = await test.step('Seed a custom-llm provider that refuses every connection', async () => {
      return providerKeys.createUnreachable({
        providerName: `${testNamespace}-broken-provider`,
        modelName: 'unreachable-model',
      });
    });

    const ruleId = await test.step('Create a thread-scope LLM-judge rule at 100% sampling', async () => {
      return backendClient.createTraceThreadLlmAsJudgeRule({
        projectId: project.id,
        name: ruleName,
        samplingRate: 1,
        model,
        scoreName,
      });
    });

    await test.step('Seed two turns under each of two threads', async () => {
      for (const threadId of threadIds) {
        for (let turn = 0; turn < 2; turn++) {
          await sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-${threadId}-turn-${turn}`,
            input: 'what is the capital of France?',
            output: 'Paris.',
            thread_id: threadId,
          });
        }
      }
    });

    await test.step('Both threads exist and are still open before the close', async () => {
      // The precondition the batch close needs, asserted rather than assumed:
      // if the threads were already inactive here, the evaluations below would
      // have been triggered by the 15-minute inactivity job instead of by the
      // one call this test makes, and the fan-out would not be the shape under
      // test.
      await expect
        .poll(
          async () => (await backendClient.listThreads({ projectId: project.id })).total,
          {
            timeout: 60_000,
            intervals: [1_000, 2_000],
            message: 'both seeded threads must be queryable before they are closed',
          },
        )
        .toBe(2);

      const { threads } = await backendClient.listThreads({ projectId: project.id });
      expect(
        threads.map((t) => t.id).sort(),
        'the project holds exactly the two seeded threads and nothing else',
      ).toEqual([...threadIds].sort());
      for (const thread of threads) {
        expect(thread.status, `thread '${thread.id}' is open before the close`).toBe('active');
      }
    });

    await test.step('Close both threads in a single request', async () => {
      await backendClient.closeThreads({ projectName: project.name, threadIds });
    });

    const logs = await test.step('Wait for the rule to report a failure for both threads', async () => {
      let stream: AutomationRuleLogRef[] = [];
      await expect
        .poll(
          async () => {
            stream = await backendClient.getAutomationRuleLogs(ruleId);
            const failed = new Set(
              stream.filter((l) => l.level === 'ERROR').map((l) => threadIdOf(l)),
            );
            return threadIds.filter((id) => failed.has(id)).length;
          },
          {
            timeout: 180_000,
            intervals: [2_000, 5_000],
            message:
              `rule '${ruleName}' never reported a failure for both threads — its provider ` +
              'cannot answer, so a stream missing one of them means that thread was never ' +
              'evaluated or its failure was swallowed',
          },
        )
        .toBe(2);
      return stream;
    });

    await test.step('Each thread was evaluated exactly once, and no other thread was', async () => {
      // Asserted as the whole set rather than "mine is in there": one thread
      // appearing twice (a sibling processed twice) and a third thread
      // appearing (a fan-out over the wrong ids) are both bugs this rules out,
      // and neither would fail a per-thread `find()`.
      const evaluating = logs.filter((l) => l.message.startsWith(EVALUATING_LINE));
      expect(
        evaluating.map(threadIdOf).sort(),
        'exactly one Evaluating line per closed thread',
      ).toEqual([...threadIds].sort());
    });

    await test.step('Each thread reached the provider on the rule\'s own model', async () => {
      // Without this the failures below could equally be a rule that never got
      // as far as a provider call — a template that failed to render, say —
      // which is a different bug wearing the same ERROR line.
      const sending = logs.filter((l) => l.message.startsWith(SENDING_LINE));
      expect(sending.map(threadIdOf).sort(), 'exactly one provider call per thread').toEqual(
        [...threadIds].sort(),
      );
      for (const line of sending) {
        expect(line.message, 'the call names the unreachable provider model').toContain(
          `model='${model}'`,
        );
      }
    });

    await test.step('Each thread reported its own failure, exactly once', async () => {
      // The claim the thread scorer's failure fold is responsible for: sibling
      // evaluations of one fanned-out message must each surface their own
      // error. Keeping one and dropping the rest would leave a thread that was
      // evaluated, was not scored, and says nothing about why.
      const errors = logs.filter((l) => l.level === 'ERROR');
      expect(errors.map(threadIdOf).sort(), 'exactly one ERROR per closed thread').toEqual(
        [...threadIds].sort(),
      );
      for (const line of errors) {
        expect(line.message, 'the failure is raised while scoring the thread').toContain(
          SCORING_FAILURE_LINE,
        );
      }
    });

    await test.step('Both threads are closed and neither carries a score', async () => {
      const { total, threads } = await backendClient.listThreads({ projectId: project.id });
      expect(total, 'still exactly the two seeded threads').toBe(2);
      for (const thread of threads) {
        expect(thread.status, `thread '${thread.id}' is closed`).toBe('inactive');
      }

      for (const threadId of threadIds) {
        const detail = await backendClient.getThread({ projectId: project.id, threadId });
        expect(
          detail.feedbackScores.map((s) => s.name),
          `a failed evaluation must write nothing to thread '${threadId}'`,
        ).toEqual([]);
      }
    });

    await test.step('The automation logs page shows one failure per thread', async () => {
      // The API assertions above read the same stream this page renders, but
      // only through the page can a user actually see it — and this is the one
      // place the product says whether a rule ran at all.
      const logsPage = new AutomationLogsPage(page);
      await logsPage.goto(ruleId);
      await logsPage.waitForReady();

      for (const threadId of threadIds) {
        await expect(
          logsPage.rowsForThreadAtLevel('ERROR', threadId),
          `one rendered failure row for thread '${threadId}'`,
        ).toHaveCount(1);
      }
      await expect(
        logsPage.rowsAtLevel('ERROR'),
        'and no failure rows beyond those two — the view is scoped to this rule',
      ).toHaveCount(2);
    });
  });
});
