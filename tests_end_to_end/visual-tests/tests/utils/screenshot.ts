import { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';

const IS_COMPARISON_RUN = process.env.SKIP_TEARDOWN !== '1';
const COMPARISON_DIR = path.join(__dirname, '../../screenshots/comparison');

// Applied to every screenshot: page chrome that renders on virtually every page and
// can legitimately differ between the two environments being compared (workspace/project
// name in the breadcrumb, timestamps rendered outside a table).
function baseMasks(page: Page) {
  return [
    page.locator('time'),
    page.locator('[data-testid="timestamp"]'),
    page.locator('[data-testid="date"]'),
    // mask breadcrumb — shows dynamic project/dataset names on sub-pages
    page.locator('nav[aria-label="breadcrumb"]'),
  ];
}

// Table-row masks: only relevant to screenshots of populated data tables (traces,
// threads, datasets, experiments, ...). Empty-state screenshots have no rows and the
// trace sidebar has no table at all, so don't apply these everywhere — pass this in
// via screenshot()'s extraMasks only for tests that actually render a populated table.
export function tableMasks(page: Page) {
  return [
    page.locator('td').filter({ hasText: /\d+ (second|minute|hour|day)s? ago/ }),
    page.locator('td').filter({ hasText: /\d{4}-\d{2}-\d{2}/ }),
    page.locator('td').filter({ hasText: /\d+ mins? ago/ }),
    // mask absolute date cells — format "D MMM YYYY, h:mm A" used by TimeCell (e.g., "15 May 2026, 3:26 PM")
    page.locator('td').filter({ hasText: /\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4},/ }),
    // mask UUID columns
    page.locator('td').filter({ hasText: /[0-9a-f]{8}-[0-9a-f]{4}/ }),
    // mask pagination "Showing X-Y of Z" — counts differ between environments
    page.locator('span').filter({ hasText: /^Showing \d/ }),
    // mask duration cells (e.g. "1.23s") — timing varies between environments
    page.locator('td').filter({ hasText: /^\d+\.?\d*s$/ }),
  ];
}

export async function hideDemoBanner(page: Page) {
  await page.addStyleTag({
    content: '.z-10.h-8.bg-primary { display: none !important; }',
  });
}

// extraMasks: additional masks scoped to the caller only — appended to the shared
// baseMasks() list for this screenshot, without changing masking for any other test.
export async function screenshot(page: Page, name: string, extraMasks: Locator[] = []) {
  await hideDemoBanner(page);
  const mask = [...baseMasks(page), ...extraMasks];
  if (IS_COMPARISON_RUN) {
    fs.mkdirSync(COMPARISON_DIR, { recursive: true });
    await page.screenshot({
      path: path.join(COMPARISON_DIR, `${name}.png`),
      mask,
      animations: 'disabled',
    });
  }
  await expect(page).toHaveScreenshot(`${name}.png`, { mask, animations: 'disabled' });
}
