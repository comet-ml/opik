import {
  test,
  expect,
  JUDGE_REASON_ITEMS,
  MULTILINE_JUDGE_REASON,
  COLLAPSED_JUDGE_REASON,
  EMPTY_LIST_JUDGE_REASON,
} from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A judge's multi-line reason, in the Logs TABLE, at Detailed row height
 * (opik#8550).
 *
 * `FeedbackScoreCell` renders the reason two completely different ways. At
 * Compact or Medium height it goes through `FeedbackScoreReasonTooltip`, which
 * has carried `whitespace-pre-line` all along. At Detailed height it is written
 * inline into a `<span>` that had no white-space utility at all, so the computed
 * `white-space: normal` collapsed every newline and a two-sentence verdict ran
 * together as one line of prose. #8550 adds the one class to that span.
 *
 * Why this is a spec of its own rather than a case inside
 * feedback-score-reason-render.spec.ts, which already tags this capability: that
 * spec reads the TOOLTIP in the trace panel — the branch that was never broken —
 * and nothing in the estate has ever changed row height, so the inline branch
 * #8550 fixed is reachable from no existing test. Same capability, the other half
 * of it.
 *
 * Both heights are driven in ONE test on purpose. Compact is not a second
 * observation, it is the control: "the verdict rendered on two lines at Detailed"
 * is equally well explained by a table that breaks every reason it is handed, and
 * the two measurements only mean something as a pair. The `EMPTY_LIST_JUDGE_REASON`
 * score is the control on the other axis — a reason that must stay on one line at
 * the SAME height, so a build that started inserting breaks everywhere fails too.
 *
 * Deterministic: the reasons are seeded constants, so no judge and no provider
 * key is in the loop.
 */
test.describe(
  'Feedback score reason rendering — row height',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'the Logs table keeps a judge reason multi-line at Detailed height and behind a tooltip at Compact',
      { tag: ['@cap:traces.score-reason-render'] },
      async ({ feedbackScoreReasons, project, backendClient, page }) => {
        const { multiline, sentinel, traceId } = feedbackScoreReasons;
        const logs = new LogsPage(page);

        await test.step('The newline reached storage, so there is something to render', async () => {
          // The discriminating precondition, asserted before the browser opens.
          // A backend that trimmed or re-encoded the reason would leave the table
          // nothing to break a line on, and the rendering assertion below would
          // then fail looking exactly like a CSS regression.
          const trace = await backendClient.getTrace(traceId);
          expect(trace, `trace ${traceId} is readable`).not.toBeNull();
          const byName = new Map(trace!.feedbackScores.map((s) => [s.name, s]));
          expect([...byName.keys()].sort(), 'scores on the trace').toEqual(
            [multiline.scoreName, sentinel.scoreName].sort(),
          );
          expect(
            byName.get(multiline.scoreName)!.reason,
            'the judge verdict is stored with its newline',
          ).toBe(MULTILINE_JUDGE_REASON);
        });

        const compact = await test.step('Compact: the reason is not inline, it is behind the tooltip trigger', async () => {
          // `height` is stated in the URL rather than left to the profile. The
          // setting is backed by localStorage as well as the query param, and
          // `useQueryParamAndLocalStorageState` resolves `queryValue ??
          // localStorageValue` — so an explicit param wins, and a spec that
          // omitted it would assert against whatever height the storage state
          // last carried.
          await logs.goto(project.id, { rowHeight: 'small' });
          await logs.waitForReady();
          await expect(logs.traceRow(traceId), 'the seeded trace is listed').toHaveCount(1);

          const cell = await logs.readFeedbackScoreCell(traceId, multiline.scoreName);

          expect(
            cell.text,
            'at Compact height the verdict is not written into the cell',
          ).not.toContain(JUDGE_REASON_ITEMS[0]);
          await expect(
            logs.feedbackScoreReasonTooltipTrigger(traceId, multiline.scoreName),
            'the Compact cell offers the reason behind its tooltip trigger',
          ).toBeVisible();

          return cell;
        });

        const detailed = await test.step('Detailed: the verdict renders inline, one line per judge sentence', async () => {
          await logs.goto(project.id, { rowHeight: 'large' });
          await logs.waitForReady();
          await expect(logs.traceRow(traceId), 'the seeded trace is listed').toHaveCount(1);

          const cell = await logs.readFeedbackScoreCell(traceId, multiline.scoreName);

          // The tail of the rendered text, one entry per line box. Taken from the
          // end rather than by skipping a header of assumed length: the score
          // value renders above the reason and its box count is a layout detail.
          expect(
            cell.text.split('\n').slice(-JUDGE_REASON_ITEMS.length),
            'each judge sentence occupies its own line in the cell',
          ).toEqual([...JUDGE_REASON_ITEMS]);

          // The same claim stated as the whole string, and then as the failure it
          // exists to catch: with `white-space: normal` the newline collapses and
          // the two sentences read as one run-on line.
          expect(cell.text, 'the verdict is rendered whole').toContain(MULTILINE_JUDGE_REASON);
          expect(
            cell.text,
            'the newline must not collapse into a space',
          ).not.toContain(COLLAPSED_JUDGE_REASON);

          // And the inline branch really is the one on screen — otherwise the
          // assertions above would be describing a tooltip that happens to be
          // open.
          await expect(
            logs.feedbackScoreReasonTooltipTrigger(traceId, multiline.scoreName),
            'the Detailed cell has no tooltip trigger, it renders the reason itself',
          ).toHaveCount(0);

          return cell;
        });

        await test.step('The Detailed cell is materially taller than the Compact one', async () => {
          // The geometric half of the same fact, and what makes "the reason is
          // inline" more than a text assertion: a cell that had kept the Compact
          // single-line box could not be showing two lines whatever its text said.
          expect(
            detailed.height,
            `Detailed cell height (Compact was ${compact.height}px)`,
          ).toBeGreaterThan(compact.height * 2);
        });

        await test.step('The one-line control stays on one line at the same height', async () => {
          // Still at Detailed. Without this, "the verdict broke into two lines"
          // is equally well explained by a build that breaks every reason it
          // renders — and the sentinel is the second string `reason_to_text` can
          // produce, so it is worth asserting in its own right.
          const cell = await logs.readFeedbackScoreCell(traceId, sentinel.scoreName);
          expect(
            cell.text.split('\n').slice(-1),
            'the sentinel is rendered inline, and on a single line',
          ).toEqual([EMPTY_LIST_JUDGE_REASON]);
        });
      },
    );
  },
);
