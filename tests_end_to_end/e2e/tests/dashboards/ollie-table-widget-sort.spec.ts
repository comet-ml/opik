import {
  test,
  expect,
  OLLIE_TABLE_COLUMNS,
  OLLIE_TABLE_COST_AS_STORED,
  OLLIE_TABLE_COST_ASCENDING,
  OLLIE_TABLE_COST_DESCENDING,
  OLLIE_TABLE_ROWS,
} from '@e2e/fixtures';
import { OllieTableWidget } from '@e2e/pom/ollie-table-widget';
import { ProjectDashboardsPage } from '@e2e/pom/project-dashboards.page';

/**
 * Sorting an Ollie TABLE widget's column goes through the dashboard auto-save
 * and comes back on a reload (OPIK-9123, shipped by opik#9445).
 *
 * The widget's table is now the shared `DataTable`, so a header click sorts it
 * and the chosen sort is written into the widget's own config — `config.sorting`
 * — by the same debounced PATCH of `/v1/private/insights-views/{id}` that saves
 * everything else on the dashboard. That round trip is the half nothing else
 * checks. Both of the change's Vitest files stub `updateWidget`, so they prove
 * the component asks to be saved and nothing about what reaches the API: not
 * that `{...config, ...patch}` keeps the widget's `rows`, `spec` and `version`
 * on the way, and not that a reload reapplies the sort rather than redrawing
 * the stored row order.
 *
 * The three orders asserted here are deliberately all different. The seeded
 * rows arrive in none of them, so a first click that reorders nothing fails
 * instead of passing against rows that were already sorted; and `cost` mixes a
 * number, a numeric STRING, text and a null, so the ranking rule the change
 * added — numbers first as numbers, then text, then empty cells, in EITHER
 * direction — is visible in both directions rather than only in one.
 *
 * Why `@cap:dashboards.edit-dashboard`: that key's own note reserves
 * "reconfiguring a widget ... and the auto-save those go through" as its
 * uncovered half, which is exactly this. The existing specs under the key only
 * reach the template-copy-and-rename flow.
 *
 * Deterministic on a self-hosted install with no Ollie and no provider keys:
 * the widget is seeded with a snapshot `rows` array and no `query.id`, so it
 * draws the seed rather than a query result, and `OLLIE_ENABLED` gates only its
 * Edit-in-Ollie menu actions.
 */
test.describe('Ollie table widget — column sort', { tag: ['@t2-cuj', '@area:dashboards'] }, () => {
  test(
    'a column sort round-trips through the dashboard auto-save and is reapplied on reload',
    { tag: ['@cap:dashboards.edit-dashboard'] },
    async ({ ollieTableWidget, backendClient, page }) => {
      const { view, project, widget } = ollieTableWidget;
      const dashboards = new ProjectDashboardsPage(page, project.id);
      const table = new OllieTableWidget(page, widget.title);

      /**
       * The widget's config as stored, with `sorting` split off.
       *
       * Everything else is compared AS A WHOLE against the seed rather than
       * key by key, because the failure this exists to catch is a save that
       * writes the sort correctly and loses something else in the spread — the
       * snapshot `rows`, the `spec` that makes it a table at all, the `version`
       * Ollie stamps, the `scope`, the description the header renders as a
       * subtitle. A per-key check would have to predict which one.
       */
      const storedWidget = async () => {
        const widgets = await backendClient.listInsightsViewWidgets(view.id);
        expect(widgets, 'the view is still readable').not.toBeNull();
        const found = (widgets ?? []).filter((w) => w.id === widget.id);
        expect(found, `exactly one widget stored at id ${widget.id}`).toHaveLength(1);
        const { sorting, ...rest } = found[0].config;
        return { sorting, rest: { ...found[0], config: rest } };
      };

      await test.step('The dashboard opens on the seeded view and draws the table', async () => {
        await dashboards.goto({ dashboardId: view.id });
        await dashboards.waitForReady();
        expect(dashboards.currentDashboardId(), 'the page is showing the seeded view').toBe(view.id);
        await table.waitForReady(OLLIE_TABLE_COLUMNS, OLLIE_TABLE_ROWS.length);
      });

      await test.step('It draws the rows in the order they are stored, with no column sorted', async () => {
        // The baseline the two clicks below are measured against. Without it a
        // sort that did nothing at all would be indistinguishable from one
        // that worked, because the stored order is a valid-looking order too.
        expect(await table.columnValues('cost'), 'cost in stored order').toEqual([
          ...OLLIE_TABLE_COST_AS_STORED,
        ]);
        await expect(
          table.sortIndicator('cost', 'desc'),
          'no descending indicator before anything is clicked',
        ).toBeHidden();
        await expect(
          table.sortIndicator('cost', 'asc'),
          'and no ascending one either',
        ).toBeHidden();
      });

      await test.step('Clicking the numeric column sorts it descending and saves that', async () => {
        const saved = dashboards.autoSaveLanded(view.id);
        await table.sortByColumn('cost');

        // The ranking in full: 10, 9.5, 2 as numbers (9.5 is stored as the
        // string "9.5", so a lexical sort would put it last of the three),
        // then "n/a" because text ranks below every number, then the null's
        // "-" placeholder last.
        expect(await table.columnValues('cost'), 'cost, highest first').toEqual([
          ...OLLIE_TABLE_COST_DESCENDING,
        ]);
        await expect(
          table.sortIndicator('cost', 'desc'),
          'the header shows the descending indicator',
        ).toBeVisible();

        await saved;
        const { sorting, rest } = await storedWidget();
        expect(sorting, 'the descending sort is stored on the widget').toEqual([
          { id: 'cost', desc: true },
        ]);
        expect(rest, 'and nothing else about the widget changed').toEqual({
          id: widget.id,
          type: 'ollie_chart',
          title: widget.title,
          config: widget.config,
        });
      });

      await test.step('Clicking it again sorts ascending, with text and empty cells still last', async () => {
        const saved = dashboards.autoSaveLanded(view.id);
        await table.sortByColumn('cost');

        expect(await table.columnValues('cost'), 'cost, lowest first').toEqual([
          ...OLLIE_TABLE_COST_ASCENDING,
        ]);
        await expect(
          table.sortIndicator('cost', 'asc'),
          'the header shows the ascending indicator',
        ).toBeVisible();
        await expect(
          table.sortIndicator('cost', 'desc'),
          'and no longer the descending one',
        ).toBeHidden();

        await saved;
        const { sorting, rest } = await storedWidget();
        expect(sorting, 'the direction is replaced, not appended to').toEqual([
          { id: 'cost', desc: false },
        ]);
        expect(rest, 'and still nothing else about the widget changed').toEqual({
          id: widget.id,
          type: 'ollie_chart',
          title: widget.title,
          config: widget.config,
        });
      });

      await test.step('A reload reapplies the stored sort without the header being touched', async () => {
        // Navigated afresh rather than `page.reload()`-ed so the view list is
        // awaited the way `goto` documents; either way the page remounts and
        // re-reads the config from the server, which is the point.
        await dashboards.goto({ dashboardId: view.id });
        await dashboards.waitForReady();
        await table.waitForReady(OLLIE_TABLE_COLUMNS, OLLIE_TABLE_ROWS.length);

        expect(
          await table.columnValues('cost'),
          'the saved ascending order is what the fresh page draws',
        ).toEqual([...OLLIE_TABLE_COST_ASCENDING]);
        await expect(
          table.sortIndicator('cost', 'asc'),
          'and the header says so, so the sort was restored rather than the rows reordered',
        ).toBeVisible();
      });
    },
  );
});
