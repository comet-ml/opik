import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { uuid7 } from '@e2e/core/backend';
import type { AutomationRuleLogRef, BackendClient } from '@e2e/core/backend';
import { buildConstantScoreMetric, buildValuelessScoresMetric } from '@e2e/core/metrics';

/**
 * A python metric may answer `ScoreResult(value=None)` — a check that did not
 * apply, or one the metric gave up on. Such a score cannot be persisted
 * (`feedback_scores.value` is not nullable), and the contract asserted here is
 * that it is dropped ON ITS OWN: every sibling in the same batch is still
 * stored, the evaluation does not fail, and the rule's log says which name was
 * skipped.
 *
 * The failure this guards against is silent wrongness rather than an outage —
 * one valueless score used to take the whole batch down with it, so a trace
 * carried NO scores and the rule reported only a generic error naming neither
 * the metric nor the reason. A user reading that trace sees "this rule did not
 * score anything", which is indistinguishable from a rule that was never
 * invoked.
 *
 * ## Why the log assertions do not pin the WARN's exact shape
 *
 * The dropped names are reported on the rule's own log stream, and HOW they are
 * reported has changed within the lifetime of this behaviour: one WARN per
 * dropped name at first, then one capped WARN per batch (names capped, the
 * remainder rendered as "and N more") so that a metric returning a long list
 * cannot flood the stream. Both shapes are the same claim — this name was
 * skipped, and here is why — so these tests assert that claim (a WARN naming
 * the score and the reason, no ERROR anywhere) and deliberately do NOT assert
 * how many WARN lines carried it. Pinning the count would make the spec a test
 * of the current wording rather than of the behaviour, and it would fail on any
 * build sitting on the other side of that change.
 *
 * ## Why everything is seeded over REST
 *
 * `source` is load-bearing throughout: `OnlineScoringSampler` and
 * `OnlineScoringSpanSampler` keep only entities whose source `isLoggingSource`,
 * and the sampler drops any trace with a null `end_time` as a partial write. The
 * span half additionally needs a span written on its own, which the SDK bridge
 * cannot do — its only span route writes a whole trace and its spans in one
 * call. One seeding mechanism for the whole file keeps the trace, span and
 * thread halves comparable; the rules read `output.output`, which the REST write
 * produces exactly as the SDK would.
 */

/** The 500-era wording: the batch failing wholesale is what this behaviour replaced. */
const OPAQUE_FAILURE_MESSAGE = 'An unexpected error occurred';

/** Emitted once per evaluator call, immediately before the HTTP request. */
const EVALUATOR_CALL_LINE = 'to Python evaluator';

/** Tail of the WARN that reports a dropped score, stable across both wordings. */
const DROPPED_SCORE_REASON = 'because the metric returned no value';

/** The scorer's terminal line, written even when the storable batch is empty. */
const STORED_SUCCESSFULLY = 'stored successfully';

const SEED_OUTPUT = 'seed output';

/** Mapping every trace- and span-scope rule here uses. Thread rules map nothing. */
const OUTPUT_ARGUMENTS = { output: 'output.output' };

/**
 * Wait until a rule has finished with the entity it was given, then return its
 * whole log stream.
 *
 * Anchoring on the terminal "stored successfully" line rather than on a score
 * arriving is what makes the absence assertions sound. A rule whose every score
 * was valueless writes no score at all, so there is nothing to poll for; and a
 * rule that HAS written one score may still be mid-batch. The terminal line is
 * emitted on both paths and only once the scorer is done.
 *
 * An ERROR ends the wait too, and is then asserted away. It is a terminal state
 * just as much as the success line, so polling on past it only delays the
 * message that says what went wrong by the full timeout — and every rule in
 * this file is one that must not fail.
 */
async function waitForRuleToFinish(
  backendClient: BackendClient,
  ruleId: string,
  ruleName: string,
): Promise<AutomationRuleLogRef[]> {
  let logs: AutomationRuleLogRef[] = [];
  await expect
    .poll(
      async () => {
        logs = await backendClient.getAutomationRuleLogs(ruleId);
        return logs.some((l) => l.message.includes(STORED_SUCCESSFULLY) || l.level === 'ERROR');
      },
      {
        timeout: 180_000,
        intervals: [2_000, 5_000],
        message:
          `rule '${ruleName}' never reported an outcome — no stored-scores line and no ` +
          `error. Its trace or span reached no scorer at all.`,
      },
    )
    .toBe(true);

  expect(
    logs.filter((l) => l.level === 'ERROR').map((l) => l.message),
    `rule '${ruleName}' failed instead of storing its scores. A batch failing because one ` +
      `of its scores carries no value is the regression this spec exists to catch: it ` +
      `takes every sibling score down with it.`,
  ).toEqual([]);

  return logs;
}

/**
 * The assertions every rule in this file makes about its own log stream: the
 * dropped names were reported, nothing failed, and the evaluator was called
 * once.
 */
function expectCleanDropLog(
  logs: AutomationRuleLogRef[],
  ruleName: string,
  droppedNames: readonly string[],
): void {
  for (const dropped of droppedNames) {
    const warns = logs.filter(
      (l) =>
        l.level === 'WARN' && l.message.includes(dropped) && l.message.includes(DROPPED_SCORE_REASON),
    );
    // At least one, not exactly one: a single batch-level WARN may name several
    // dropped scores at once. See the file comment.
    expect(
      warns.length,
      `rule '${ruleName}' must report that it skipped '${dropped}' and why — a score that ` +
        `vanishes with no explanation is indistinguishable from one the metric never returned`,
    ).toBeGreaterThan(0);
  }

  // Re-asserted rather than left to `waitForRuleToFinish`, which stops at the
  // FIRST terminal line: a rule that stored its scores and then errored would
  // satisfy that wait.
  expect(
    logs.filter((l) => l.level === 'ERROR').map((l) => l.message),
    `rule '${ruleName}' must not fail: a score with no value is a valid answer from a metric, ` +
      `not an error`,
  ).toEqual([]);

  for (const line of logs) {
    expect(
      line.message,
      `rule '${ruleName}' must not report the pre-classification catch-all`,
    ).not.toContain(OPAQUE_FAILURE_MESSAGE);
  }

  expect(
    logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)),
    `rule '${ruleName}' must call the evaluator exactly once — a dropped score is not a ` +
      `reason to re-run the metric`,
  ).toHaveLength(1);
}

test.describe('Online Evaluation — python scores with no value', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A trace-scope rule stores the valued scores in a mixed batch and drops only the valueless ones', { tag: ['@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.scores-in-trace-panel'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // Scoring is asynchronous end to end (ingest -> sampler -> Redis stream ->
    // python evaluator -> score write -> user log write) and four rules judge
    // the same trace. The inner polls below fail first, each with a diagnostic
    // naming the rule it was waiting on.
    test.setTimeout(300_000);

    const controlName = `${testNamespace}-control`;
    const mixedKept = `${testNamespace}-mixed-kept`;
    const mixedGone = `${testNamespace}-mixed-gone`;
    const zeroKept = `${testNamespace}-zero-kept`;
    const zeroGone = `${testNamespace}-zero-gone`;
    const voidFirst = `${testNamespace}-void-first`;
    const voidSecond = `${testNamespace}-void-second`;

    const rules = await test.step('Create a control rule and three list-returning rules', async () => {
      const create = (name: string, metric: string) =>
        backendClient.createAutomationRule({
          projectId: project.id,
          name,
          samplingRate: 1,
          metric,
          arguments: OUTPUT_ARGUMENTS,
        });

      return {
        // The control returns a single valued score and nothing else. It is what
        // separates "the fix is wrong" from "the python evaluator is
        // unreachable": without it, three rules that stored nothing would look
        // identical to three rules that were never invoked.
        control: await create(`${testNamespace}-control-rule`, buildConstantScoreMetric(controlName)),
        // The valueless score is FIRST in every batch below. A regression that
        // only handled a trailing `None` would pass a batch that always ends
        // with one.
        mixed: await create(
          `${testNamespace}-mixed-rule`,
          buildValuelessScoresMetric({
            scores: [
              { name: mixedGone, value: null },
              { name: mixedKept, value: 1.0 },
            ],
          }),
        ),
        // 0.0 is the value most easily confused with "no value": both are
        // falsy, and a filter written on truthiness rather than on nullness
        // would drop this score while every assertion about the mixed rule
        // still passed.
        zero: await create(
          `${testNamespace}-zero-rule`,
          buildValuelessScoresMetric({
            scores: [
              { name: zeroGone, value: null },
              { name: zeroKept, value: 0.0 },
            ],
          }),
        ),
        // The empty-batch corner: every score dropped must reach a clean no-op,
        // not an error path, and not a batch that fails because it is empty.
        allValueless: await create(
          `${testNamespace}-void-rule`,
          buildValuelessScoresMetric({
            scores: [
              { name: voidFirst, value: null },
              { name: voidSecond, value: null },
            ],
          }),
        ),
      };
    });

    const traceId = await test.step('Seed one trace for all four rules to judge', async () => {
      // One trace, four rules: the control and the list-returning rules are then
      // provably judging identical input, so a difference in outcome is a
      // difference in the metric and not in what it was given.
      const id = uuid7();
      const now = new Date();
      await backendClient.createTraceWithSource({
        id,
        projectName: project.name,
        name: `${testNamespace}-trace`,
        source: 'sdk',
        input: { q: 'whatever' },
        output: { output: SEED_OUTPUT },
        startTime: now,
        endTime: now,
      });
      return id;
    });

    await test.step('Control: the python evaluator is reachable and this project\'s rules fire', async () => {
      const score = await backendClient.pollTraceForFeedbackScore(traceId, controlName, {
        timeoutMs: 180_000,
      });
      expect(score.value, 'the control metric returns a constant 1.0').toBe(1.0);
    });

    const logsByRule = await test.step('Wait for all four rules to finish with the trace', async () => {
      // Every absence assertion below rests on this. The sampler enqueues rules
      // onto per-type Redis streams via `parallelStream()`, so one rule's score
      // landing says nothing about another's progress — and the all-valueless
      // rule never writes a score to wait on at all.
      const [control, mixed, zero, allValueless] = await Promise.all([
        waitForRuleToFinish(backendClient, rules.control, `${testNamespace}-control-rule`),
        waitForRuleToFinish(backendClient, rules.mixed, `${testNamespace}-mixed-rule`),
        waitForRuleToFinish(backendClient, rules.zero, `${testNamespace}-zero-rule`),
        waitForRuleToFinish(backendClient, rules.allValueless, `${testNamespace}-void-rule`),
      ]);
      return { control, mixed, zero, allValueless };
    });

    const settled = await test.step('Let the trace\'s score set settle before reading it', async () => {
      // The rules have all reported storing their scores, but the write and the
      // log line are separate hops. A short quiet period closes that gap
      // without a fixed sleep.
      // minScores is the three survivors, not 1: a settle that returned early
      // with a partial set would fail on the set comparison below with a diff
      // that reads like "the backend stored the wrong scores", when the real
      // cause is that this wait gave up too soon. Naming the number here makes
      // that case fail as "only N feedback score(s) ever appeared" instead.
      return backendClient.waitForTraceScoresSettled(traceId, {
        quietPeriodMs: 5_000,
        timeoutMs: 60_000,
        minScores: 3,
      });
    });

    await test.step('The trace carries exactly the valued scores, and no valueless one', async () => {
      // The whole set, not a lookup per name. Asserting only that the kept
      // scores are present would pass on a build that also stored a dropped
      // one, and asserting only that the dropped names are absent would pass on
      // a build that lost the whole batch — which is the bug this replaced.
      expect(
        settled.feedbackScores.map((s) => s.name).sort(),
        `only the valued scores may reach the trace: '${mixedGone}', '${zeroGone}', ` +
          `'${voidFirst}' and '${voidSecond}' carry no value and cannot be stored`,
      ).toEqual([controlName, mixedKept, zeroKept].sort());

      const byName = new Map(settled.feedbackScores.map((s) => [s.name, s.value]));
      expect(byName.get(mixedKept), 'the valued sibling of a dropped score is stored').toBe(1.0);
      expect(
        byName.get(zeroKept),
        '0.0 is a real score, not a missing one — storing it is the difference between ' +
          '"the metric scored zero" and "the metric did not answer"',
      ).toBe(0.0);
    });

    await test.step('Each rule reported its dropped scores and none of them failed', async () => {
      expectCleanDropLog(logsByRule.mixed, `${testNamespace}-mixed-rule`, [mixedGone]);
      expectCleanDropLog(logsByRule.zero, `${testNamespace}-zero-rule`, [zeroGone]);
      expectCleanDropLog(logsByRule.allValueless, `${testNamespace}-void-rule`, [
        voidFirst,
        voidSecond,
      ]);
      // The control has nothing to drop, so it must have warned about nothing.
      expect(
        logsByRule.control.filter((l) => l.message.includes(DROPPED_SCORE_REASON)),
        'the control rule returns a valued score, so it must report no dropped score',
      ).toEqual([]);
    });

    await test.step('The all-valueless rule stored an empty batch rather than erroring', async () => {
      // The corner the empty batch is really about: with nothing left to store,
      // the scorer must still reach its terminal line. `expectCleanDropLog`
      // above has already asserted the stream carries no ERROR.
      const terminal = logsByRule.allValueless.filter((l) =>
        l.message.includes(STORED_SUCCESSFULLY),
      );
      expect(
        terminal,
        'a batch whose every score was dropped is a clean no-op, reported exactly once',
      ).toHaveLength(1);
    });

    await test.step('The trace panel renders the survivors and no row for a dropped score', async () => {
      // The API says which scores landed; this says what a user reading the
      // trace sees. The absence half is the point: a row for a dropped score
      // would mean the UI had invented a value for it.
      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();
      const panel = await logs.openTraceById(traceId);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();

      for (const [name, value] of [
        [controlName, 1],
        [mixedKept, 1],
        [zeroKept, 0],
      ] as const) {
        await expect(
          panel.feedbackScoreRow(name),
          `the Feedback scores tab must show exactly one row for '${name}'`,
        ).toHaveCount(1);
        expect(await panel.readFeedbackScoreValue(name), `'${name}' renders its value`).toBe(value);
      }

      for (const dropped of [mixedGone, zeroGone, voidFirst, voidSecond]) {
        await expect(
          panel.feedbackScoreRow(dropped),
          `'${dropped}' carried no value, so the panel must show no row for it`,
        ).toHaveCount(0);
      }
    });
  });

  test('Span-scope and thread-scope rules drop valueless scores the same way', { tag: ['@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.rule-scope-thread-span'] }, async ({
    project,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    // No page: the subject is that the span and thread scorers — separate call
    // sites on separate Redis streams from the trace one — treat a valueless
    // score the same way. Neither entity's scores are rendered anywhere the
    // trace panel covers, and driving a browser to read them second-hand would
    // add a rendering failure mode to an assertion that has nothing to do with
    // rendering. The trace-scope test above owns the UI half.
    const spanKept = `${testNamespace}-span-kept`;
    const spanGone = `${testNamespace}-span-gone`;
    const threadKept = `${testNamespace}-thread-kept`;
    const threadGone = `${testNamespace}-thread-gone`;
    const spanRuleName = `${testNamespace}-span-rule`;
    const threadRuleName = `${testNamespace}-thread-rule`;
    const threadId = `${testNamespace}-thread`;

    const rules = await test.step('Create a span-scope and a thread-scope rule', async () => {
      return {
        span: await backendClient.createAutomationRule({
          projectId: project.id,
          name: spanRuleName,
          samplingRate: 1,
          type: 'span_user_defined_metric_python',
          metric: buildValuelessScoresMetric({
            scores: [
              { name: spanGone, value: null },
              { name: spanKept, value: 0.5 },
            ],
          }),
          arguments: OUTPUT_ARGUMENTS,
        }),
        thread: await backendClient.createAutomationRule({
          projectId: project.id,
          name: threadRuleName,
          samplingRate: 1,
          type: 'trace_thread_user_defined_metric_python',
          // No `arguments`: the thread scorer hands the metric the whole
          // conversation rather than a mapped section, and `context` is the
          // parameter it arrives as.
          metric: buildValuelessScoresMetric({
            scores: [
              { name: threadGone, value: null },
              { name: threadKept, value: 0.25 },
            ],
            scoreArgs: ['context'],
          }),
        }),
      };
    });

    await test.step('Both rules persisted the scope this test is about', async () => {
      // Runs before anything is seeded. Scope decides which Redis stream carries
      // the rule's messages, so a rule that silently fell back to the
      // trace-scope default would exercise the path the test above already
      // covers while still producing a plausible-looking result here.
      const span = await backendClient.getAutomationRule(rules.span);
      expect(span.type, 'the span rule must be span-scope').toBe('span_user_defined_metric_python');
      const thread = await backendClient.getAutomationRule(rules.thread);
      expect(thread.type, 'the thread rule must be thread-scope').toBe(
        'trace_thread_user_defined_metric_python',
      );
    });

    const spanId = await test.step('Seed a trace and one span under it', async () => {
      const parentTraceId = uuid7();
      const now = new Date();
      await backendClient.createTraceWithSource({
        id: parentTraceId,
        projectName: project.name,
        name: `${testNamespace}-span-parent`,
        source: 'sdk',
        input: { q: 'whatever' },
        output: { output: SEED_OUTPUT },
        startTime: now,
        endTime: now,
      });

      const id = uuid7();
      await backendClient.createSpan({
        id,
        traceId: parentTraceId,
        projectName: project.name,
        name: `${testNamespace}-span`,
        source: 'sdk',
        input: { q: 'whatever' },
        output: { output: SEED_OUTPUT },
      });
      return id;
    });

    const turnTraceIds = await test.step('Seed a two-turn thread and close it', async () => {
      // A thread is otherwise evaluated when the inactivity timeout expires,
      // which is a deployment-wide setting in minutes. Closing it explicitly is
      // the same transition, taken now.
      const start = new Date();
      const ids: string[] = [];
      for (const [index, turn] of [
        { input: 'q1', output: 'a1' },
        { input: 'q2', output: 'a2' },
      ].entries()) {
        ids.push(
          await backendClient.createTraceWithSource({
            id: uuid7(),
            projectName: project.name,
            name: `${testNamespace}-thread-turn-${index}`,
            source: 'sdk',
            input: { q: turn.input },
            output: { output: turn.output },
            // Turn order is derived from start_time; distinct stamps rather than a
            // sleep, so two writes landing in the same millisecond cannot reorder
            // the conversation.
            startTime: new Date(start.getTime() + index * 1_000),
            endTime: new Date(start.getTime() + index * 1_000 + 500),
            threadId,
          }),
        );
      }

      // The write endpoint answers 201 once the trace is accepted, not once it
      // is queryable, and closing the thread is what hands the conversation to
      // the scorer. Closing while a turn is still in flight risks the thread
      // being evaluated without it — which does not change WHICH scores this
      // metric returns (its list is fixed and it never reads `context`), but a
      // thread that closes before any turn is visible may not be evaluated at
      // all, and the poll below would then burn its full timeout reporting
      // "no score" for what was really a seeding race.
      await expect
        .poll(
          async () => {
            const traces = await Promise.all(ids.map((id) => backendClient.getTrace(id)));
            return traces.filter((t) => t !== null).length;
          },
          {
            timeout: 60_000,
            intervals: [500, 1_000, 2_000],
            message:
              `only some of the ${ids.length} seeded turns of thread '${threadId}' became ` +
              `readable, so closing it would hand the scorer a partial conversation`,
          },
        )
        .toBe(ids.length);

      await backendClient.closeThread({ projectName: project.name, threadId });
      return ids;
    });

    await test.step('The span carries the valued score and not the valueless one', async () => {
      const score = await backendClient.pollSpanForFeedbackScore(spanId, spanKept, {
        timeoutMs: 180_000,
      });
      expect(score.value, 'the span metric returns 0.5 for its valued score').toBe(0.5);

      const span = await backendClient.getSpan(spanId);
      expect(span, 'the seeded span must still exist to be asserted about').not.toBeNull();
      expect(
        span!.feedbackScores.map((s) => s.name).sort(),
        `'${spanGone}' carries no value, so the span must carry only '${spanKept}'`,
      ).toEqual([spanKept]);
    });

    await test.step('The thread carries the valued score and not the valueless one', async () => {
      const score = await backendClient.pollThreadForFeedbackScore(
        { projectId: project.id, threadId },
        threadKept,
        { timeoutMs: 180_000 },
      );
      expect(score.value, 'the thread metric returns 0.25 for its valued score').toBe(0.25);

      const thread = await backendClient.getThread({ projectId: project.id, threadId });
      expect(
        thread.feedbackScores.map((s) => s.name).sort(),
        `'${threadGone}' carries no value, so the thread must carry only '${threadKept}'`,
      ).toEqual([threadKept]);

      // The other half of "thread scope": the score landed on the thread and on
      // nothing else. Without this the test would pass on a build that also
      // wrote the thread rule's scores onto each turn — which is the trace-scope
      // behaviour, and the fallback this test's `type` assertion exists to rule
      // out. Asserting the whole set, not just the absence of `threadKept`, so a
      // dropped `threadGone` leaking onto a turn fails here too.
      for (const [index, turnTraceId] of turnTraceIds.entries()) {
        const turn = await backendClient.getTrace(turnTraceId);
        expect(turn, `seeded turn ${index} must still exist to be asserted about`).not.toBeNull();
        expect(
          turn!.feedbackScores.map((s) => s.name).sort(),
          `a thread-scope rule scores the thread, not its turns — turn ${index} ` +
            `(${turnTraceId}) must carry no feedback score at all`,
        ).toEqual([]);
      }
    });

    await test.step('Both rules reported their dropped score and neither failed', async () => {
      const spanLogs = await waitForRuleToFinish(backendClient, rules.span, spanRuleName);
      expectCleanDropLog(spanLogs, spanRuleName, [spanGone]);

      const threadLogs = await waitForRuleToFinish(backendClient, rules.thread, threadRuleName);
      expectCleanDropLog(threadLogs, threadRuleName, [threadGone]);
    });
  });
});
