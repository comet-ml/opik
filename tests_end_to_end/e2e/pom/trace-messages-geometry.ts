import { test, expect, type Locator } from '@playwright/test';

/**
 * Geometry of the shared `TraceMessages` list — the component the thread detail
 * panel, the annotator thread view and the SME flow all render.
 *
 * Why geometry and not a screenshot: what opik#9234 fixed is that wide message
 * content escaped its container and was clipped at the left edge, and an
 * element that has overflowed its box is still `toBeVisible()` to Playwright,
 * still carries its text, and still sits in the right order — so every
 * assertion the area already makes passes over the broken layout. A pixel
 * snapshot would catch it, but only in `visual-tests/`, where it would also
 * fail on a font, a theme or a scrollbar-width change. Measuring boxes is the
 * one reading that is both decisive and deterministic.
 */
export interface TraceMessagesGeometry {
  /** How many `[data-trace-message-id]` turns were measured. */
  turnCount: number;
  /** The scrolling container's own box. Equal widths means nothing overflows. */
  scrollWidth: number;
  clientWidth: number;
  scrollHeight: number;
  clientHeight: number;
  /** Horizontal scroll offset at the moment of measuring; must be 0. */
  scrollLeft: number;
  /**
   * Descriptions of descendants whose box starts left of the container's client
   * box. In a left-to-right layout this region is NOT reachable by scrolling,
   * so anything here is content a user can never read — the exact failure
   * opik#9234 is about.
   */
  clippedLeft: string[];
  /** Descendants whose box ends right of the container's client box. */
  overflowingRight: string[];
  /**
   * Scrollable space between the bottom of the last turn and the bottom of the
   * scrolled content, in CSS pixels — what `pb-6` on the list buys.
   */
  trailingSpace: number;
  /**
   * How many turn payloads rendered through `react18-json-view`.
   *
   * The discrimination check, and not decoration. `TraceMessage` renders an
   * object through the JSON view that opik#9234 put `[overflow-wrap:anywhere]`
   * on, and anything else through `MarkdownPreview`, which has always broken
   * long words — so a seed whose payloads were collapsed to strings by
   * `prettifyMessage` would wrap perfectly with the fix reverted, and the
   * geometry assertions below would be a permanent false green.
   *
   * `.json-view` is react18-json-view's own root class, applied beside whatever
   * `className` the caller passes. Matched on it rather than on the app's
   * `comet-code`, which is a shared type utility used all over the app and says
   * nothing about which branch rendered.
   */
  jsonViewCount: number;
}

/**
 * Measure the message list that `root` contains.
 *
 * The scrolling container is resolved by walking up from the first turn to the
 * nearest ancestor whose computed `overflow-y` scrolls, the same way
 * `CompareExperimentsPage.scrollGridToEnd` resolves its own: the element has no
 * role, label or testid, and naming it by the property under test is stabler
 * than a structural path that breaks the next time the layout gains a wrapper.
 * The e2e suite ships into a repo whose frontend this change may not touch, so
 * a `data-testid` on `TraceMessages` is the better handle and should replace
 * this when one can be added.
 *
 * Everything is read inside one `evaluate`, in one frame: a measurement split
 * across several round-trips can straddle a re-layout and report boxes that
 * never coexisted.
 */
export async function measureTraceMessages(turns: Locator): Promise<TraceMessagesGeometry> {
  return test.step('Measure the thread message list geometry', async () => {
    await expect(turns.first(), 'at least one turn to measure').toBeVisible();

    return turns.first().evaluate((firstTurn) => {
      let container: HTMLElement | null = null;
      for (let node = firstTurn.parentElement; node; node = node.parentElement) {
        const overflowY = window.getComputedStyle(node).overflowY;
        if (overflowY === 'auto' || overflowY === 'scroll') {
          container = node;
          break;
        }
      }
      if (container === null) {
        throw new Error(
          'measureTraceMessages: no scrolling ancestor above the first turn. TraceMessages ' +
            'renders its list inside an `overflow-y-auto` div; if that is gone the clipping ' +
            'this measures can no longer happen there, and the assertion has moved.',
        );
      }

      const containerRect = container.getBoundingClientRect();
      // The client box — inside the border, padding included. Content that
      // reaches into the padding is still fully painted; content outside it is
      // what gets clipped.
      const clientLeft = containerRect.left + container.clientLeft;
      const clientRight = clientLeft + container.clientWidth;
      // Sub-pixel layout rounds, and a 1px report is not a clipped message.
      const TOLERANCE = 1;

      const describe = (el: Element): string => {
        const classes = (el.getAttribute('class') ?? '').slice(0, 60);
        const text = (el.textContent ?? '').trim().slice(0, 40);
        const rect = el.getBoundingClientRect();
        return (
          `<${el.tagName.toLowerCase()} class="${classes}"> ` +
          `[${Math.round(rect.left)}..${Math.round(rect.right)}] "${text}"`
        );
      };

      const clippedLeft: string[] = [];
      const overflowingRight: string[] = [];
      const turnElements = Array.from(
        container.querySelectorAll('[data-trace-message-id]'),
      ) as HTMLElement[];

      for (const turn of turnElements) {
        const candidates: Element[] = [turn, ...Array.from(turn.querySelectorAll('*'))];
        for (const el of candidates) {
          const rect = el.getBoundingClientRect();
          // Unrendered elements report a zero box at the origin, which would
          // read as "far left of the container" and fail for no reason.
          if (rect.width === 0 || rect.height === 0) continue;
          if (rect.left < clientLeft - TOLERANCE) clippedLeft.push(describe(el));
          if (rect.right > clientRight + TOLERANCE) overflowingRight.push(describe(el));
        }
      }

      const lastTurn = turnElements[turnElements.length - 1];
      const lastRect = lastTurn.getBoundingClientRect();
      const lastBottomInContent =
        lastRect.bottom - containerRect.top - container.clientTop + container.scrollTop;

      return {
        turnCount: turnElements.length,
        scrollWidth: container.scrollWidth,
        clientWidth: container.clientWidth,
        scrollHeight: container.scrollHeight,
        clientHeight: container.clientHeight,
        scrollLeft: container.scrollLeft,
        // Capped so one pathological turn cannot produce a megabyte of failure
        // text; the count is what matters and the first few name the culprit.
        clippedLeft: clippedLeft.slice(0, 5),
        overflowingRight: overflowingRight.slice(0, 5),
        trailingSpace: container.scrollHeight - lastBottomInContent,
        jsonViewCount: container.querySelectorAll('[data-trace-message-id] .json-view').length,
      };
    });
  });
}

/**
 * Assert a measurement shows nothing escaping the message container.
 *
 * Takes the whole measurement rather than a boolean so a failure names the
 * offending element and its box, which is the difference between "the layout
 * regressed" and "the user bubble on turn 2 starts 180px left of the panel".
 */
export function expectNothingClipped(
  geometry: TraceMessagesGeometry,
  where: string,
): void {
  expect(
    geometry.scrollLeft,
    `${where}: the message list must be measured unscrolled, or every box is offset`,
  ).toBe(0);
  expect(
    geometry.clippedLeft,
    `${where}: no part of a message may start left of the message container — that region ` +
      'cannot be scrolled to, so the content is unreadable',
  ).toEqual([]);
  expect(
    geometry.overflowingRight,
    `${where}: no part of a message may extend right of the message container`,
  ).toEqual([]);
  expect(
    geometry.scrollWidth,
    `${where}: the message container must not scroll horizontally (scrollWidth ` +
      `${geometry.scrollWidth} vs clientWidth ${geometry.clientWidth})`,
  ).toBe(geometry.clientWidth);
}
