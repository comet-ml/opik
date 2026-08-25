import { test, expect } from '@e2e/fixtures';
import { DashboardsPage } from '@e2e/pom/dashboards.page';
import type { DashboardRef } from '@e2e/core/backend';

/**
 * OPIK-8047 — the Dashboards list filters by the Description column.
 *
 * `DashboardField` carried no `DESCRIPTION` constant, so `FiltersFactory`
 * rejected the whole `filters` query param and the endpoint answered 400 for
 * every operator the column offers. The table renders a failed list request as
 * "No matching results", so the bug presented as an empty table rather than an
 * error — which is why the assertion below is on the *rows*, not on a status.
 *
 * The seed deliberately includes a dashboard whose description does not carry
 * the marker: asserting only that the two matches appear would also pass
 * against a filter that was ignored entirely.
 */
test.describe(
  'Dashboards list filtering',
  { tag: ['@release-gate', '@release-gate:2.2.41', '@area:dashboards'] },
  () => {
    const created: DashboardRef[] = [];

    test.afterAll(async ({ backendClient }) => {
      for (const dashboard of created) {
        try {
          await backendClient.deleteDashboard(dashboard.id);
        } catch (err) {
          console.warn(`[opik-8047] cleanup warning for ${dashboard.name}:`, err);
        }
      }
    });

    test(
      'filters the list by description',
      { tag: ['@cap:dashboards.list-dashboards'] },
      async ({ page, backendClient, testNamespace }) => {
        const marker = `${testNamespace}-desc`;

        await test.step('Seed two matching dashboards and one that must not match', async () => {
          created.push(
            await backendClient.createDashboard({
              name: `${testNamespace}-match-1`,
              description: `contains ${marker} in the description`,
            }),
            await backendClient.createDashboard({
              name: `${testNamespace}-match-2`,
              description: `also contains ${marker} here`,
            }),
            await backendClient.createDashboard({
              name: `${testNamespace}-other`,
              description: 'unrelated description text',
            }),
          );
        });

        const dashboards = new DashboardsPage(page);
        await dashboards.goto();
        await dashboards.waitForReady();

        await dashboards.applyListFilter('Description', 'contains', marker);

        await test.step('Only the dashboards whose description matches are listed', async () => {
          await expect(dashboards.row(`${testNamespace}-match-1`)).toBeVisible();
          await expect(dashboards.row(`${testNamespace}-match-2`)).toBeVisible();
          await expect(dashboards.row(`${testNamespace}-other`)).toBeHidden();
          await expect(page.getByText('No matching results')).toBeHidden();
        });
      },
    );
  },
);
