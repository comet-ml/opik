import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * One Ollie TABLE widget on a dashboard — the `{"table": {"columns": [...]}}`
 * form of an `ollie_chart`, which `VegaChart` renders through `RowsTable` and
 * the shared `DataTable` instead of through Vega (opik#9445, OPIK-9123).
 *
 * Addressed by the widget's own title rather than by position in the grid,
 * because a dashboard holds several widgets and which one a spec means is the
 * subject of half the assertions here.
 *
 * ## The three widths this object can report, and why they are different
 *
 * The table is `w-full table-fixed`, so the browser STRETCHES the columns
 * proportionally whenever their declared widths total less than the frame. That
 * makes three numbers available and only one of them the right answer to
 * "how wide is this column":
 *
 * - `columnWidths()` reads the `<colgroup>`, which is the width the component
 *   DECLARED — `shownSizing`, i.e. the saved width capped to the column's
 *   content and to the widget. This is the number the save round-trips and the
 *   one every assertion here uses.
 * - a header's rendered `getBoundingClientRect().width` is that number after the
 *   CSS stretch, so on a table narrower than its frame it is always larger. A
 *   spec comparing it against a saved width would be comparing against the
 *   browser's layout arithmetic.
 * - `tableFrame().clientWidth` is the scroll container's inner width — the
 *   widget's own width less its padding, border and scrollbar. It is the cap a
 *   saved width is clamped to, so it is read from the DOM rather than derived
 *   from the viewport.
 *
 * ## Selectors
 *
 * `data-testid="dashboard-widget"` and `data-header-id` / `data-cell-id` are
 * the shared `DashboardWidget`'s and `DataTable`'s own attributes. The resize
 * handle is the one exception: `DataTableColumnResizer` renders an unlabelled
 * `div` with no role and no test id, so it is matched on its `cursor-ew-resize`
 * class — the class that makes it the resize affordance, not a positional path.
 * A `data-testid` belongs on that component, but it is shared frontend code and
 * this change is confined to `tests_end_to_end/`. Every lookup asserts it
 * resolved to exactly one element, so a rename fails loudly here rather than
 * silently resizing nothing.
 */
export class OllieTableWidget {
  constructor(
    private readonly page: Page,
    private readonly widgetTitle: string,
  ) {}

  /**
   * The widget's card.
   *
   * Filtered on the title as EXACT text: the seeded titles in a dashboard are
   * deliberately related ("… by model" and "… by model, narrow"), and a
   * substring filter would match both and then fail strict mode somewhere less
   * obvious. Asserted to be a single card by every method that uses it.
   */
  get root(): Locator {
    return this.page
      .getByTestId('dashboard-widget')
      .filter({ has: this.page.getByText(this.widgetTitle, { exact: true }) });
  }

  /** The one header cell for a column, by the column id the widget's spec names. */
  header(columnId: string): Locator {
    return this.root.locator(`th[data-header-id="${columnId}"]`);
  }

  /** Every rendered cell of one column, in the order the rows are drawn. */
  cells(columnId: string): Locator {
    return this.root.locator(`td[data-cell-id$="_${columnId}"]`);
  }

  /**
   * The ascending / descending sort indicator in a column's header.
   *
   * `useSortableHeader` renders an icon for the NEXT sort direction on every
   * sortable column and keeps it `hidden` until hover, swapping in the current
   * direction's icon — visible — once the column is actually sorted. So
   * `toBeVisible()` on this locator is the real claim "this column is sorted
   * this way", while an unsorted column's icon exists in the DOM and is not
   * visible. Nothing here hovers, which is what keeps that true.
   */
  sortIndicator(columnId: string, direction: 'asc' | 'desc'): Locator {
    const icon = direction === 'asc' ? 'arrow-up-narrow-wide' : 'arrow-down-wide-narrow';
    return this.header(columnId).locator(`svg.lucide-${icon}`);
  }

  /**
   * Waits for the table to have drawn its columns and all of its rows.
   *
   * Both halves: the headers prove the widget resolved its spec as a table
   * rather than falling through to Vega or to an empty state, and the row count
   * is what stops a caller reading an order off a half-rendered body.
   */
  async waitForReady(columnIds: readonly string[], rowCount: number): Promise<void> {
    return test.step(`Wait for the "${this.widgetTitle}" table to render`, async () => {
      await expect(this.root, `exactly one "${this.widgetTitle}" widget`).toHaveCount(1);
      for (const columnId of columnIds) {
        await expect(this.header(columnId), `the "${columnId}" column header`).toHaveCount(1);
      }
      await expect(
        this.cells(columnIds[0]),
        `all ${rowCount} rows of the "${columnIds[0]}" column`,
      ).toHaveCount(rowCount);
    });
  }

  /**
   * One column's rendered values, top to bottom.
   *
   * Positional on purpose, and the one place in this object that is: the
   * subject is the ORDER the rows are drawn in, so the identity of each row is
   * exactly what must not be used to look them up.
   */
  async columnValues(columnId: string): Promise<string[]> {
    return test.step(`Read the "${columnId}" column, top to bottom`, async () => {
      const cells = this.cells(columnId);
      // `allInnerTexts` resolves the locator once with no implicit wait, so a
      // body that has not finished rendering would come back short rather than
      // being waited for. The count assertion is the wait.
      await expect(cells, `the "${columnId}" column has cells to read`).not.toHaveCount(0);
      return (await cells.allInnerTexts()).map((text) => text.trim());
    });
  }

  /** Clicks a column header, which toggles the sort the way a user does. */
  async sortByColumn(columnId: string): Promise<void> {
    return test.step(`Click the "${columnId}" column header`, async () => {
      const header = this.header(columnId);
      await expect(header, `exactly one "${columnId}" header to click`).toHaveCount(1);
      await header.click();
    });
  }

  /**
   * The width each column DECLARES, keyed by column id.
   *
   * The `<colgroup>` carries no ids of its own, so a `<col>` is paired with its
   * header through `th.cellIndex` — read out of the DOM in the same pass rather
   * than assumed from the order the spec lists its columns in. Returned as a
   * map so a caller never indexes into a list.
   */
  async columnWidths(): Promise<Record<string, number>> {
    return test.step(`Read the "${this.widgetTitle}" table's declared column widths`, async () => {
      await expect(this.root, `exactly one "${this.widgetTitle}" widget`).toHaveCount(1);
      const measured = await this.root.evaluate((card) => {
        const table = card.querySelector('table');
        if (!table) return null;
        const cols = Array.from(table.querySelectorAll('colgroup > col'));
        return Array.from(table.querySelectorAll('th[data-header-id]')).map((th) => {
          const col = cols[(th as HTMLTableCellElement).cellIndex];
          return {
            id: th.getAttribute('data-header-id'),
            width: col instanceof HTMLElement ? col.style.width : null,
          };
        });
      });

      // Asserted away rather than coded around: a table that rendered no
      // `<colgroup>`, or a header with no `<col>` above it, is a broken render,
      // and a `?? 0` here would turn it into a width comparison that quietly
      // passed or quietly failed for the wrong reason.
      expect(measured, 'the table rendered a <colgroup> to measure').not.toBeNull();
      const widths: Record<string, number> = {};
      for (const column of measured ?? []) {
        expect(column.id, 'every header carries a data-header-id').not.toBeNull();
        expect(
          column.width,
          `the "${column.id}" column has a <col> with a width`,
        ).toMatch(/^\d+(\.\d+)?px$/);
        widths[column.id as string] = parseFloat(column.width as string);
      }
      return widths;
    });
  }

  /**
   * The scroll frame's inner width and its content's width, plus the widget's
   * own width.
   *
   * `clientWidth` vs `scrollWidth` is how "the table scrolls" is told from "the
   * table overflowed its widget": a capped table is wider than its frame and
   * scrolls inside it, while the frame itself stays within the widget. Both
   * facts are needed, because either alone is satisfied by the wrong outcome.
   */
  async tableFrame(): Promise<{ clientWidth: number; scrollWidth: number; widgetWidth: number }> {
    return test.step(`Measure the "${this.widgetTitle}" table's scroll frame`, async () => {
      await expect(this.root, `exactly one "${this.widgetTitle}" widget`).toHaveCount(1);
      const measured = await this.root.evaluate((card) => {
        const frame = card.querySelector('[data-table-scroll-container]');
        if (!(frame instanceof HTMLElement)) return null;
        return {
          clientWidth: frame.clientWidth,
          scrollWidth: frame.scrollWidth,
          widgetWidth: Math.round(card.getBoundingClientRect().width),
        };
      });
      expect(measured, 'the table rendered its scroll frame').not.toBeNull();
      return measured as { clientWidth: number; scrollWidth: number; widgetWidth: number };
    });
  }

  /**
   * Drags a column's right border horizontally by `deltaX` CSS pixels and
   * releases, the way a user resizes it.
   *
   * Negative narrows. The pointer travel is what TanStack measures — it tracks
   * `clientX` from mousedown, not the border's drawn position — so the declared
   * width moves by exactly `deltaX`, even though the border on screen moves
   * further than the cursor does on a stretched table.
   *
   * Moved in steps rather than in one jump because the resize is driven by
   * `mousemove`, and released with a document-level `mouseup`, which is what
   * `usePersistColumnWidths` listens for before it hands the final widths to
   * the dashboard's auto-save. A single move would resize without ever
   * persisting.
   */
  async dragColumnBorderBy(columnId: string, deltaX: number): Promise<void> {
    return test.step(`Drag the "${columnId}" column border by ${deltaX}px`, async () => {
      const handle = this.header(columnId).locator('div.cursor-ew-resize');
      await expect(handle, `exactly one resize handle on the "${columnId}" column`).toHaveCount(1);
      const box = await handle.boundingBox();
      expect(box, `the "${columnId}" column's resize handle is on screen`).not.toBeNull();
      const { x, y, width, height } = box as { x: number; y: number; width: number; height: number };
      const startX = x + width / 2;
      const startY = y + height / 2;

      await this.page.mouse.move(startX, startY);
      await this.page.mouse.down();
      const steps = 10;
      for (let step = 1; step <= steps; step++) {
        await this.page.mouse.move(startX + (deltaX * step) / steps, startY);
      }
      await this.page.mouse.up();
    });
  }
}
