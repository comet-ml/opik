import { test, type Page } from '@playwright/test';

/**
 * Read the system clipboard the page just wrote to.
 *
 * The app copies with `clipboard-copy`, which prefers `navigator.clipboard`,
 * so the only way to assert the value a user would paste is to read it back
 * through the same API. Two requirements the caller owns:
 *
 * - The context must hold `clipboard-read` (and `clipboard-write`, which
 *   Chromium does not grant to a background read). Declare it on the spec:
 *   `test.use({ permissions: ['clipboard-read', 'clipboard-write'] })`.
 * - `navigator.clipboard.readText()` rejects unless the document is focused, so
 *   read on the page that did the copying. A second page takes focus with it,
 *   so either read before opening one, or `await page.bringToFront()` first —
 *   do not rely on focus coming back when the other page closes.
 */
export async function readClipboard(page: Page): Promise<string> {
  return test.step('Read the clipboard', async () =>
    page.evaluate(() => navigator.clipboard.readText()));
}
