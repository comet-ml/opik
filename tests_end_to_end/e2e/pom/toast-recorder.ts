import { test, type Page } from '@playwright/test';

/** Window key the recorder writes into. */
const TOAST_RECORD_KEY = '__opikRecordedToasts';

/**
 * Record every toast a page raises, so a spec can assert on one that has already
 * been dismissed — and, just as importantly, on one that was never raised.
 *
 * Shared between POMs rather than reimplemented per page: `playground.page.ts`
 * established this and `datasets.page.ts` needs exactly the same guarantee, and
 * two copies of a MutationObserver installed through an init script is how the
 * two quietly stop agreeing about what counts as a toast.
 *
 * Why not a live locator. Radix dismisses a toast on its own 5-second default,
 * so a point-in-time read races the dismissal — and worse, it cannot tell "never
 * raised" from "raised and already gone". That distinction is the whole
 * assertion for a spec about a flow that must stay silent: a dataset upload
 * rejected with a 400 must NOT also raise the "is ready to use" toast, and a
 * locator that found nothing would be satisfied either way.
 */

/**
 * Install the recorder. Must be called BEFORE the page navigates — it adds an
 * init script, which runs on every document, so the observer is in place before
 * the first toast can be raised.
 *
 * It does NOT accumulate across navigations: the init script re-runs in each
 * fresh document and assigns a new array, so a `page.reload()` drops every toast
 * raised before it, and `readRecordedToasts` then describes the current document
 * only. A spec needing history to survive a reload has to hold the array
 * test-side, through an exposed binding the init script appends to.
 *
 * Scoped to the notification region: Radix also portals a visually-hidden
 * `role="status"` announcer carrying the same text outside the region, and
 * recording both would double every toast.
 */
export async function startRecordingToasts(page: Page): Promise<void> {
  return test.step('start recording toasts', async () => {
    await page.addInitScript((key: string) => {
      const recorded: Element[] = [];
      (window as unknown as Record<string, unknown>)[key] = recorded;

      const isToast = (el: Element): boolean =>
        el.matches('[role="status"]') && el.closest('[role="region"]') !== null;

      new MutationObserver((mutations) => {
        for (const mutation of mutations) {
          for (const node of Array.from(mutation.addedNodes)) {
            if (!(node instanceof Element)) continue;
            if (isToast(node)) recorded.push(node);
            // A toast can also arrive nested, when the region itself is the node
            // that was inserted.
            for (const nested of Array.from(node.querySelectorAll('[role="status"]'))) {
              if (isToast(nested)) recorded.push(nested);
            }
          }
        }
      }).observe(document, { childList: true, subtree: true });
    }, TOAST_RECORD_KEY);
  });
}

/**
 * The text of every toast raised since the recorder was installed, oldest first,
 * whether or not it is still on screen.
 *
 * Read out of the recorded elements rather than snapshotted at insertion: a
 * detached node keeps its text, and reading late also picks up content React
 * committed into the toast after appending it.
 *
 * Throws rather than returning `[]` when the recorder was never installed — an
 * empty array is what half of these assertions are looking for, so a missing
 * recorder would read as "no toast was raised" and pass.
 */
export async function readRecordedToasts(page: Page): Promise<string[]> {
  return test.step('read the recorded toasts', async () => {
    return page.evaluate((key: string) => {
      const recorded = (window as unknown as Record<string, unknown>)[key];
      if (!Array.isArray(recorded)) {
        throw new Error(
          'no toast recorder on this page — startRecordingToasts() must run before goto()',
        );
      }
      return (recorded as Element[]).map((el) => (el.textContent ?? '').trim());
    }, TOAST_RECORD_KEY);
  });
}
