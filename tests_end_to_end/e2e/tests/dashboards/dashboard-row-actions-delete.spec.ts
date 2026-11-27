import { test, expect } from '@e2e/fixtures';
import { DashboardsPage } from '@e2e/pom/dashboards.page';

/**
 * Deleting a dashboard from its row-actions menu on the Dashboards list.
 *
 * `dashboards.delete-dashboard` was the one uncovered capability in the
 * dashboards area, and opik#9832 is the release that touched the cell it runs
 * through: the shared `DataTable` moved its utility columns from pixel to
 * percentage widths, and the dashboards row-actions cell was rewrapped so the
 * menu's clicks stop propagating. The list binds `onRowClick` to a `navigate()`
 * into the dashboard, so a click that reached the row handler would open the
 * dashboard behind the menu — the menu would appear to work, the delete would
 * appear to work, and the user would end up on a page they never asked for.
 * That is why the URL is asserted twice below, not once.
 *
 * Two dashboards are seeded and only one is addressed. The delete goes through
 * `useDashboardBatchDeleteMutation`, which posts an `ids` ARRAY — the same
 * mutation the multi-select toolbar uses — so "the wrong ids were sent" is a
 * real failure mode, and asserting only that the target row disappeared would
 * read identically if the request had taken both. The bystander is what makes
 * "exactly one dashboard went" a verdict.
 *
 * Both halves are asserted, in the table and again server-side, because they
 * are different claims: the list invalidates its query optimistically, so a row
 * leaving the table says only that the UI believes the delete happened. A read
 * back over the API is what says the dashboard is actually gone — and that the
 * bystander is actually still there.
 */
test.describe('Dashboards — row-actions delete', { tag: ['@t2-cuj', '@area:dashboards'] }, () => {
  test(
    'deletes exactly the targeted dashboard, leaves the bystander, and never opens either',
    { tag: ['@cap:dashboards.delete-dashboard'] },
    async ({ page, backendClient, dashboardRowActions }) => {
      const { target, control, namePrefix } = dashboardRowActions;
      const dashboards = new DashboardsPage(page);

      const listUrl = await test.step('Open the list narrowed to the two seeded dashboards', async () => {
        await dashboards.gotoSearchedByName(namePrefix);
        await dashboards.waitForReady();

        // Both rows addressed by id, not by name: this page's column order is
        // user-configurable and persisted, so a name-cell lookup is not
        // anchored to the row's identity.
        await expect(
          dashboards.rowById(target.id),
          'the target dashboard is listed before anything is deleted',
        ).toHaveCount(1);
        await expect(
          dashboards.rowById(control.id),
          'the bystander is listed too — without it, "one row went" is not a verdict',
        ).toHaveCount(1);

        return page.url();
      });

      await test.step('Opening the row-actions menu does not navigate into the dashboard', async () => {
        const menu = await dashboards.openRowActionsMenu(target.id);
        await expect(menu.getByRole('menuitem', { name: 'Delete', exact: true })).toBeVisible();
        expect(
          page.url(),
          'the menu trigger must not reach the row\'s own onRowClick — the cell wraps it to stop propagation',
        ).toBe(listUrl);

        // Closed again so the delete below drives the menu itself rather than
        // inheriting an already-open one, which would make this step a
        // precondition of that one instead of an independent claim.
        await page.keyboard.press('Escape');
        await menu.waitFor({ state: 'hidden' });
      });

      await test.step('Delete the target through its row-actions menu', async () => {
        await dashboards.deleteFromRowActionsMenu(target.id);
      });

      await test.step('The target row is gone and the bystander is still listed', async () => {
        await expect(
          dashboards.rowById(target.id),
          'the deleted dashboard must leave the table',
        ).toHaveCount(0);
        await expect(
          dashboards.rowById(control.id),
          'the bystander must survive — a delete that took the whole list would also empty the target row',
        ).toHaveCount(1);
      });

      await test.step('The whole flow never left the Dashboards list', async () => {
        // The second URL assertion, and the one the rewrap is really about: the
        // Delete menu item and the confirm dialog's button both sit inside the
        // row, so either could have propagated a click to the row handler.
        expect(
          page.url(),
          'deleting from the row menu must not also open a dashboard',
        ).toBe(listUrl);
      });

      await test.step('Server-side, exactly the bystander survives under the seeded prefix', async () => {
        // The claim the table cannot make. Asserted as the WHOLE answer for
        // this prefix rather than as "the control is in there somewhere": a
        // response that also still carried the target would satisfy a `find()`
        // and pass, and that is the regression this test exists to catch.
        const survivors = await backendClient.listDashboardsWithPrefix(namePrefix);
        expect(
          survivors.map((dashboard) => dashboard.id),
          `only the bystander may remain under '${namePrefix}'`,
        ).toEqual([control.id]);
      });
    },
  );
});
