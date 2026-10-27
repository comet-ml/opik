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
 * What a reader sees when an LLM-judge metric's `reason` carries a newline.
 *
 * opik#8414 changed the shape of that field. `Hallucination` and `SycEval`
 * declare `reason: List[str]`, and until this release the SDK uploaded
 * `str(value)` — a Python list literal, brackets and all. `reason_to_text` now
 * joins the items with `\n`, and labels an empty list `No reason provided`.
 *
 * The PR carries its own unit tests for the join itself
 * (`test_parsing_helpers.py::TestReasonToText` and the two judge parsers), and
 * those are the coverage for the parser. This spec is the OTHER half, which
 * nothing covered: the string's new shape has to survive the upload and then be
 * rendered. Nothing in the estate asserted a metric-supplied reason anywhere —
 * the only `reason` the POMs touched was the human annotation textarea, which is
 * a different field with a different author.
 *
 * So the seed is the exact output `reason_to_text` produces, written over the
 * API, and the assertions are about what happens to it afterwards. No judge and
 * no provider key is in the loop; the reason is a constant, so the test is
 * deterministic.
 *
 * `innerText` is doing the real work here. The Reason cell truncates to one line
 * by CSS and the tooltip is where the whole verdict is meant to be legible, so
 * the tooltip is what is read — and read as RENDERED text, because a
 * `textContent` read would report the seeded `\n` back even from a build that
 * collapsed it on screen, and pass having verified nothing.
 */

test.describe('Feedback score reason rendering', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'a newline-joined judge reason is stored and rendered as multi-line prose',
    { tag: ['@cap:traces.score-reason-render'] },
    async ({ feedbackScoreReasons, project, backendClient, page }) => {
      await test.step('Both reasons reached storage exactly as seeded', async () => {
        const trace = await backendClient.getTrace(feedbackScoreReasons.traceId);
        // Asserted present before anything is read off it, so a missing trace
        // fails here rather than making every comparison below optional.
        expect(trace, `trace ${feedbackScoreReasons.traceId} is readable`).not.toBeNull();

        // The whole collection, not just a lookup of the two names: a build
        // that duplicated a score per author, or that left a stray row behind,
        // would satisfy a `find()`-and-compare while showing the reader
        // something else entirely.
        const byName = new Map(trace!.feedbackScores.map((s) => [s.name, s]));
        expect([...byName.keys()].sort(), 'scores on the trace').toEqual(
          [feedbackScoreReasons.multiline.scoreName, feedbackScoreReasons.sentinel.scoreName].sort(),
        );

        expect(
          byName.get(feedbackScoreReasons.multiline.scoreName)!.reason,
          'the judge verdict is stored with its newline, not re-encoded',
        ).toBe(MULTILINE_JUDGE_REASON);
        expect(
          byName.get(feedbackScoreReasons.sentinel.scoreName)!.reason,
          'the empty-list sentinel is stored as the literal text reason_to_text produces',
        ).toBe(EMPTY_LIST_JUDGE_REASON);
      });

      const panel = await test.step('Open the trace and its Feedback scores tab', async () => {
        const logs = new LogsPage(page);
        await logs.goto(project.id);
        const panel = await logs.openTraceById(feedbackScoreReasons.traceId);
        await panel.waitForFullyLoaded();
        await panel.openFeedbackScoresTab();
        return panel;
      });

      await test.step('The tab shows the trace\'s two scores and nothing else', async () => {
        // One table before any row is counted: the tab renders a second, "Span
        // scores" table whenever the trace's spans carry scores, and neither is
        // labelled in the DOM, so the row locators would otherwise range over
        // both and the count below would be about the wrong thing.
        await expect(panel.feedbackScoreTables(), 'only the Trace scores table').toHaveCount(1);
        await expect(panel.feedbackScoreRows(), 'one row per seeded score').toHaveCount(2);
        for (const seed of [feedbackScoreReasons.multiline, feedbackScoreReasons.sentinel]) {
          await expect(
            panel.feedbackScoreRowByName(seed.scoreName),
            `exactly one row for ${seed.scoreName}`,
          ).toHaveCount(1);
        }
      });

      await test.step('The verdict renders on one line per list item', async () => {
        const text = await panel.feedbackScoreReasonTooltipText(
          feedbackScoreReasons.multiline.scoreName,
        );

        // The tail of the tooltip, one entry per rendered line box. Taken from
        // the end rather than by skipping a header of assumed length: the
        // `author (value) <time ago>` row above it is three block-level boxes
        // whose line count is a layout detail.
        const lines = text.split('\n');
        expect(
          lines.slice(-JUDGE_REASON_ITEMS.length),
          'each judge sentence occupies its own line',
        ).toEqual([...JUDGE_REASON_ITEMS]);

        // The failure this test exists to catch, stated directly: with
        // `white-space: normal` the newline collapses and the two sentences
        // render as one run-on paragraph.
        expect(
          text,
          'the newline must not collapse into a space',
        ).not.toContain(COLLAPSED_JUDGE_REASON);

        // And the failure it REPLACED: before 8414 the field arrived as
        // `str(["...", "..."])`, so the reader got a Python list literal.
        expect(text, 'no list-literal debris around the verdict').not.toMatch(/[[\]]/);
      });

      await test.step('The empty-list sentinel renders as ordinary prose on one line', async () => {
        // The control. Without a reason that must stay on ONE line, "the
        // verdict rendered on two lines" is equally well explained by a tooltip
        // that breaks every reason it is handed — and it is also the second
        // string reason_to_text can produce, so it is worth asserting in its
        // own right: `isValidReason` filters placeholder reasons out of this
        // cell entirely, and this one must survive that filter.
        const text = await panel.feedbackScoreReasonTooltipText(
          feedbackScoreReasons.sentinel.scoreName,
        );
        expect(
          text.split('\n').slice(-1),
          'the sentinel is rendered, and on a single line',
        ).toEqual([EMPTY_LIST_JUDGE_REASON]);
      });
    },
  );
});
