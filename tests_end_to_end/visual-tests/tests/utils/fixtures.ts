import { test as base } from '@playwright/test';

// Campaign banners show on every page until their end date, so pre-dismiss them
// to keep screenshots independent of the run date.
const DISMISSED_BANNER_KEYS = ['mcp-announcement-dismissed-v1'];

export const test = base.extend({
  context: async ({ context }, use) => {
    await context.addInitScript({
      content: DISMISSED_BANNER_KEYS.map((key) => `localStorage.setItem('${key}', 'true');`).join('\n'),
    });
    await use(context);
  },
});
