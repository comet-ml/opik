import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { FeedbackScoreRef } from '@e2e/core/backend';

/**
 * The Traces table's per-score columns (OPIK-8021 / PR #7944 rewrote the cell
 * behind them).
 *
 * Nothing in the estate renders a feedback-score *cell* today: the score
 * coverage that exists asserts scores through the filter chip
 * (trace-filters.spec.ts) or the detail panel's Feedback scores tab
 * (thread-evaluation.spec.ts), neither of which reads the table's own column.
 * So the whole read path here is unguarded, and its failure mode is silent —
 * a cell that renders a neighbouring row's number, or a stale one, looks
 * exactly like a cell that is right.
 *
 * Every assertion compares the rendered text against what the API reports for
 * that same row, rather than against a literal, so a value that is wrong in
 * both places still fails. Three columns of staggered scores plus an unscored
 * row make every column discriminating — see the scoredTraces fixture.
 */

/** How the table formats a score for display (`formatScoreDisplay`, 2dp). */
const displayScore = (value: number): string => String(Number(value.toFixed(2)));

/** A score set as a comparable shape, sorted so the comparison is order-free. */
const comparable = (scores: Array<Pick<FeedbackScoreRef, 'name' | 'value' | 'reason'>>) =>
  scores
    .map((s) => ({ name: s.name, value: s.value, reason: s.reason }))
    .sort((a, b) => a.name.localeCompare(b.name));

test.describe('Trace feedback-score columns', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'Each score column renders the value the API holds for that row, and an unscored row renders the empty state',
    { tag: ['@cap:traces.configure-columns'] },
    async ({ scoredTraces, project, backendClient, page }) => {
      const logs = new LogsPage(page);
      const { all, sharedScoreName, reasonlessScoreName, unscored } = scoredTraces;

      await test.step('Open Logs and confirm every seeded trace is listed', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(all.length);
      });

      for (const seeded of all) {
        await test.step(`"${seeded.name}" renders exactly the scores the API reports`, async () => {
          const trace = await backendClient.getTrace(seeded.id);
          expect(trace, `trace ${seeded.name} is readable over the API`).not.toBeNull();

          // The whole score set, not just the ones about to be rendered: a
          // trace that also carried a score it should not have is the state
          // that would make a per-column check pass while the row is wrong.
          expect(
            comparable(trace!.feedbackScores),
            'the API holds exactly the scores the fixture seeded',
          ).toEqual(comparable(seeded.scores));

          for (const scoreName of [sharedScoreName, reasonlessScoreName]) {
            const apiScore = trace!.feedbackScores.find((s) => s.name === scoreName);
            const cell = logs.feedbackScoreCell(seeded.id, scoreName);
            // Exactly one cell: an ambiguous lookup must fail loudly rather
            // than silently assert against whichever element came first.
            await expect(cell).toHaveCount(1);

            if (apiScore) {
              await expect(
                cell,
                `${seeded.name} / ${scoreName} renders the API's own value`,
              ).toHaveText(displayScore(apiScore.value));
            } else {
              await expect(
                cell,
                `${seeded.name} carries no ${scoreName} score, so its cell is empty`,
              ).toHaveText('-');
            }
          }
        });
      }

      await test.step('The unscored trace renders the empty state in every score column', async () => {
        // Restated as its own step because it is the case a cell that fell back
        // to the column's first value would pass: gamma has no scores at all,
        // so both of its cells must read as empty, not as alpha's numbers.
        const trace = await backendClient.getTrace(unscored.id);
        expect(trace!.feedbackScores, 'the unscored trace really holds no scores').toEqual([]);
        await expect(logs.feedbackScoreCell(unscored.id, sharedScoreName)).toHaveText('-');
        await expect(logs.feedbackScoreCell(unscored.id, reasonlessScoreName)).toHaveText('-');
      });
    },
  );

  test(
    'A score seeded with a reason exposes it in a tooltip, and one seeded without exposes nothing to open',
    { tag: ['@cap:traces.configure-columns'] },
    async ({ scoredTraces, project, backendClient, page }) => {
      const logs = new LogsPage(page);
      const { all, sharedScoreName, reasonlessScoreName } = scoredTraces;
      const alpha = all[0];

      const { withReason, withoutReason } = await test.step(
        'Confirm over the API that one score carries a reason and the other does not',
        async () => {
          // Both halves of the discriminator, checked before the browser opens:
          // if the fixture had failed to attach the reason, the tooltip
          // assertion below would be testing an empty page against itself.
          const trace = await backendClient.getTrace(alpha.id);
          const withReason = trace!.feedbackScores.find((s) => s.name === sharedScoreName);
          const withoutReason = trace!.feedbackScores.find((s) => s.name === reasonlessScoreName);
          expect(withReason?.reason, `${sharedScoreName} was seeded with a reason`).toBeTruthy();
          expect(
            withoutReason,
            `${reasonlessScoreName} is present on the trace`,
          ).toBeDefined();
          expect(
            withoutReason!.reason,
            `${reasonlessScoreName} was seeded without a reason`,
          ).toBeNull();
          return { withReason: withReason!, withoutReason: withoutReason! };
        },
      );

      await test.step('Open Logs', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await expect(logs.traceRow(alpha.id)).toHaveCount(1);
      });

      await test.step(`The "${sharedScoreName}" cell opens a tooltip carrying its reason`, async () => {
        const cell = logs.feedbackScoreCell(alpha.id, sharedScoreName);
        await expect(logs.scoreReasonIndicator(cell)).toHaveCount(1);
        await logs.openScoreReason(cell);
        // The tooltip also renders the author and a relative timestamp; only
        // the reason is deterministic, so only the reason is asserted.
        await expect(logs.scoreReasonTooltip).toContainText(withReason.reason!);
      });

      await test.step(`The "${reasonlessScoreName}" cell offers no reason to open`, async () => {
        const cell = logs.feedbackScoreCell(alpha.id, reasonlessScoreName);
        // Gated on the cell having rendered its value first — otherwise "no
        // indicator" would also pass on a cell that had not painted yet.
        await expect(cell).toHaveText(displayScore(withoutReason.value));
        await expect(logs.scoreReasonIndicator(cell)).toHaveCount(0);
      });
    },
  );

  test(
    'A span-level score renders in its own "(span)" column and never in the trace-level column of the same name',
    { tag: ['@cap:traces.configure-columns'] },
    async ({ scoredTraces, project, backendClient, page }) => {
      const logs = new LogsPage(page);
      const { all, scoredSpan, unscored } = scoredTraces;
      const alpha = all[0];

      const apiSpanScore = await test.step('Read the span score back over the API', async () => {
        const spans = await backendClient.listTraceSpans({
          projectId: project.id,
          traceId: alpha.id,
        });
        expect(spans.map((s) => s.id), 'the trace carries exactly its one span').toEqual([
          scoredSpan.id,
        ]);
        expect(
          comparable(spans[0].feedbackScores),
          'the span holds exactly the one span-level score',
        ).toEqual(comparable([scoredSpan.score]));

        // The other half of the split: the score is on the SPAN, so the trace
        // itself must not report it. Without this, a backend that folded span
        // scores into the trace's own set would still satisfy the cell
        // assertion below and the column separation would be untested.
        const trace = await backendClient.getTrace(alpha.id);
        expect(
          trace!.feedbackScores.map((s) => s.name),
          'a span score is not a trace score',
        ).not.toContain(scoredSpan.score.name);

        return spans[0].feedbackScores[0];
      });

      await test.step('Open Logs', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(all.length);
      });

      await test.step('The trace row shows the span score in the "(span)" column only', async () => {
        const spanColumnCell = logs.spanScoreCell(alpha.id, apiSpanScore.name);
        await expect(spanColumnCell).toHaveCount(1);
        await expect(spanColumnCell).toHaveText(displayScore(apiSpanScore.value));

        // No trace-level column of that name exists to render it into. Safe to
        // assert as absence: the same row's "(span)" cell has just been read,
        // so the table is demonstrably rendered.
        await expect(
          logs.feedbackScoreCell(alpha.id, apiSpanScore.name),
          'the span score must not also appear as a trace-level column',
        ).toHaveCount(0);
      });

      await test.step('A trace with no spans renders the empty state in the "(span)" column', async () => {
        await expect(logs.spanScoreCell(unscored.id, apiSpanScore.name)).toHaveText('-');
      });
    },
  );

  test(
    'The Spans tab shows the span score on the span row it belongs to',
    { tag: ['@cap:traces.configure-columns'] },
    async ({ scoredTraces, project, backendClient, page }) => {
      const logs = new LogsPage(page);
      const { scoredSpan } = scoredTraces;

      const apiSpanScore = await test.step('Read the span score back over the API', async () => {
        const spans = await backendClient.listTraceSpans({
          projectId: project.id,
          traceId: scoredTraces.all[0].id,
        });
        const span = spans.find((s) => s.id === scoredSpan.id);
        expect(span, 'the scored span is readable over the API').toBeDefined();
        expect(span!.feedbackScores).toHaveLength(1);
        return span!.feedbackScores[0];
      });

      await test.step('Open the Spans tab and verify the span row', async () => {
        await logs.gotoSpans(project.id);
        await logs.waitForSpansReady(scoredSpan.id);
        // On this tab the score is the row's OWN score, so it renders through
        // the plain `feedback_scores` column — not the "(span)" column the
        // Traces tab needs to reach across from the parent trace.
        const cell = logs.feedbackScoreCell(scoredSpan.id, apiSpanScore.name);
        await expect(cell).toHaveCount(1);
        await expect(cell).toHaveText(displayScore(apiSpanScore.value));
      });
    },
  );
});
