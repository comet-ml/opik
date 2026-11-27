import {
  test,
  expect,
  OLLIE_TABLE_COLUMNS,
  OLLIE_TABLE_ROWS,
  OVERSIZED_SAVED_COLUMN_WIDTH,
} from '@e2e/fixtures';
import { OllieTableWidget } from '@e2e/pom/ollie-table-widget';
import { ProjectDashboardsPage } from '@e2e/pom/project-dashboards.page';

/**
 * How wide gets drawn in a resizable Ollie TABLE widget (OPIK-9123, shipped by
 * opik#9445).
 *
 * The other half of the same untested round trip the sort spec drives, and the
 * half that goes wrong SILENTLY rather than with an error: a column width is
 * not a preference of the person who dragged it, it is part of the shared
 * dashboard config, so the number travels to everyone who opens the view. A
 * width dragged out on a wide monitor therefore has to be clamped on a narrower
 * viewer's screen, and the change's own Vitest suite can only reach that through
 * a fake `ResizeObserver` in happy-dom, which never lays anything out — so the
 * only place the clamp is observable is a real browser.
 *
 * Both tests read the DECLARED width out of the `<colgroup>` rather than the
 * rendered header box, and the POM explains why at length: the table is
 * `w-full table-fixed`, so the browser stretches the columns whenever they total
 * less than the frame, and the drawn header is therefore wider than the number
 * that was saved.
 *
 * Why `@cap:dashboards.edit-dashboard`: that key's own note reserves
 * "reconfiguring a widget ... and the auto-save those go through" as its
 * uncovered half. Resizing a column is that, and the clamp is what the same
 * stored value has to survive being read back on a different screen.
 */
test.describe(
  'Ollie table widget — column width',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    test(
      'a dragged column width is saved on the widget and redrawn at the same width after a reload',
      { tag: ['@cap:dashboards.edit-dashboard'] },
      async ({ ollieTableWidget, backendClient, page }) => {
        const { view, project, widget } = ollieTableWidget;
        const dashboards = new ProjectDashboardsPage(page, project.id);
        const table = new OllieTableWidget(page, widget.title);

        // Narrowing, and by enough to be unambiguous while staying far clear of
        // the component's 80px floor — the dragged width lands around 226px, so
        // a result that was clamped, ignored or reset reads as a different
        // number rather than as the same one by luck.
        const DRAG_BY = -150;

        await test.step('The dashboard opens on the seeded view and draws the table', async () => {
          await dashboards.goto({ dashboardId: view.id });
          await dashboards.waitForReady();
          await table.waitForReady(OLLIE_TABLE_COLUMNS, OLLIE_TABLE_ROWS.length);
        });

        const before = await test.step('Read the width the model column starts at', async () => {
          const widths = await table.columnWidths();
          // The seed carries no `columnsWidth` for this widget, so the starting
          // width is the one the component derived from the content. Captured
          // rather than hard-coded, and the assertions below are stated as
          // "the starting width less the drag", which is the claim — a literal
          // would silently become a different claim the day the seed's longest
          // value changes.
          expect(
            widths.model,
            'the model column starts wide enough for a 150px drag to leave it well above the 80px floor',
          ).toBeGreaterThan(300);
          return widths;
        });

        await test.step('Dragging its border narrows the column and saves the new width', async () => {
          const saved = dashboards.autoSaveLanded(view.id);
          await table.dragColumnBorderBy('model', DRAG_BY);

          const after = await table.columnWidths();
          expect(after.model, 'the model column is narrower by exactly the pointer travel').toBe(
            before.model + DRAG_BY,
          );
          // The whole colgroup, not just the column that moved: `shownSizing`
          // re-shares whatever the dragged column gave up, and the documented
          // behaviour is that only the dragged column changes its declared
          // width while the others keep theirs. A drag that quietly rewrote
          // its neighbours would also be persisting their widths for every
          // other viewer.
          expect(after, 'and no other column changed its declared width').toEqual({
            ...before,
            model: before.model + DRAG_BY,
          });

          await saved;
          const widgets = await backendClient.listInsightsViewWidgets(view.id);
          expect(widgets, 'the view is still readable').not.toBeNull();
          const found = (widgets ?? []).filter((w) => w.id === widget.id);
          expect(found, `exactly one widget stored at id ${widget.id}`).toHaveLength(1);
          expect(found[0], 'the width is stored on the widget and nothing else changed').toEqual({
            id: widget.id,
            type: 'ollie_chart',
            title: widget.title,
            config: { ...widget.config, columnsWidth: { model: before.model + DRAG_BY } },
          });
        });

        await test.step('A reload draws the column at the saved width', async () => {
          await dashboards.goto({ dashboardId: view.id });
          await dashboards.waitForReady();
          await table.waitForReady(OLLIE_TABLE_COLUMNS, OLLIE_TABLE_ROWS.length);

          expect(
            await table.columnWidths(),
            'every column is drawn as it was left, the resized one included',
          ).toEqual({ ...before, model: before.model + DRAG_BY });
        });
      },
    );

    test(
      'a saved width wider than the widget is drawn at the widget’s width, and the table scrolls rather than overflowing',
      { tag: ['@cap:dashboards.edit-dashboard'] },
      async ({ ollieTableWidget, page }) => {
        const { view, project, widget, narrowWidget } = ollieTableWidget;
        const dashboards = new ProjectDashboardsPage(page, project.id);
        const wide = new OllieTableWidget(page, widget.title);
        const narrow = new OllieTableWidget(page, narrowWidget.title);

        await test.step('The dashboard opens on the seeded view and draws both tables', async () => {
          await dashboards.goto({ dashboardId: view.id });
          await dashboards.waitForReady();
          await wide.waitForReady(OLLIE_TABLE_COLUMNS, OLLIE_TABLE_ROWS.length);
          await narrow.waitForReady(OLLIE_TABLE_COLUMNS, OLLIE_TABLE_ROWS.length);
        });

        const frame = await narrow.tableFrame();
        const contentWidth = (await wide.columnWidths()).model;

        await test.step('The narrow widget really is narrower than the column wants to be', async () => {
          // The precondition that makes the rest of this test mean what it
          // says. Two caps apply to a saved width — the column's content and
          // the widget — and they are indistinguishable unless one is provably
          // smaller. The content width is read off the full-width widget, where
          // nothing constrains the same column, so this is derived from the
          // render rather than assumed from the viewport.
          expect(
            contentWidth,
            'the model column wants more room than the narrow widget has, so the cap below is the widget cap',
          ).toBeGreaterThan(frame.clientWidth);
          expect(
            OVERSIZED_SAVED_COLUMN_WIDTH,
            'and the saved width is past both caps',
          ).toBeGreaterThan(contentWidth);
        });

        await test.step('The column is drawn at the widget’s width, not at the width that was saved', async () => {
          const widths = await narrow.columnWidths();
          expect(
            widths.model,
            'the saved width is clamped to the frame the widget gives the table',
          ).toBe(frame.clientWidth);
          // Stated separately and in the other direction, because the number
          // above would also be right if the clamp were applied to the wrong
          // thing: what must never happen is this width reaching the page.
          expect(
            widths.model,
            'and is nowhere near the width somebody else saved',
          ).toBeLessThan(OVERSIZED_SAVED_COLUMN_WIDTH);
        });

        await test.step('The rest of the table keeps its minimum, so the table scrolls inside the widget', async () => {
          // The two halves of "scrolls rather than overflows". The content is
          // wider than the frame, so there is something to scroll to — a clamp
          // that had instead squeezed every column to fit would satisfy no
          // claim about the saved width. And the frame itself stays inside the
          // widget, which is what "not overflowing" means: the columns past the
          // edge are reachable by scrolling, not drawn over the dashboard.
          expect(
            frame.scrollWidth,
            'the table is wider than its frame, so it scrolls',
          ).toBeGreaterThan(frame.clientWidth);
          expect(
            frame.clientWidth,
            'while the frame stays within the widget rather than spilling out of it',
          ).toBeLessThanOrEqual(frame.widgetWidth);
        });
      },
    );
  },
);
