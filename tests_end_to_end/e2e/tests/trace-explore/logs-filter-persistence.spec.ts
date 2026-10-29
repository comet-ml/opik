import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { ProjectDashboardsPage } from '@e2e/pom/project-dashboards.page';
import { SidebarNav } from '@e2e/pom/sidebar.page';

type NavLogWindow = Window & { __navLog?: string[] };

/**
 * Logs filter chips are remembered per project in localStorage and restored
 * whenever the Logs URL carries no filter param (OPIK-8704). The sidebar's
 * "Logs" item navigates to a bare /logs URL, which is what exercises it.
 *
 * The tagged subset (2 of 3) is what makes each assertion meaningful: an
 * unrestored filter shows all three rows, so the row count tells restored from
 * dropped.
 */
test.describe('Logs filter persistence', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'Filters are restored when returning to Logs from another page via the sidebar',
    { tag: ['@cap:traces.filter-persistence'] },
    async ({ filterableTraces, project, page }) => {
      const logs = new LogsPage(page);
      const sidebar = new SidebarNav(page);
      const { all, sharedTag } = filterableTraces;
      const tagged = all.filter((t) => t.tags.includes(sharedTag));

      await test.step(`Open Traces and filter by tag "${sharedTag}"`, async () => {
        await logs.gotoTraces(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(all.length);
        await logs.applyFilter('tags', sharedTag);
        await expect(logs.traceRows).toHaveCount(tagged.length);
      });

      await test.step('Leave for the project Dashboards page', async () => {
        await sidebar.navigateTo('Dashboards');
        await new ProjectDashboardsPage(page, project.id).waitForReady();
      });

      await test.step('Return via the sidebar and verify the filter is restored', async () => {
        await sidebar.navigateTo('Logs');
        await logs.waitForUrlFilters('traces', (fs) =>
          fs.some((f) => f.field === 'tags' && f.value === sharedTag),
        );
        await expect(logs.filterChip('tags')).toBeVisible();
        await expect(logs.clearAllFiltersButton).toBeVisible();
        await expect(logs.traceRows).toHaveCount(tagged.length);
        for (const trace of tagged) {
          await expect(logs.traceRow(trace.id)).toBeVisible();
        }
      });
    },
  );

  test(
    'Filters stay applied when clicking Logs in the sidebar while already on Logs',
    { tag: ['@cap:traces.filter-persistence'] },
    async ({ filterableTraces, project, page }) => {
      const logs = new LogsPage(page);
      const sidebar = new SidebarNav(page);
      const { all, sharedTag } = filterableTraces;
      const tagged = all.filter((t) => t.tags.includes(sharedTag));

      // Records every SPA URL change so the test can prove a bare /logs
      // navigation happened (the sidebar link is already active, so nothing
      // else observable distinguishes the click from a no-op).
      await page.addInitScript(() => {
        const w = window as NavLogWindow;
        w.__navLog = [];
        for (const method of ['pushState', 'replaceState'] as const) {
          const original = window.history[method].bind(window.history);
          window.history[method] = (data, unused, url) => {
            if (url != null) w.__navLog?.push(String(url));
            original(data, unused, url);
          };
        }
      });

      await test.step(`Open Traces and filter by tag "${sharedTag}"`, async () => {
        await logs.gotoTraces(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(all.length);
        await logs.applyFilter('tags', sharedTag);
        await logs.waitForUrlFilters('traces', (fs) =>
          fs.some((f) => f.field === 'tags' && f.value === sharedTag),
        );
        await expect(logs.traceRows).toHaveCount(tagged.length);
        await page.evaluate(() => {
          (window as NavLogWindow).__navLog = [];
        });
      });

      await test.step('Click Logs in the sidebar and verify a bare navigation happened', async () => {
        await sidebar.navigateTo('Logs');
        await expect
          .poll(() =>
            page.evaluate(() =>
              ((window as NavLogWindow).__navLog ?? []).some(
                (url) => /\/logs(\?|$)/.test(url) && !url.includes('traces_filters'),
              ),
            ),
          )
          .toBe(true);
      });

      await test.step('Verify the filter came back', async () => {
        await logs.waitForUrlFilters('traces', (fs) =>
          fs.some((f) => f.field === 'tags' && f.value === sharedTag),
        );
        await expect(logs.filterChip('tags')).toBeVisible();
        await expect(logs.traceRows).toHaveCount(tagged.length);
      });
    },
  );

  test(
    'A new browser tab opened on bare Logs gets the remembered filters',
    { tag: ['@cap:traces.filter-persistence'] },
    async ({ filterableTraces, project, page, context }) => {
      const logs = new LogsPage(page);
      const { all, sharedTag } = filterableTraces;
      const tagged = all.filter((t) => t.tags.includes(sharedTag));

      await test.step(`Filter by tag "${sharedTag}" in the first tab`, async () => {
        await logs.gotoTraces(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(all.length);
        await logs.applyFilter('tags', sharedTag);
        await logs.waitForUrlFilters('traces', (fs) =>
          fs.some((f) => f.field === 'tags' && f.value === sharedTag),
        );
        await expect(logs.traceRows).toHaveCount(tagged.length);
      });

      await test.step('Open bare Logs in a new tab and verify the filter is restored', async () => {
        const newTab = await context.newPage();
        const newTabLogs = new LogsPage(newTab);
        await newTabLogs.gotoTraces(project.id);
        await newTabLogs.waitForReady();
        await newTabLogs.waitForUrlFilters('traces', (fs) =>
          fs.some((f) => f.field === 'tags' && f.value === sharedTag),
        );
        await expect(newTabLogs.filterChip('tags')).toBeVisible();
        await expect(newTabLogs.traceRows).toHaveCount(tagged.length);
        await newTab.close();
      });
    },
  );
});
