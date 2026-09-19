/**
 * Assert what the page just put on the system clipboard.
 *
 * The app copies with `clipboard-copy`, which prefers `navigator.clipboard`, so
 * the only way to assert the value a user would paste is to read it back
 * through the same API. Two requirements the caller owns:
 *
 * - The context must hold `clipboard-read` (and `clipboard-write`, which
 *   Chromium does not grant to a background read). Declare it on the spec:
 *   `test.use({ permissions: ['clipboard-read', 'clipboard-write'] })`.
 * - `navigator.clipboard.readText()` rejects unless the document is focused, so
 *   read on the page that did the copying. A second page takes focus with it,
 *   so either read before opening one, or `await page.bringToFront()` first —
 *   do not rely on focus coming back when the other page closes.
 *
 * Both helpers poll rather than read once, and that is not defensiveness:
 * `CopyEntityActions` calls `copy(...)` and sets its "Copied" state in the same
 * tick without awaiting the write, so the check icon is not evidence the value
 * has landed. A single read can still observe what the PREVIOUS copy left
 * there, which surfaces as a wrong-value mismatch rather than as the timing
 * problem it actually is.
 */
import { expect, test, type Page } from '@playwright/test';

/** How long a clipboard write is given to land before the read is a failure. */
const CLIPBOARD_SETTLE_TIMEOUT = 10_000;
const CLIPBOARD_POLL_INTERVALS = [100, 250, 500];

const readText = (page: Page) =>
  page.evaluate(() => navigator.clipboard.readText());

/**
 * Assert the clipboard carries exactly `expected`, waiting for the unawaited
 * write to land.
 *
 * Bounded rather than retried forever: a value that never arrives is a real
 * failure, and `message` is what tells the 3am reader which copy it was.
 */
export async function expectClipboard(
  page: Page,
  expected: string,
  message: string,
): Promise<void> {
  return test.step(`Wait for the clipboard to carry "${expected}"`, async () => {
    await expect
      .poll(() => readText(page), {
        message,
        timeout: CLIPBOARD_SETTLE_TIMEOUT,
        intervals: CLIPBOARD_POLL_INTERVALS,
      })
      .toBe(expected);
  });
}

/**
 * Wait for the clipboard to match `pattern`, then return what it holds.
 *
 * For the copies whose value is not known up front — a link the app builds from
 * the current URL. `pattern` is also what distinguishes the new value from
 * whatever the last copy left behind, so make it discriminating:
 * `/^https?:\/\//` tells a copied link apart from a copied id, `/./` tells
 * nothing apart from anything.
 */
export async function readClipboardMatching(
  page: Page,
  pattern: RegExp,
  message: string,
): Promise<string> {
  return test.step(`Wait for the clipboard to match ${pattern}`, async () => {
    await expect
      .poll(() => readText(page), {
        message,
        timeout: CLIPBOARD_SETTLE_TIMEOUT,
        intervals: CLIPBOARD_POLL_INTERVALS,
      })
      .toMatch(pattern);
    return readText(page);
  });
}
