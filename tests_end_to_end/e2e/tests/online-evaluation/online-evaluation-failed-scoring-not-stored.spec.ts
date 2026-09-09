import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { uuid7, type AutomationRuleLogRef } from '@e2e/core/backend';
import { buildScoreListMetric } from '@e2e/core/metrics';

/**
 * The 500-era wording, from before the evaluator classified anything. A
 * classified 400 arrives wrapped in the scorer's own "Unexpected error while
 * scoring …" preamble, which is a different (pre-existing) string — so this
 * still discriminates a classified rejection from the catch-all it replaced.
 */
const OPAQUE_FAILURE_MESSAGE = 'An unexpected error occurred';

/** Emitted once per evaluator call, immediately before the HTTP request. */
const EVALUATOR_CALL_LINE = 'to Python evaluator';

/** The two reasons a score cannot be stored, worded identically on both sides. */
const REASON_SCORING_FAILED = 'because the metric reported the scoring as failed';
const REJECTION_PREAMBLE =
  "400 Bad Request: The provided 'code' field didn't return any usable " +
  "'opik.evaluation.metrics.ScoreResult'";

/**
 * Wait until a rule's log stream satisfies `done`, then return the whole stream.
 *
 * Polled rather than read once: the scorer writes its lines as it goes, so a
 * single read can catch a rule mid-flight and make an absence assertion pass
 * against a stream that simply had not been written yet.
 */
async function pollRuleLogs(
  getLogs: (ruleId: string) => Promise<AutomationRuleLogRef[]>,
  ruleId: string,
  ruleName: string,
  done: (logs: AutomationRuleLogRef[]) => boolean,
  expectation: string,
): Promise<AutomationRuleLogRef[]> {
  let logs: AutomationRuleLogRef[] = [];
  await expect
    .poll(
      async () => {
        logs = await getLogs(ruleId);
        return done(logs);
      },
      {
        timeout: 180_000,
        intervals: [2_000, 5_000],
        message: `rule '${ruleName}' never ${expectation} — a silent stream means it was never invoked`,
      },
    )
    .toBe(true);
  return logs;
}

test.describe('Online Evaluation — a failed scoring is dropped, not stored as zero', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A python metric\'s failed scoring is dropped with a named warning, while its usable score still lands on the trace', { tag: ['@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.scores-in-trace-panel'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    // What used to happen: the SDK pairs `scoring_failed=True` with a
    // placeholder `0.0`, and the pre-fix split kept every score whose value was
    // non-null — so a scoring the metric itself gave up on was persisted as a
    // genuine zero. Nothing in the UI can tell those apart, which makes this a
    // silent-wrongness bug rather than a visible one, and it is why the control
    // rule below matters as much as the failing one.
    //
    // No existing spec can catch it: `online-evaluation-smoke.spec.ts` asserts
    // 0.0 scores that read identically before and after the fix.
    const usableName = `${testNamespace}-mixed-usable`;
    const failedName = `${testNamespace}-mixed-failed`;
    const zeroName = `${testNamespace}-deliberate-zero`;
    const unnamedProbeName = `${testNamespace}-unnamed-usable`;

    const rules = await test.step('Create three python rules over one project', async () => {
      const create = (name: string, metric: string) =>
        backendClient.createAutomationRule({
          projectId: project.id,
          name,
          samplingRate: 1,
          metric,
          // A resolvable mapping is mandatory: the backend refuses to call the
          // evaluator with an empty argument map, which would fail these rules
          // before their metric ever ran.
          arguments: { output: 'output.output' },
        });
      return {
        // Mixed: one usable score and one the metric flagged as failed. The
        // usable score is the anchor — it proves this rule ran and reached the
        // store, which is what makes its sibling's absence mean something.
        mixed: await create(
          `${testNamespace}-rule-mixed`,
          buildScoreListMetric([
            { name: usableName, value: 1.0 },
            { name: failedName, value: 0.0, scoringFailed: true },
          ]),
        ),
        // The control, and the reason this test cannot pass for the wrong
        // reason: a deliberate 0.0 with the flag unset must still be stored. A
        // fix that over-reached and dropped every zero would go green without it.
        zero: await create(
          `${testNamespace}-rule-zero`,
          buildScoreListMetric([
            { name: zeroName, value: 0.0, reason: 'genuinely zero' },
          ]),
        ),
        // A metric may leave a score unnamed. It must still be dropped, and
        // still be reported — as `<unnamed>` rather than as a blank.
        unnamed: await create(
          `${testNamespace}-rule-unnamed`,
          buildScoreListMetric([
            { name: unnamedProbeName, value: 1.0 },
            { name: '', value: 0.0, scoringFailed: true },
          ]),
        ),
      };
    });

    const traceId = uuid7();
    const traceName = `${testNamespace}-trace`;

    await test.step('Seed ONE trace for all three rules to judge', async () => {
      // One trace, three rules: every rule is then provably judging identical
      // input, so a difference in outcome is a difference in the metric.
      //
      // Seeded over REST rather than through the SDK bridge because the trace id
      // has to be known before the write, so the log-line assertions below can
      // name it exactly.
      const now = new Date();
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: traceName,
        source: 'sdk',
        input: { question: 'whatever' },
        output: { output: 'seed output' },
        startTime: now,
        // Without an end_time `OnlineScoringSampler.onTracesCreated` discards
        // the trace as a partial write and nothing here is ever evaluated.
        endTime: now,
      });
    });

    await test.step('Anchor: the usable score from the mixed rule arrived', async () => {
      // Every assertion below is an absence assertion. This is what stops them
      // passing against a trace no rule had got to yet.
      const score = await backendClient.pollTraceForFeedbackScore(traceId, usableName, {
        timeoutMs: 180_000,
      });
      expect(score.value, 'the usable half of the mixed metric stores unchanged').toBe(1.0);
    });

    await test.step('The trace carries exactly the three storable scores, and nothing else', async () => {
      // Settled, not sampled: the three rules are enqueued onto Redis streams
      // consumed independently, so one rule's score landing says nothing about
      // whether another has finished. Asserting the set too early would report a
      // score still in flight as correctly dropped.
      const settled = await backendClient.waitForTraceScoresSettled(traceId, {
        minScores: 3,
        timeoutMs: 180_000,
      });

      // The whole set, not a lookup of the names we expect: a `find()` for each
      // would pass just as happily on a trace that also carried the dropped
      // scores, which is the exact regression this test exists to catch.
      expect(
        settled.feedbackScores.map((s) => `${s.name}=${s.value}`).sort(),
        'a failed scoring must not be stored, and a deliberate zero must be',
      ).toEqual([`${unnamedProbeName}=1`, `${usableName}=1`, `${zeroName}=0`].sort());

      expect(
        settled.feedbackScores.map((s) => s.name),
        'the unnamed failed score must not land as a blank-named row either',
      ).not.toContain('');
    });

    await test.step('The mixed rule warned by name, and stored the rest of its batch', async () => {
      const logs = await pollRuleLogs(
        (id) => backendClient.getAutomationRuleLogs(id),
        rules.mixed,
        `${testNamespace}-rule-mixed`,
        (l) => l.some((line) => line.level === 'WARN'),
        'warned about the score it dropped',
      );

      // The full line, not a fragment: the point of the fix is that the user is
      // told WHICH score was dropped, for WHICH trace, and WHY. A test matching
      // only the reason would still pass if the name stopped being interpolated.
      //
      // Safe to match exactly because the name cannot reach the log's 100-char
      // sanitize limit, past which it would arrive truncated with an ellipsis:
      // `testNamespace` caps its slug at 40 chars, so the longest name this spec
      // can build stays in the 80s however the test is retitled.
      expect(
        logs.filter((l) => l.level === 'WARN').map((l) => l.message),
        'the warning must name the dropped score, its trace and the cause',
      ).toContain(`Skipped '${failedName}' for traceId '${traceId}' ${REASON_SCORING_FAILED}`);

      // A dropped score must not cost the batch it arrived in — the same rule,
      // for the same trace, still reports the score it could store.
      const stored = logs.filter(
        (l) => l.level === 'INFO' && l.message.includes('stored successfully'),
      );
      expect(
        stored.map((l) => l.message).join('\n---\n'),
        'the usable score from the same batch is still stored and reported',
      ).toContain(usableName);
      expect(
        stored.map((l) => l.message).join('\n---\n'),
        'the dropped score must not be reported as stored',
      ).not.toContain(failedName);
    });

    await test.step('The unnamed dropped score is reported as <unnamed>, not as a blank', async () => {
      const logs = await pollRuleLogs(
        (id) => backendClient.getAutomationRuleLogs(id),
        rules.unnamed,
        `${testNamespace}-rule-unnamed`,
        (l) => l.some((line) => line.level === 'WARN'),
        'warned about its unnamed dropped score',
      );
      expect(
        logs.filter((l) => l.level === 'WARN').map((l) => l.message),
        'an unnamed score is rendered, not omitted — the count a user sees must still match',
      ).toContain(`Skipped '<unnamed>' for traceId '${traceId}' ${REASON_SCORING_FAILED}`);
    });

    await test.step('The trace panel renders the stored scores and no row for the dropped one', async () => {
      // Written over REST, read back through the UI. The disagreement this
      // catches is the one that made the old behaviour invisible: a stored
      // placeholder 0.0 renders in this table as an ordinary, believable zero.
      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();
      const panel = await logs.openTraceById(traceId);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();

      await expect(
        panel.feedbackScoreRow(usableName),
        'the usable score renders — the anchor for the absence assertions below',
      ).toHaveCount(1);
      expect(await panel.readFeedbackScoreValue(usableName)).toBe(1.0);

      await expect(
        panel.feedbackScoreRow(zeroName),
        'a deliberate zero still renders as a row',
      ).toHaveCount(1);
      expect(
        await panel.readFeedbackScoreValue(zeroName),
        'the fix must not swallow a zero the metric meant',
      ).toBe(0);

      await expect(
        panel.feedbackScoreRow(failedName),
        'a failed scoring has no row to render',
      ).toHaveCount(0);

      // Every score this test seeds is namespaced, so this counts the panel's
      // whole answer rather than just confirming ours are among it — a fourth
      // row, whatever its name, fails here.
      await expect(
        panel.feedbackScoresTabPanel.getByRole('row').filter({ hasText: testNamespace }),
        'the panel shows exactly the three storable scores',
      ).toHaveCount(3);
    });
  });

  test('A wholly unusable evaluator response is a 400 naming each offending score and its reason, and stores nothing', { tag: ['@cap:online-evaluation.python-rule-scores'] }, async ({
    project,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    // No page: this is a rejection with nothing to render. The message exists
    // only on the rule's log stream, and driving a browser to read it
    // second-hand would add a rendering failure mode to an assertion that has
    // nothing to do with rendering.
    //
    // The sibling half of the same fix, and a different code path: the python
    // backend's endpoint rejects the whole response before the Java scorer ever
    // gets a list to split.
    const allFailedA = `${testNamespace}-allfailed-a`;
    const allFailedB = `${testNamespace}-allfailed-b`;
    const valuelessName = `${testNamespace}-valueless`;

    const rules = await test.step('Create two rules whose every score is unusable', async () => {
      const create = (name: string, metric: string) =>
        backendClient.createAutomationRule({
          projectId: project.id,
          name,
          samplingRate: 1,
          metric,
          arguments: { output: 'output.output' },
        });
      return {
        // Both scores flagged failed — and both must be named in the rejection,
        // which is what stops a future refactor reporting only the first.
        allFailed: await create(
          `${testNamespace}-rule-allfailed`,
          buildScoreListMetric([
            { name: allFailedA, value: 0.0, scoringFailed: true },
            { name: allFailedB, value: 0.0, scoringFailed: true },
          ]),
        ),
        // The other reason a score is unusable. Pinned separately because the
        // two reasons are the thing a refactor is most likely to collapse back
        // into one generic message.
        valueless: await create(
          `${testNamespace}-rule-valueless`,
          buildScoreListMetric([{ name: valuelessName, value: null }]),
        ),
      };
    });

    const traceId = uuid7();

    await test.step('Seed one trace for both rules to judge', async () => {
      const now = new Date();
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-trace`,
        source: 'sdk',
        input: { question: 'whatever' },
        output: { output: 'seed output' },
        startTime: now,
        endTime: now,
      });
    });

    const hasError = (logs: AutomationRuleLogRef[]) => logs.some((l) => l.level === 'ERROR');

    const allFailedLogs = await test.step(
      'The all-failed rule is rejected with a 400 naming BOTH scores and the cause',
      async () => {
        const logs = await pollRuleLogs(
          (id) => backendClient.getAutomationRuleLogs(id),
          rules.allFailed,
          `${testNamespace}-rule-allfailed`,
          hasError,
          'reported a failure',
        );
        const errors = logs.filter((l) => l.level === 'ERROR').map((l) => l.message).join('\n---\n');
        expect(
          errors,
          'a wholly unusable response is a classified user error, at 400 rather than 500',
        ).toContain(
          `${REJECTION_PREAMBLE}: '${allFailedA}' reported the scoring as failed, ` +
            `'${allFailedB}' reported the scoring as failed`,
        );
        return logs;
      },
    );

    const valuelessLogs = await test.step(
      'The valueless rule is rejected with the same 400, reported as no value',
      async () => {
        const logs = await pollRuleLogs(
          (id) => backendClient.getAutomationRuleLogs(id),
          rules.valueless,
          `${testNamespace}-rule-valueless`,
          hasError,
          'reported a failure',
        );
        const errors = logs.filter((l) => l.level === 'ERROR').map((l) => l.message).join('\n---\n');
        expect(
          errors,
          'a score with no value is reported by its own reason, not the failed-scoring one',
        ).toContain(`${REJECTION_PREAMBLE}: '${valuelessName}' returned no value`);
        return logs;
      },
    );

    await test.step('Neither rejection fell back to the opaque 500 wording', async () => {
      for (const [name, logs] of [
        [`${testNamespace}-rule-allfailed`, allFailedLogs],
        [`${testNamespace}-rule-valueless`, valuelessLogs],
      ] as const) {
        for (const line of logs) {
          expect(
            line.message,
            `rule '${name}' must not report the pre-classification catch-all`,
          ).not.toContain(OPAQUE_FAILURE_MESSAGE);
        }
      }
    });

    await test.step('A rejected response stores no score at all', async () => {
      // The ERROR lines above are what make this meaningful: both rules provably
      // ran and provably answered, so an empty score set is a decision rather
      // than a rule that never fired.
      const detail = await backendClient.getTrace(traceId);
      expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
      expect(
        detail!.feedbackScores.map((s) => `${s.name}=${s.value}`),
        'a rejected evaluator response is stored in part by nobody',
      ).toEqual([]);
    });

    await test.step('Each rule called the evaluator once and reported once', async () => {
      // A 400 is a terminal answer: the caller must not re-run the metric hoping
      // for a different one. Both counts are asserted because they fail
      // differently — a re-queued message repeats the call line, while a retry
      // loop that eventually gives up repeats only the error line.
      for (const [name, logs] of [
        [`${testNamespace}-rule-allfailed`, allFailedLogs],
        [`${testNamespace}-rule-valueless`, valuelessLogs],
      ] as const) {
        expect(
          logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)),
          `rule '${name}' must send the trace to the evaluator exactly once`,
        ).toHaveLength(1);
        expect(
          logs.filter((l) => l.level === 'ERROR'),
          `rule '${name}' must report its terminal failure exactly once`,
        ).toHaveLength(1);
      }
    });
  });
});
