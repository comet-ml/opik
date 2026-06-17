import { Page } from '@playwright/test';
import { expect } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';

const IS_COMPARISON_RUN = process.env.SKIP_TEARDOWN !== '1';
const COMPARISON_DIR = path.join(__dirname, '../../screenshots/comparison');

export function dynamicMasks(page: Page) {
  return [
    page.locator('time'),
    page.locator('[data-testid="timestamp"]'),
    page.locator('[data-testid="date"]'),
    page.locator('td').filter({ hasText: /\d+ (second|minute|hour|day)s? ago/ }),
    page.locator('td').filter({ hasText: /\d{4}-\d{2}-\d{2}/ }),
    page.locator('td').filter({ hasText: /\d+ mins? ago/ }),
    // mask absolute date cells — format "D MMM YYYY, h:mm A" used by TimeCell (e.g., "15 May 2026, 3:26 PM")
    page.locator('td').filter({ hasText: /\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4},/ }),
    // mask UUID columns
    page.locator('td').filter({ hasText: /[0-9a-f]{8}-[0-9a-f]{4}/ }),
    // mask breadcrumb — shows dynamic project/dataset names on sub-pages
    page.locator('nav[aria-label="breadcrumb"]'),
    // mask pagination "Showing X-Y of Z" — counts differ between environments
    page.locator('span').filter({ hasText: /^Showing \d/ }),
    // mask duration cells (e.g. "1.23s") — timing varies between environments
    page.locator('td').filter({ hasText: /^\d+\.?\d*s$/ }),
  ];
}

export const screenshotOpts = (page: Page) => ({
  mask: dynamicMasks(page),
  animations: 'disabled' as const,
});

export async function hideDemoBanner(page: Page) {
  await page.addStyleTag({
    content: '.z-10.h-8.bg-primary { display: none !important; }',
  });
}

export async function screenshot(page: Page, name: string) {
  await hideDemoBanner(page);
  if (IS_COMPARISON_RUN) {
    fs.mkdirSync(COMPARISON_DIR, { recursive: true });
    await page.screenshot({
      path: path.join(COMPARISON_DIR, `${name}.png`),
      mask: dynamicMasks(page),
      animations: 'disabled',
    });
  }
  await expect(page).toHaveScreenshot(`${name}.png`, screenshotOpts(page));
}
