import { test, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * Wait out the Ollie sidebar's load after a navigation. Its iframe focuses its own
 * chat box when it mounts, a few seconds after the page loads, and the host treats
 * focus moving into that iframe as a click outside (`AssistantSidebar.tsx`), so any
 * select or popover open at that moment closes under the spec.
 *
 * Bounded rather than required: a collapsed sidebar never takes focus, and a spec
 * that is not about Ollie must not fail for it.
 */
export async function waitForAssistantSidebarLoaded(page: Page): Promise<void> {
  if (!loadEnvConfig().features.ollie) return;
  const shown = await page
    .getByTestId('ollie-assistant-iframe')
    .waitFor({ state: 'visible', timeout: 15_000 })
    .then(
      () => true,
      () => false,
    );
  if (!shown) return;
  const tookFocus = await page
    .waitForFunction(
      () => document.activeElement?.getAttribute('data-testid') === 'ollie-assistant-iframe',
      undefined,
      { timeout: 30_000 },
    )
    .then(
      () => true,
      () => false,
    );
  if (!tookFocus) {
    test.info().annotations.push({
      type: 'warning',
      description: 'the Ollie sidebar did not take focus within 30s; continuing without it',
    });
  }
}
