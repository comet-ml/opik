import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Configuring columns on a project big enough to window the Logs table.
 *
 * Past a fixed budget (`columns × rows > 3000`, `getVirtualizationConfig` in
 * shared/DataTable/utils.tsx) the table renders only a window of its rows and
 * its columns. The failure that introduces is silent rather than loud: a row or
 * a column that exists but is never in the DOM, or a selection count that only
 * reports what is rendered. A spec that counted rendered rows would agree with
 * every one of those. So each test here asserts on the WHOLE set — all 100
 * seeded ids, all N column ids — by sweeping the scroll container and collecting
 * what each window rendered.
 *
 * The seed sits on both sides of the budget on purpose: the default column
 * selection stays under it and turning every column on crosses it. Both are
 * asserted from the counts the Columns control itself reports, so if the table's
 * column set ever drifts far enough to close that gap these fail saying so,
 * rather than going quietly green having stopped exercising windowing at all.
 */
const VIRTUALIZATION_CELL_BUDGET = 3000;

test.describe(
  'Trace logs — column configuration under table virtualization',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'Turning on every column windows the table while keeping every row and every column reachable',
      { tag: ['@cap:traces.configure-columns'] },
      async ({ wideTraces, project, page }) => {
        const logs = new LogsPage(page);
        let pinnedColumns = 0;
        let totalColumns = 0;

        await test.step('Open Logs with every seeded trace on one page', async () => {
          await logs.goto(project.id, { pageSize: wideTraces.traceCount });
          await logs.waitForReady();

          // Under the budget: every row is in the DOM and there is no filler row.
          await expect(logs.traceRows).toHaveCount(wideTraces.traceCount);
          await expect(logs.spacerRows).toHaveCount(0);
        });

        await test.step('Confirm the default selection is under the virtualization budget', async () => {
          // Every seeded score name becomes a column of its own, on a query of
          // its own — so wait for them before reading the widths that decide
          // which side of the budget this table is on.
          await expect
            .poll(async () => (await logs.renderedFeedbackScoreColumnIds()).length)
            .toBe(wideTraces.scoreNames.length);

          const { selected, total } = await logs.readColumnCounts();
          const renderedNow = (await logs.renderedHeaderIds()).length;
          // Nothing is windowed yet, so what is rendered IS the whole header row.
          // The difference is the pinned select column, which the picker does not
          // list — carry it forward so the "all columns" arithmetic below counts
          // the same things the FE does.
          pinnedColumns = renderedNow - selected;
          totalColumns = total + pinnedColumns;
          expect(pinnedColumns).toBeGreaterThan(0);

          expect(renderedNow * wideTraces.traceCount).toBeLessThanOrEqual(
            VIRTUALIZATION_CELL_BUDGET,
          );
          expect(totalColumns * wideTraces.traceCount).toBeGreaterThan(VIRTUALIZATION_CELL_BUDGET);
        });

        await test.step('Turn on every column and confirm the table is now windowed', async () => {
          await logs.selectAllColumns();

          await expect
            .poll(async () => (await logs.renderedHeaderIds()).length)
            .toBeLessThan(totalColumns);
          expect(await logs.traceRows.count()).toBeLessThan(wideTraces.traceCount);
          expect(await logs.spacerRows.count()).toBeGreaterThan(0);
        });

        await test.step('Scroll top to bottom and account for every seeded trace', async () => {
          const { seenIds, blankRows } = await logs.sweepRowsVertically(wideTraces.traceIds);

          expect(blankRows).toBe(0);
          expect([...seenIds].sort()).toEqual([...wideTraces.traceIds].sort());
        });

        await test.step('Scroll left to right and account for every column', async () => {
          await logs.resetTableScroll();
          const { headerIds, lastHeaderRight, containerRight } =
            await logs.sweepColumnsHorizontally();

          expect(headerIds.size).toBe(totalColumns);
          // Scrolled as far right as it goes, the right-most column ends flush
          // with the container's edge: no column is stranded past what the
          // scroll can reach, and the trailing spacer is sized correctly.
          expect(Math.abs(lastHeaderRight - containerRight)).toBeLessThanOrEqual(1);
        });
      },
    );

    test(
      'Select all reports every row while only a window of them is rendered, and rows scrolled into view arrive selected',
      { tag: ['@cap:traces.configure-columns'] },
      async ({ wideTraces, project, page }) => {
        const logs = new LogsPage(page);

        await test.step('Open Logs and turn on every column to window the table', async () => {
          await logs.goto(project.id, { pageSize: wideTraces.traceCount });
          await logs.waitForReady();
          // Every seeded score name becomes a column of its own, on a query of
          // its own — so wait for them before reading the widths that decide
          // which side of the budget this table is on.
          await expect
            .poll(async () => (await logs.renderedFeedbackScoreColumnIds()).length)
            .toBe(wideTraces.scoreNames.length);

          await logs.selectAllColumns();
          await expect.poll(async () => logs.spacerRows.count()).toBeGreaterThan(0);
        });

        await test.step('Select all rows and confirm the count covers the whole page', async () => {
          await logs.selectAllRowsCheckbox.click();

          // The count the bar reports is the whole page, not the rendered window —
          // and the second assertion is what makes the first one mean something.
          await expect(logs.selectionSummary).toHaveText(`Selected: ${wideTraces.traceCount}`);
          expect(await logs.traceRows.count()).toBeLessThan(wideTraces.traceCount);
        });

        await test.step('Scroll to the bottom and confirm the rows rendered there are selected too', async () => {
          const { seenIds } = await logs.sweepRowsVertically(wideTraces.traceIds);
          expect([...seenIds].sort()).toEqual([...wideTraces.traceIds].sort());

          const rows = await logs.traceRows.all();
          expect(rows.length).toBeGreaterThan(0);
          for (const row of rows) {
            await expect(row).toHaveAttribute('data-state', 'selected');
          }
          await expect(logs.selectionSummary).toHaveText(`Selected: ${wideTraces.traceCount}`);
        });
      },
    );

    test(
      'Searching below the virtualization budget renders the whole table again, and clearing the search restores every row',
      { tag: ['@cap:traces.configure-columns'] },
      async ({ wideTraces, project, page }) => {
        const logs = new LogsPage(page);
        let totalColumns = 0;
        let scrollTopWhileWindowed = 0;

        await test.step('Open Logs and turn on every column to window the table', async () => {
          await logs.goto(project.id, { pageSize: wideTraces.traceCount });
          await logs.waitForReady();
          // Every seeded score name becomes a column of its own, on a query of
          // its own — so wait for them before reading the widths that decide
          // which side of the budget this table is on.
          await expect
            .poll(async () => (await logs.renderedFeedbackScoreColumnIds()).length)
            .toBe(wideTraces.scoreNames.length);

          const before = await logs.readColumnCounts();
          const renderedBefore = (await logs.renderedHeaderIds()).length;
          totalColumns = before.total + (renderedBefore - before.selected);

          await logs.selectAllColumns();
          await expect.poll(async () => logs.spacerRows.count()).toBeGreaterThan(0);
        });

        await test.step('Scroll away from the top so the reset below is observable', async () => {
          await logs.sweepRowsVertically(wideTraces.traceIds);
          scrollTopWhileWindowed = await logs.tableScrollTop();
          expect(scrollTopWhileWindowed).toBeGreaterThan(0);
        });

        await test.step(`Search "${wideTraces.searchTerm}" and confirm windowing turns off`, async () => {
          await logs.searchTraces(wideTraces.searchTerm);

          await expect(logs.traceRows).toHaveCount(wideTraces.searchMatchIds.length);
          for (const id of wideTraces.searchMatchIds) {
            await expect(logs.traceRow(id)).toBeVisible();
          }

          // Few enough cells to fall under the budget: every column is in the
          // DOM at once and there are no filler rows left.
          await expect.poll(async () => (await logs.renderedHeaderIds()).length).toBe(totalColumns);
          await expect(logs.spacerRows).toHaveCount(0);

          // The table just got 90 rows shorter under a view that was scrolled to
          // the bottom of the long one, so the result has to be on screen rather
          // than stranded below it. Asserted as "the first match is in the
          // viewport" and not as `scrollTop === 0`: the browser clamps to the
          // new maximum, and whether ten rows still overflow at all is a fact
          // about the window size, not about the flip.
          expect(await logs.tableScrollTop()).toBeLessThan(scrollTopWhileWindowed);
          await expect(logs.traceRow(wideTraces.searchMatchIds[0])).toBeInViewport();
        });

        await test.step('Clear the search and confirm every row is reachable again', async () => {
          await logs.clearTraceSearch();

          await expect.poll(async () => logs.spacerRows.count()).toBeGreaterThan(0);
          const { seenIds, blankRows } = await logs.sweepRowsVertically(wideTraces.traceIds);
          expect(blankRows).toBe(0);
          expect([...seenIds].sort()).toEqual([...wideTraces.traceIds].sort());
        });
      },
    );
  },
);
