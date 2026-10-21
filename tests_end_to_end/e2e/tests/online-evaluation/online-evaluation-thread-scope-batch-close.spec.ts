import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { uuid7 } from '@e2e/core/backend';
import { buildThreadScoreMetric } from '@e2e/core/metrics';

/**
 * Thread-scope online evaluation over a BATCH close (OPIK-8262 / #8162).
 *
 * Closing threads is what triggers thread-scope scoring, and a close can carry
 * many thread ids at once. #8162 changed what happens next: the publisher still
 * makes ONE grouped pass over the close, collecting the threads each rule
 * sampled (`TraceThreadOnlineScorerPublisher`), but it now fans that set out to
 * one stream entry PER THREAD ID rather than one entry carrying the whole list
 * (`OnlineScorePublisher#enqueueThreadMessage`), so each thread is acked and
 * redelivered on its own.
 *
 * That fan-out is what this spec pins, because two failures are still possible
 * and both are silent:
 *
 *   - a thread in the close is never enqueued at all. The grouped pass reads
 *     each thread's persisted sampling decision and skips the threads missing
 *     from it, which from the outside is indistinguishable from a rule that
 *     legitimately declined to score that thread;
 *   - one thread whose metric raises takes its siblings down with it. Per-entry
 *     delivery makes that structurally unlikely now, which is exactly why it
 *     needs a test: nothing else would catch a regression that collapsed the
 *     fan-out back to one shared entry under one shared verdict.
 *
 * Neither surfaces as an error a user would see. A thread simply has no score.
 *
 * Deterministic by construction: the metric is a constant-value python metric
 * that raises only on a marker this spec seeds itself, so no provider key and no
 * model verdict is in the loop.
 */

/** How long the seeded threads may take to materialise, and to become readable by id. */
const THREADS_VISIBLE_TIMEOUT_MS = 120_000;

/**
 * How long the batch may take to be scored. Generous on purpose: the property
 * is that every thread in one close IS scored, not that it happens quickly, and
 * a tight budget would turn a slow environment into a failure that reads like a
 * dropped thread. Observed latency on staging is ~12s for six threads.
 */
const SCORING_TIMEOUT_MS = 240_000;

/**
 * How long the settled state must hold before "the poisoned thread was not
 * scored" counts as an answer rather than as "not yet".
 *
 * The failing thread's error is re-surfaced to `BaseRedisSubscriber` once its
 * own entry finishes, so that entry can be redelivered. This window is what
 * makes the two outcomes distinguishable: a redelivery that eventually scored
 * the poisoned thread, or that re-scored a sibling, lands inside it.
 */
const QUIET_PERIOD_MS = 30_000;

/**
 * How long to let the sampler's decision land after the threads become listable.
 *
 * Not a readiness wait dressed up as a sleep — there is nothing to wait ON. A
 * thread's row and its `sampling_per_rule` map are written by two different
 * paths: the row is what makes the thread listable, and a listener on an async
 * event bus then writes the sampling decision, with nothing synchronising the
 * two. The close only READS that map, so a close issued the instant the sixth
 * thread appears can find it empty, enqueue nothing, and surface four minutes
 * later as a missing score — a failure that reads exactly like the product bug
 * this spec is meant to catch.
 *
 * The map is not on the public thread API, so a spec cannot poll for it. Until
 * it is, this margin is the honest mitigation.
 */
const SAMPLING_COMMIT_MARGIN_MS = 10_000;

/** Rule creation and read-back, twelve seed writes, the close, and two panel loads. */
const SETUP_AND_UI_BUDGET_MS = 240_000;

/**
 * Derived, not chosen: each budget above may legitimately be spent in full, and
 * a flat number would let Playwright abort mid-step and report its own timeout
 * instead of the one that ran out — "the run timed out" reads nothing like "a
 * thread in the batch was never scored".
 */
const TEST_TIMEOUT_MS =
  THREADS_VISIBLE_TIMEOUT_MS * 2 +
  SCORING_TIMEOUT_MS +
  QUIET_PERIOD_MS +
  SAMPLING_COMMIT_MARGIN_MS +
  SETUP_AND_UI_BUDGET_MS;

const THREAD_COUNT = 6;
const TURNS_PER_THREAD = 2;

/**
 * Which thread's metric raises. Mid-batch deliberately: the scorer walks the
 * message's thread ids, so a poisoned FIRST id would leave "the siblings
 * survived" provable only for ids after it, and a poisoned LAST id only for ids
 * before it. From the middle, both directions are asserted at once.
 */
const POISONED_THREAD_INDEX = 3;

/** Seeded into the poisoned thread's first turn, and matched by the metric. */
const POISON_MARKER = 'POISON-e2e-thread-scope';

/**
 * The name the score lands under. Constant rather than namespaced, because the
 * engine writes the ScoreResult's own name verbatim and this one is read out of
 * a UI cell — a run-prefixed name is truncated with a CSS ellipsis in the Key
 * column. Uniqueness is not at stake: the threads live in this test's own
 * project, and the only rule scoring them is this one.
 */
const SCORE_NAME = 'thread_batch_score';

/**
 * Not 0 and not 1. A metric that ran on unexpected input and a metric that was
 * never invoked both tend to look like 0, and 1 is what half the estate's
 * constant metrics return — a value that is neither makes "the right rule
 * scored this thread" a claim that can fail.
 */
const SCORE_VALUE = 0.75;

test.describe('Online Evaluation — thread scope', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A single batch thread close scores every thread exactly once, and one failing thread does not take its siblings down', { tag: ['@cap:online-evaluation.rule-scope-thread-span', '@cap:online-evaluation.python-rule-scores'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    test.setTimeout(TEST_TIMEOUT_MS);

    const ruleName = `${testNamespace}-thread-rule`;
    const threadId = (index: number) => `${testNamespace}-thread-${index}`;
    const threadIds = Array.from({ length: THREAD_COUNT }, (_, i) => threadId(i));
    const poisonedThreadId = threadId(POISONED_THREAD_INDEX);
    const benignThreadIds = threadIds.filter((id) => id !== poisonedThreadId);

    const ruleId = await test.step('Create a 100% thread-scope python rule', async () => {
      // Created BEFORE any trace is written, and that ordering is a
      // precondition rather than tidiness: the backend decides which
      // thread-scope rules sample a thread when it materialises the thread from
      // its first traces. A rule created afterwards samples nothing, and every
      // assertion below would then fail for a reason that has nothing to do
      // with batching.
      return backendClient.createAutomationRule({
        projectId: project.id,
        name: ruleName,
        type: 'trace_thread_user_defined_metric_python',
        samplingRate: 1,
        metric: buildThreadScoreMetric(SCORE_NAME, SCORE_VALUE, POISON_MARKER),
      });
    });

    await test.step('The rule really is thread-scope, enabled, and sampling everything', async () => {
      // Without this the spec could silently have created a TRACE-scope rule —
      // the API's default type — and then spend ten minutes asserting about a
      // stream it never touched. Sampling and enabled are read back for the same
      // reason: at any rate below 1 an unscored thread is a legitimate outcome,
      // so every assertion below would stop meaning anything.
      const rule = await backendClient.getAutomationRule(ruleId);
      expect(rule.type, 'the rule must score threads, not traces or spans').toBe(
        'trace_thread_user_defined_metric_python',
      );
      expect(rule.samplingRate, 'every thread must be eligible').toBe(1);
      expect(rule.enabled, 'a disabled rule scores nothing at all').toBe(true);
    });

    const poisonedFirstTraceId = await test.step(
      `Seed ${THREAD_COUNT} threads of ${TURNS_PER_THREAD} turns, one of them poisoned`,
      async () => {
        let poisonedTraceId = '';
        for (let t = 0; t < THREAD_COUNT; t++) {
          for (let turn = 0; turn < TURNS_PER_THREAD; turn++) {
            const id = uuid7();
            const poisoned = t === POISONED_THREAD_INDEX && turn === 0;
            if (poisoned) poisonedTraceId = id;
            const now = new Date();
            await backendClient.createTraceWithSource({
              id,
              projectName: project.name,
              name: `${testNamespace}-t${t}-turn${turn}`,
              source: 'sdk',
              input: {
                question: poisoned ? `is this safe? ${POISON_MARKER}` : `question ${turn}`,
              },
              output: { answer: `answer ${turn}` },
              threadId: threadId(t),
              startTime: now,
              // A trace with no end_time is a partial write the sampler drops,
              // so a thread built from them would never be scored.
              endTime: now,
            });
          }
        }
        return poisonedTraceId;
      },
    );

    await test.step('The poison marker really reached storage', async () => {
      // The seed has to prove it discriminates. If ingest had dropped or
      // rewritten the marker, the poisoned thread would score like every other
      // one and the isolation half of this spec would be asserting nothing —
      // worse, it would read as coverage of a failure path nobody exercised.
      const stored = await backendClient.getTrace(poisonedFirstTraceId);
      expect(stored, 'the poisoned turn must exist to be asserted about').not.toBeNull();
      expect(
        JSON.stringify(stored!.input),
        'the metric raises on this marker; without it nothing fails and nothing is proved',
      ).toContain(POISON_MARKER);
    });

    await test.step('All six threads exist before anything is closed', async () => {
      // Threads are derived from their traces asynchronously. Closing an id the
      // backend has not materialised yet closes nothing, so this barrier is
      // what makes the single call below a batch of six rather than a batch of
      // however many happened to be ready.
      await expect
        .poll(
          async () => {
            const { threads } = await backendClient.listThreads({ projectId: project.id });
            return threads
              .map((t) => t.id)
              .filter((id) => threadIds.includes(id))
              .sort();
          },
          {
            timeout: THREADS_VISIBLE_TIMEOUT_MS,
            intervals: [2_000, 5_000],
            message: 'the seeded threads never materialised, so there is no batch to close',
          },
        )
        .toEqual([...threadIds].sort());
    });

    await test.step('Let the sampling decision commit before closing', async () => {
      // See SAMPLING_COMMIT_MARGIN_MS: the close reads a map the sampler writes
      // on a path this spec has no way to observe.
      await new Promise((r) => setTimeout(r, SAMPLING_COMMIT_MARGIN_MS));
    });

    await test.step('Close all six thread ids in ONE call', async () => {
      // The single multi-id close is the subject: it is the one call that makes
      // the publisher's grouped pass see six sampled threads at once and fan
      // them out to six independent stream entries. Six separate closes would
      // drive six separate one-thread passes and say nothing about that.
      await backendClient.closeThreads({
        projectName: project.name,
        threadIds,
      });

      // The close is asserted to have reached every id independently of
      // scoring: if it had only closed some of them, "a thread was not scored"
      // below would be true for a reason that is not the one under test.
      await expect
        .poll(
          async () => {
            const { threads } = await backendClient.listThreads({ projectId: project.id });
            return threads
              .filter((t) => threadIds.includes(t.id))
              .map((t) => `${t.id}=${t.status}`)
              .sort();
          },
          {
            timeout: 60_000,
            intervals: [1_000, 2_000, 5_000],
            message: 'not every thread in the batch was closed by the single call',
          },
        )
        .toEqual([...threadIds].sort().map((id) => `${id}=inactive`));
    });

    const readThreadScores = async (id: string) => {
      const thread = await backendClient.getThread({ projectId: project.id, threadId: id });
      // The whole set, not a find() of our own score: a rule that also wrote
      // something it should not have is exactly what a lookup would pass
      // through.
      return thread.feedbackScores.map((s) => ({ name: s.name, value: s.value }));
    };

    await test.step('Every thread is readable by id before any score is asserted', async () => {
      // A readiness barrier, deliberately separate from the score assertions.
      //
      // `getThread` is the by-id read (`POST /traces/threads/retrieve`), which
      // resolves the project through a different path than the listing above —
      // and shortly after a project is created it has been observed to answer
      // 404 "Project not found" while `GET /threads` is already serving that
      // same project's threads. Waiting for the by-id read to come up here keeps
      // that startup race out of the assertions below, where `expect.poll`
      // surfaces a thrown 404 immediately rather than retrying it.
      //
      // This cannot hide a real absence: the poll asserts every thread becomes
      // readable, so a project or thread that is genuinely gone fails here,
      // naming the ids that never resolved.
      await expect
        .poll(
          async () => {
            const readable: string[] = [];
            for (const id of threadIds) {
              try {
                await backendClient.getThread({ projectId: project.id, threadId: id });
                readable.push(id);
              } catch {
                // Not readable yet — the poll's own deadline is the failure.
              }
            }
            return readable.sort();
          },
          {
            timeout: THREADS_VISIBLE_TIMEOUT_MS,
            intervals: [1_000, 2_000, 5_000],
            message: 'a closed thread never became readable by id, so its scores cannot be asserted',
          },
        )
        .toEqual([...threadIds].sort());
    });

    await test.step('Every benign thread carries exactly one score, at the constant value', async () => {
      const expected = benignThreadIds.map(() => [{ name: SCORE_NAME, value: SCORE_VALUE }]);
      await expect
        .poll(
          async () => Promise.all(benignThreadIds.map(readThreadScores)),
          {
            timeout: SCORING_TIMEOUT_MS,
            intervals: [2_000, 5_000],
            message:
              'a thread published in the batch was never scored — the failing sibling took it down, ' +
              'or the close published fewer ids than it was given',
          },
        )
        .toEqual(expected);
    });

    await test.step('The poisoned thread was attempted, failed, and scored nothing', async () => {
      // Positive evidence first. "No score" on its own is satisfied by a thread
      // that was never sent to the evaluator at all, which is the OTHER bug —
      // so the log stream is what separates "the metric raised, and the failure
      // stayed here" from "this thread was quietly dropped from the close".
      //
      // Quoted, because the engine writes thread ids as `threadId '<id>'` and a
      // bare substring match would let `...-thread-1` be satisfied by a line
      // about `...-thread-10`.
      const quoted = (id: string) => `'${id}'`;

      const readLogSections = async () => {
        const logs = await backendClient.getAutomationRuleLogs(ruleId);
        return {
          errorText: logs
            .filter((l) => l.level === 'ERROR')
            .map((l) => l.message)
            .join('\n---\n'),
          // `Evaluating threadId '<id>' sampled by rule '<name>'` — the marker
          // the scorer writes per thread, before it calls the metric.
          evaluatedText: logs
            .filter((l) => l.message.includes('Evaluating threadId'))
            .map((l) => l.message)
            .join('\n---\n'),
        };
      };

      // Polled, not read once. The evaluator log is flushed on its own path, so
      // a snapshot taken the moment the last benign score lands can still be
      // missing the poisoned thread's error — a one-shot read fails
      // intermittently on a run that was entirely correct.
      //
      // Both positive facts are polled together: every id in the single close
      // reached the scorer, and the poisoned one was reported as failed. This is
      // the fan-out property stated directly rather than inferred from the
      // scores — a publish that dropped ids would still leave the rest scored,
      // and fails here naming the ones that never appeared.
      await expect
        .poll(
          async () => {
            const { errorText, evaluatedText } = await readLogSections();
            return {
              notEvaluated: threadIds.filter((id) => !evaluatedText.includes(quoted(id))),
              poisonedFailureLogged: errorText.includes(quoted(poisonedThreadId)),
            };
          },
          {
            timeout: SCORING_TIMEOUT_MS,
            intervals: [2_000, 5_000],
            message:
              'the evaluator log never showed every closed thread being evaluated, or never ' +
              'reported the poisoned thread as failed',
          },
        )
        .toEqual({ notEvaluated: [], poisonedFailureLogged: true });

      // Only now the negative half, against a log known to have arrived. Made
      // after the poll on purpose: against an empty snapshot every one of these
      // would pass while proving nothing.
      const { errorText } = await readLogSections();
      for (const benign of benignThreadIds) {
        expect(
          errorText,
          `no failure may be reported against sibling ${benign}`,
        ).not.toContain(quoted(benign));
      }

      expect(
        await readThreadScores(poisonedThreadId),
        'a metric that raised must store nothing at all',
      ).toEqual([]);
    });

    await test.step('The outcome is stable, not a snapshot mid-retry', async () => {
      // The batch's first error is re-surfaced on the message's error path, so
      // the message may be retried whole. Re-reading after a quiet window is
      // what distinguishes a settled result from one caught between attempts:
      // a retry that re-scored the siblings, or that eventually scored the
      // poisoned thread, changes one of these two answers.
      //
      // A duration, not a state wait, and deliberately so — the claim IS that
      // nothing changes over an interval, which no locator or response can
      // stand in for. Same reasoning as `waitForTraceScoresSettled`'s
      // `quietPeriodMs`; this is not a sleep standing in for a missing signal.
      await new Promise((r) => setTimeout(r, QUIET_PERIOD_MS));

      expect(
        await Promise.all(benignThreadIds.map(readThreadScores)),
        'no sibling may gain a second score',
      ).toEqual(benignThreadIds.map(() => [{ name: SCORE_NAME, value: SCORE_VALUE }]));
      expect(
        await readThreadScores(poisonedThreadId),
        'the poisoned thread must stay unscored',
      ).toEqual([]);
    });

    await test.step('The thread panel shows the score, and shows none for the failed thread', async () => {
      // Written over REST, read back through the UI. A backend-only pass would
      // hide the failure a user actually reports: a Feedback scores tab that
      // says nothing landed while the API holds the score. The Threads table
      // hides score columns by default, so the panel's tab is where a thread
      // score is actually read.
      const logs = new LogsPage(page);
      await logs.gotoThreads(project.id);
      await logs.waitForThreadsReady(benignThreadIds[0]);

      const benignPanel = await logs.openThreadById(benignThreadIds[0]);
      await benignPanel.waitForFullyLoaded();
      await benignPanel.openFeedbackScoresTab();
      await expect(
        benignPanel.feedbackScoreRow(SCORE_NAME),
        'exactly one rule scored this thread, so exactly one row may be present',
      ).toHaveCount(1);
      expect(
        await benignPanel.readFeedbackScoreValue(SCORE_NAME),
        'the panel must render the constant value, not just a row',
      ).toBe(SCORE_VALUE);

      await logs.waitForThreadsReady(poisonedThreadId);
      const poisonedPanel = await logs.openThreadById(poisonedThreadId);
      await poisonedPanel.waitForFullyLoaded();
      await poisonedPanel.openFeedbackScoresTab();
      // `openFeedbackScoresTab` waits for the tab panel itself, so this is an
      // assertion about a rendered table rather than about a page that had not
      // painted yet.
      await expect(
        poisonedPanel.feedbackScoreRow(SCORE_NAME),
        'the failed thread must show no score at all — not a zero, not a blank row',
      ).toHaveCount(0);
    });
  });
});
