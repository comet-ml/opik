import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { buildConstantThreadScoreMetric } from '@e2e/core/metrics';

/** Emitted once per evaluator call, immediately before the HTTP request. */
const EVALUATOR_CALL_LINE = 'to Python evaluator';

/** Emitted once per thread whose scores were persisted — the end of one cycle. */
const SCORES_STORED_LINE = 'stored successfully';

/**
 * Online evaluation of a THREAD-scoped rule over several threads at once
 * (OPIK-8262).
 *
 * A request naming N threads becomes N entries on the scoring stream, one per
 * thread. The estate has no spec that drives a thread-scoped rule at all, and
 * the two ways the fan-out can go wrong are both invisible from any single
 * thread's point of view:
 *
 *   - an id dropped in the split — fewer evaluator calls than threads, and a
 *     thread that silently never gets scored;
 *   - an entry still carrying the whole list — N x N evaluator calls, each
 *     thread scored repeatedly.
 *
 * Counting evaluator calls is what separates those from a correct run, because
 * all three end with every thread carrying a score: the duplicate case
 * overwrites its own value, so the scores alone read identically.
 *
 * Deterministic by construction: a constant-1.0 python thread metric needs no
 * provider key and no model verdict, and the rule sits at sampling_rate 0 so
 * the manual request is the only thing that can trigger it.
 */
test.describe('Online Evaluation — thread-scoped rule fan-out', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('a manual evaluation of N threads scores every one of them exactly once', { tag: ['@cap:online-evaluation.rule-scope-thread-span'] }, async ({
    threadCohort,
    backendClient,
    testNamespace,
    automationRulesCleanup,
    page,
  }) => {
    // Budget for the longest chain: the cohort fixture's 60s thread-aggregation
    // poll, the 180s wait for the rule to store scores, then three thread panels
    // opened in sequence. Kept just above the 180s inner poll so that one fires
    // first — it fails naming the threads that never got scored, which beats an
    // opaque "test timeout exceeded".
    test.setTimeout(300_000);

    const { projectId, threads } = threadCohort;
    const threadIds = threads.map((t) => t.threadId);
    const scoreName = `${testNamespace}-thread-score`;

    const ruleId = await test.step('Create a thread-scoped python rule that can only fire on request', async () => {
      // sampling_rate 0: the production sampler skips every thread, so anything
      // this rule scores is attributable to the manual request below. At any
      // other rate a passing assertion would also be satisfied by ordinary
      // sampling, which is not the path under test.
      return backendClient.createAutomationRule({
        projectId,
        name: `${testNamespace}-thread-rule`,
        type: 'trace_thread_user_defined_metric_python',
        samplingRate: 0,
        metric: buildConstantThreadScoreMetric(scoreName),
      });
    });

    await test.step('No thread carries a score before the request', async () => {
      // The complement of the sampling_rate 0 argument above, asserted rather
      // than assumed: if the threads arrived already scored, every assertion
      // after this one would hold without the fan-out having run at all.
      for (const { threadId } of threads) {
        const thread = await backendClient.getThread({ projectId, threadId });
        expect(
          thread.feedbackScores,
          `thread '${threadId}' must start unscored for the manual trigger to be attributable`,
        ).toEqual([]);
      }
    });

    await test.step('The request reports one queued entry per thread', async () => {
      const queued = await backendClient.evaluateThreadsManually({
        projectId,
        threadModelIds: threads.map((t) => t.threadModelId),
        ruleIds: [ruleId],
      });
      // The first place the split is observable. A request that collapsed to a
      // single entry still answers 202.
      expect(queued.entitiesQueued, 'one entry per thread named in the request').toBe(
        threads.length,
      );
      expect(queued.rulesApplied, 'exactly the one rule was applied').toBe(1);
    });

    const logs = await test.step('Wait for the rule to finish all three threads', async () => {
      let observed: AutomationRuleLogRef[] = [];
      await expect
        .poll(
          async () => {
            observed = await backendClient.getAutomationRuleLogs(ruleId);
            return observed.filter((l) => l.message.includes(SCORES_STORED_LINE)).length;
          },
          {
            timeout: 180_000,
            intervals: [2_000, 5_000],
            message:
              'the rule never reported storing scores for every thread — a stream entry was ' +
              'lost, or the trace-thread python evaluator is disabled on this deployment',
          },
        )
        .toBe(threads.length);
      return observed;
    });

    await test.step('The evaluator was called once per thread, and once for each', async () => {
      const callLines = logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE));
      // Both halves of the fan-out failure mode, as one count: N-1 or fewer
      // means an id was dropped in the split, N*N means each entry still
      // carried the whole list.
      expect(
        callLines.map((l) => l.message),
        `the evaluator must be called exactly ${threads.length} times, not once and not ${threads.length ** 2} times`,
      ).toHaveLength(threads.length);

      // The count alone would still pass if one thread were evaluated three
      // times and the other two never — so pin which threads those calls were
      // for.
      for (const threadId of threadIds) {
        expect(
          callLines.filter((l) => l.message.includes(`'${threadId}'`)),
          `thread '${threadId}' must be sent to the evaluator exactly once`,
        ).toHaveLength(1);
      }
    });

    await test.step('Every thread carries the rule score, and only it', async () => {
      // Compared as the whole score set per thread rather than by looking this
      // rule's score up among others: a run that also wrote something it should
      // not have is exactly what a find()-then-compare would pass through.
      for (const { threadId } of threads) {
        const thread = await backendClient.getThread({ projectId, threadId });
        expect(
          thread.feedbackScores.map((s) => ({ name: s.name, value: s.value, source: s.source })),
          `thread '${threadId}' must carry the rule's score once, written by online scoring`,
        ).toEqual([{ name: scoreName, value: 1.0, source: 'online_scoring' }]);
      }
    });

    await test.step('The project holds exactly the cohort and nothing else', async () => {
      // Guards the reading of every assertion above: they iterate the threads
      // the fixture seeded, so a run that scored those three AND invented a
      // fourth would satisfy all of them.
      const { total, threads: rows } = await backendClient.listThreads({ projectId });
      expect(total, 'the seeded cohort is the whole project').toBe(threads.length);
      expect(
        rows.map((r) => r.id).sort(),
        'the project holds the seeded threads and no others',
      ).toEqual([...threadIds].sort());
    });

    await test.step('Each thread shows the score in its panel', async () => {
      // Where a user reads a thread-level score: the Threads table hides
      // feedback-score columns by default, so the panel's own tab is the
      // rendered surface for this. Every thread is opened, not a sample —
      // "one of the three renders it" is the observation a collapsed fan-out
      // would also produce.
      const logsPage = new LogsPage(page);
      await logsPage.gotoThreads(projectId);
      await logsPage.waitForThreadsReady(threadIds[0]);

      for (const { threadId } of threads) {
        const panel = await logsPage.openThreadById(threadId);
        await panel.waitForFullyLoaded();
        await panel.openFeedbackScoresTab();
        await expect(
          panel.feedbackScoreRow(scoreName),
          `thread '${threadId}' must render exactly one row for the rule's score`,
        ).toHaveCount(1);
        expect(
          await panel.readFeedbackScoreValue(scoreName),
          `thread '${threadId}' must render the rule's score as 1.0`,
        ).toBeCloseTo(1.0, 6);
      }
    });
  });
});
