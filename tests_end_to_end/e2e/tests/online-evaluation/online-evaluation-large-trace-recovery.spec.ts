import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { uuid7 } from '@e2e/core/backend';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

/**
 * Online scoring must survive an oversized trace.
 *
 * Every project's rules are fed from one shared Redis stream. A trace the
 * consumer cannot decode used to stop that stream for the whole deployment —
 * not for that trace, for every trace after it — silently, with no error
 * surfaced anywhere in the product, and recoverable only by hand. The codec now
 * carries the configured payload limits, so an oversized entry is dealt with
 * and the consumer keeps moving.
 *
 * Nothing in the estate can catch a regression here: every other
 * online-evaluation spec logs one-sentence outputs, so the stream is never
 * asked to carry anything large and all of them would stay green while scoring
 * was wedged for everyone.
 *
 * **What this asserts is recovery, not a size boundary.** A small trace seeded
 * AFTER the oversized one still gets its score. It deliberately does NOT assert
 * that the oversized trace itself is scored: exploration on 2.2.46 found a
 * Python rule stops producing a score at exactly 131,072 chars (128 KiB) of
 * *mapped-field* content — well below any size this spec sends — for a reason
 * downstream of the stream (an unmapped 1,000,000-char `input` scores fine).
 * That is a separate, unresolved question about the evaluator request path;
 * pinning a scored/not-scored boundary here would encode it as intended
 * behaviour. The stream not wedging is the guarantee, and it is the one a
 * regression would break catastrophically.
 */

/**
 * The oversized `output.answer`, in characters.
 *
 * Above the 20,000,000-char ceiling the JSON parser applies by default, which
 * is the limit the decode path has to be configured past for the trace to be
 * handled rather than to poison the stream. Kept just above it rather than at
 * the 25 MB exploration used: the point is to clear the ceiling, and every
 * megabyte past it is only upload time.
 */
const OVERSIZED_CHARS = 21_000_000;

/**
 * How long a small trace may take to be scored. Observed on staging: 1-7s.
 * Sized to match the sibling online-evaluation specs — long enough that a busy
 * shared engine is not mistaken for a wedged one, short enough that a genuinely
 * stalled stream fails the run rather than hanging it.
 */
const SCORE_TIMEOUT_MS = 120_000;

test.describe('Online Evaluation — oversized trace recovery', { tag: ['@area:online-evaluation'] }, () => {
  test(
    'A trace far above the payload ceiling does not stop the scoring stream: the next small trace is still scored and renders in the trace panel',
    { tag: ['@t2-cuj', '@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.scores-in-trace-panel'] },
    async ({ project, backendClient, testNamespace, page, automationRulesCleanup }) => {
      // Uploading 21 MB and waiting on two scoring round-trips. The inner polls
      // fail first with a diagnostic naming the trace, so this is a backstop,
      // not the assertion.
      test.setTimeout(600_000);

      const ruleName = `${testNamespace}-oversized`;

      const ruleId = await test.step('Create a full-rate Python constant-score rule', async () => {
        // Created through the API rather than the dialog: the dialog's Python
        // editor is covered elsewhere, and what this spec needs is a rule whose
        // score is deterministic, so "no score" can only mean "not evaluated".
        return backendClient.createAutomationRule({
          projectId: project.id,
          name: ruleName,
          samplingRate: 1,
          metric: buildConstantScoreMetric(ruleName),
          arguments: { output: 'output.answer' },
        });
      });

      await test.step('The rule persisted the rate this spec depends on', async () => {
        // Runs before anything is seeded. A rule that silently landed at a
        // partial rate would let a missing score read as sampling rather than
        // as a stalled stream, and the whole test would then prove nothing.
        const rule = await backendClient.getAutomationRule(ruleId);
        expect(rule.samplingRate, 'every trace must be eligible').toBe(1);
        expect(rule.enabled, 'a disabled rule would skip every trace').toBe(true);
      });

      /**
       * Seeds one trace through the REST write. `end_time` is mandatory:
       * `OnlineScoringSampler.onTracesCreated` discards a trace with a null
       * end_time as a partial write, so a trace seeded without one is never
       * scored and an assertion built on it could not fail.
       */
      const seedTrace = async (label: string, answer: string) => {
        const id = uuid7();
        const now = new Date();
        await backendClient.createTraceWithSource({
          id,
          projectName: project.name,
          name: `${testNamespace}-${label}`,
          source: 'sdk',
          input: { q: 'whatever' },
          output: { answer },
          startTime: now,
          endTime: now,
        });
        return { id, name: `${testNamespace}-${label}` };
      };

      const control = await test.step(
        'Seed a small control trace and confirm the stream is scoring at all',
        async () => {
          // Without this the test cannot distinguish "the oversized trace wedged
          // the stream" from "scoring was already down before we started" —
          // which would report an unrelated outage as this regression.
          const trace = await seedTrace('control', 'a short answer');
          const score = await backendClient.pollTraceForFeedbackScore(trace.id, ruleName, {
            timeoutMs: SCORE_TIMEOUT_MS,
          });
          expect(score.value, 'the constant metric scores 1.0 for anything it is handed').toBe(
            1.0,
          );
          return trace;
        },
      );

      await test.step(
        `Seed a trace whose mapped output is ${OVERSIZED_CHARS.toLocaleString('en-US')} chars`,
        async () => {
          // Accepted or not, the stream must keep moving — createTraceWithSource
          // throws on anything but 201, so a backend that starts rejecting the
          // write outright is a distinct, legible failure rather than a silent
          // change of what this spec exercises.
          const oversized = await seedTrace('oversized', 'x'.repeat(OVERSIZED_CHARS));
          expect(oversized.id, 'the oversized write was accepted').toBeTruthy();
        },
      );

      const probe = await test.step(
        'A small trace seeded AFTER the oversized one is still scored',
        async () => {
          // The whole assertion. Seeded strictly after the oversized write has
          // returned, so it is behind it in the stream: if the oversized entry
          // had poisoned the consumer, this score never arrives.
          const trace = await seedTrace('probe', 'another short answer');
          const score = await backendClient.pollTraceForFeedbackScore(trace.id, ruleName, {
            timeoutMs: SCORE_TIMEOUT_MS,
          });
          expect(
            score.value,
            `the probe trace was evaluated after the oversized one; a 0.0 would mean the ` +
              `metric ran on unexpected input rather than that the stream recovered`,
          ).toBe(1.0);
          return trace;
        },
      );

      await test.step('The control trace kept its score too', async () => {
        // A stream that recovered by dropping everything queued behind the
        // oversized entry, or a retry storm that re-wrote scores, would not be
        // caught by the probe alone.
        const trace = await backendClient.getTrace(control.id);
        expect(trace, 'the control trace must still exist to be asserted about').not.toBeNull();
        expect(
          trace!.feedbackScores.filter((s) => s.name === ruleName),
          'exactly one score from this rule, still on the control trace',
        ).toHaveLength(1);
      });

      await test.step('The probe trace renders its score in the trace panel', async () => {
        // The API says the score landed; this says a user can see it. A trace
        // whose Feedback scores tab reads "No feedback scores yet" is what a
        // wedged stream looks like from the product.
        const logs = new LogsPage(page);
        await logs.goto(project.id);
        await logs.waitForReady();
        const panel = await logs.openTraceById(probe.id);
        await panel.waitForFullyLoaded();
        await panel.openFeedbackScoresTab();
        await expect(panel.feedbackScoreRow(ruleName)).toBeVisible();
        expect(await panel.readFeedbackScoreValue(ruleName)).toBe(1.0);
      });
    },
  );
});
