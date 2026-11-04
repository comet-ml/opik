import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { SmeFlowPage } from '@e2e/pom/sme-flow.page';
import { expectNothingClipped } from '@e2e/pom/trace-messages-geometry';

/**
 * opik#9234 (OPIK-8650): a thread message whose content cannot fit on one line
 * wraps inside its bubble instead of overflowing past the left edge of the
 * view, and the list ends with clear space rather than flush against the last
 * message.
 *
 * Three changes, all in the shared `TraceMessages` component: `min-w-0` on the
 * user bubble so it may shrink below its content's min-content width,
 * `[overflow-wrap:anywhere]` on the two JSON views so a run of characters with
 * no break opportunity is broken, and `h-fit pb-6` on the list.
 *
 * Why nothing already guards this. `thread-logging-smoke.spec.ts` drives this
 * exact panel and asserts turn count, trace-id order and `toBeVisible()` on the
 * text — and an element that has overflowed its container satisfies every one
 * of those. The container is `overflow-y-auto`, which makes the X axis `auto`
 * too, so content past the RIGHT edge becomes a horizontal scrollbar while
 * content past the LEFT edge is simply unreachable: in a left-to-right layout
 * there is no negative scroll position to scroll to. Both are asserted below,
 * the left one being the bug a user reported.
 *
 * Asserted as GEOMETRY, not as a pixel snapshot. A screenshot would catch this
 * too, but only in `visual-tests/`, and it would also fail on a font, theme or
 * scrollbar change that has nothing to do with wrapping. Box measurements are
 * decisive and survive all three. Nothing here asserts a CSS class or a
 * computed `overflow-wrap`: the claim is that no content escapes its
 * container, which stays true however the frontend achieves it.
 *
 * Both surfaces the shared component reaches are driven. The release's
 * coverage report called the `traces` half of this PR's area mapping spurious
 * and noted that `ThreadDetailsPanel` and the SME/annotator thread view render
 * `TraceMessages` from completely separate layouts — the SME view at a much
 * narrower message column — so a rule that holds in one can fail in the other.
 */

/** `pb-6`, in CSS pixels. Asserted as a floor: more space is not a regression. */
const MIN_TRAILING_SPACE_PX = 24;

/** Wide enough that the panel is comfortable; narrow enough to be a real desktop. */
const WIDE_VIEWPORT = { width: 1600, height: 1000 };
/** Narrow enough that the message column is genuinely cramped. */
const NARROW_VIEWPORT = { width: 900, height: 900 };

test.describe('Thread messages — wrapping', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test('Wide message content wraps inside the thread panel at both a wide and a narrow viewport', { tag: ['@cap:threads.message-wrapping', '@cap:threads.open-thread-panel'] }, async ({
    wideContentThread,
    page,
  }) => {
    const logs = new LogsPage(page);

    const panel = await test.step('Open the thread panel at a wide viewport', async () => {
      await page.setViewportSize(WIDE_VIEWPORT);
      await logs.gotoThreads(wideContentThread.projectId);
      await logs.waitForThreadsReady(wideContentThread.threadId);
      const panel = await logs.openThreadById(wideContentThread.threadId);
      await panel.waitForFullyLoaded();
      await expect(panel.turns, 'one turn per seeded trace').toHaveCount(
        wideContentThread.turns.length,
      );
      return panel;
    });

    await test.step(`Nothing is clipped at ${WIDE_VIEWPORT.width}px`, async () => {
      const geometry = await panel.measureMessages();

      // The discrimination check, before any claim about boxes: every seeded
      // turn carries an object input AND an object output, so each must have
      // rendered through the JSON view the fix touched. A payload that reached
      // `MarkdownPreview` instead would wrap with the fix reverted, and
      // everything below would be a permanent false green.
      expect(
        geometry.jsonViewCount,
        'every turn renders its input and its output through the JSON view under test',
      ).toBe(wideContentThread.turns.length * 2);

      expectNothingClipped(geometry, `thread panel at ${WIDE_VIEWPORT.width}px`);
    });

    await test.step(`Nothing is clipped at ${NARROW_VIEWPORT.width}px`, async () => {
      const before = await panel.measureMessages();
      await page.setViewportSize(NARROW_VIEWPORT);
      // Wait on the layout having actually taken the new width rather than on a
      // delay: `setViewportSize` resolves before the resulting re-layout has
      // necessarily been painted, and a measurement taken in that window would
      // mix boxes from two layouts.
      await expect
        .poll(async () => (await panel.measureMessages()).clientWidth, {
          timeout: 10_000,
          message: 'the message container to re-lay out at the narrower viewport',
        })
        .toBeLessThan(before.clientWidth);

      const geometry = await panel.measureMessages();
      expect(
        geometry.jsonViewCount,
        'the same turns are still rendered through the JSON view after the resize',
      ).toBe(wideContentThread.turns.length * 2);
      expectNothingClipped(geometry, `thread panel at ${NARROW_VIEWPORT.width}px`);
    });

    await test.step('The list ends with clear space below the last message', async () => {
      const geometry = await panel.measureMessages();
      // Only meaningful while the list is taller than its container: a short
      // thread leaves space below the last message whatever the padding is, so
      // the assertion could not fail and would read as coverage forever.
      expect(
        geometry.scrollHeight,
        'the seeded thread must overflow its container for trailing space to mean anything',
      ).toBeGreaterThan(geometry.clientHeight);
      expect(
        geometry.trailingSpace,
        'the last message must not sit flush against the end of the scrolled content',
      ).toBeGreaterThanOrEqual(MIN_TRAILING_SPACE_PX);
    });
  });

  test('Wide message content wraps in the SME annotator thread view', { tag: ['@cap:threads.message-wrapping'] }, async ({
    wideContentThread,
    wideContentThreadQueue,
    page,
  }) => {
    const sme = new SmeFlowPage(page);

    await test.step('Open the SME flow on the queued thread at a wide viewport', async () => {
      await page.setViewportSize(WIDE_VIEWPORT);
      await sme.goto(wideContentThreadQueue.queueId);
      await expect(
        sme.welcomeHeading(wideContentThreadQueue.queueName),
        'the instructions page names the queue the link points at',
      ).toBeVisible({ timeout: 30_000 });
      await sme.startAnnotating();
      await sme.waitForThreadReady();
      await expect(sme.turns, 'one turn per seeded trace').toHaveCount(
        wideContentThread.turns.length,
      );
    });

    await test.step(`Nothing is clipped at ${WIDE_VIEWPORT.width}px`, async () => {
      const geometry = await sme.measureMessages();
      expect(
        geometry.jsonViewCount,
        'every turn renders its input and its output through the JSON view under test',
      ).toBe(wideContentThread.turns.length * 2);
      expectNothingClipped(geometry, `SME thread view at ${WIDE_VIEWPORT.width}px`);
    });

    await test.step(`Nothing is clipped at ${NARROW_VIEWPORT.width}px`, async () => {
      const before = await sme.measureMessages();
      await page.setViewportSize(NARROW_VIEWPORT);
      await expect
        .poll(async () => (await sme.measureMessages()).clientWidth, {
          timeout: 10_000,
          message: 'the message container to re-lay out at the narrower viewport',
        })
        .toBeLessThan(before.clientWidth);

      expectNothingClipped(
        await sme.measureMessages(),
        `SME thread view at ${NARROW_VIEWPORT.width}px`,
      );
    });
  });
});
