import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';

/**
 * The read-only sheet a Version history row opens — the full-width panel that
 * serves ONE version's records without restoring it.
 *
 * One class for both pages on purpose: the dataset items page and the test
 * suite items page mount the same `VersionRecordsSidebar`, discriminated only
 * by `isTestSuite`, so the suite-only accessors below (`suiteAssertions`,
 * `executionPolicy`, the per-item assertion/policy cells) are the whole of the
 * difference. `DatasetItemsPage.openVersionRecords` and
 * `TestSuiteItemsPage.openVersionRecords` both hand one back.
 *
 * **Selector note.** The sheet carries no `data-testid` of its own, and this
 * POM ships on a QA branch that is not allowed to touch `apps/opik-frontend`,
 * so it cannot add one. It therefore takes the pom-discovery skill's documented
 * fallback: `getByRole('dialog')` with an explicit accessible name. The name is
 * matched on the trailing `Read-only` tag rather than on the whole title, for
 * two reasons — the title also carries the version name and the dataset name,
 * which are facts a spec should ASSERT rather than bake into the handle it
 * found the sheet with; and the only other dialog in play, the restore
 * confirmation, is named "Restore version", so the two never collide.
 */
export class VersionRecordsSidebarPage {
  constructor(private readonly page: Page) {}

  get root(): Locator {
    return this.page.getByRole('dialog', { name: /Read-only$/ });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for the version sheet to serve its rows', async () => {
      await this.root.waitFor({ state: 'visible' });
      const realRow = this.itemRows().first();
      // Both of the sheet's own terminal states, so a version that genuinely
      // holds nothing does not hang here, and a failed load says so rather
      // than timing out on a row that was never coming.
      const emptyOrError = this.root.getByText(
        /No (records|test cases) in this version|Couldn't load this version/,
      );
      await Promise.race([
        realRow.waitFor({ state: 'visible' }),
        emptyOrError.waitFor({ state: 'visible' }),
      ]);
    });
  }

  /**
   * The sheet's title, which reads `<version name> · <dataset name>`.
   *
   * Exposed as the heading rather than as the dialog's accessible name so a
   * spec can assert the two halves it is made of; the "Read-only" tag beside it
   * is `readOnlyTag()`.
   */
  title(): Locator {
    return this.root.getByRole('heading');
  }

  readOnlyTag(): Locator {
    return this.title().getByText('Read-only', { exact: true });
  }

  /**
   * The meta line under the title: creator, item total and the
   * `+added ~modified −deleted` counters of the OPENED version.
   *
   * The counters are the part worth asserting — they come from the version the
   * sheet loaded by hash, so a sheet showing the latest version's numbers
   * beside an older version's rows is visible here and nowhere else.
   */
  meta(): Locator {
    return this.root.getByText(/· \d+ items ·/);
  }

  /** Suite-only: the "Suite assertions" list items of the opened version. */
  suiteAssertions(): Locator {
    return this.sheetSection('Suite assertions').getByRole('listitem');
  }

  /** Suite-only: the opened version's "Execution policy", e.g. `1 of 2 must pass`. */
  executionPolicy(): Locator {
    return this.sheetSection('Execution policy');
  }

  /** Every rendered row of the sheet's own table. */
  itemRows(): Locator {
    return this.root.locator('tbody tr[data-row-id]');
  }

  itemRowById(id: string): Locator {
    return this.root.locator(`tbody tr[data-row-id="${id}"]`);
  }

  /**
   * The item ids the sheet is currently rendering, in row order.
   *
   * Read from `data-row-id` rather than from cell text because identity is the
   * question: a spec comparing the sheet against the version's own
   * `?version=<hash>` read is asking whether these are the same rows, and the
   * visible columns are user-configurable. Single page only — the sheet
   * paginates at 10.
   */
  async itemRowIds(): Promise<string[]> {
    return test.step('Read the version sheet row ids', async () => {
      return this.itemRows().evaluateAll((rows) =>
        rows.map((r) => r.getAttribute('data-row-id') ?? ''),
      );
    });
  }

  /**
   * One row's cell for one of the item's data fields.
   *
   * Addressed by the table's own `data-cell-id` (`<rowId>_<columnId>`), with
   * the same `data.<field>` → `data_<field>` normalisation
   * `DatasetItemsPage.itemCell` documents at length: the FE hands TanStack an
   * `accessorKey` of `data.<field>` with no explicit id, so every dot in it
   * becomes an underscore. The whole id is escaped because a dataset field name
   * is user data and any JSON key is legal.
   */
  itemCell(itemId: string, field: string): Locator {
    const columnId = `data.${field}`.replace(/\./g, '_');
    const cellId = `${itemId}_${columnId}`.replace(/["\\]/g, '\\$&');
    return this.itemRowById(itemId).locator(`[data-cell-id="${cellId}"]`);
  }

  /**
   * One row's suite-only cell — `assertions` (the per-item count) or
   * `execution_policy` (the item's own policy, or the suite's in grey).
   */
  suiteCell(itemId: string, column: 'assertions' | 'execution_policy'): Locator {
    return this.itemRowById(itemId).locator(`[data-cell-id="${itemId}_${column}"]`);
  }

  /** The sheet's pagination footer, e.g. `Showing 1-3 of 3`. */
  showingLabel(): Locator {
    return this.root.getByText(/Showing \d+-\d+ of \d+/);
  }

  /**
   * Open one row's read-only detail panel, by item id.
   *
   * Asserts the row resolved to exactly one element before clicking, so an
   * ambiguous id fails here rather than silently opening a different item's
   * panel.
   */
  async openItem(itemId: string): Promise<void> {
    return test.step(`Open the version sheet's row ${itemId}`, async () => {
      const row = this.itemRowById(itemId);
      await expect(row).toHaveCount(1);
      await row.click();
      await this.waitForPanelRow(itemId);
      await this.panelSection('Data').first().waitFor({ state: 'visible' });
    });
  }

  itemPanel(): Locator {
    return this.page.getByTestId('dataset-version-item');
  }

  /**
   * One titled section of the open item panel — "Data", "Assertions",
   * "Execution policy", "Description".
   *
   * Structural CSS, deliberately and with no alternative: the panel's sections
   * are a shared `Section` component rendering `<div><div>{title}</div>…</div>`
   * with no testid and no landmark role, and this POM may not add one to the
   * frontend (see the class comment). Scoping by the section's own title text
   * through `:has(> div:text-is(...))` is at least addressed by identity — it
   * cannot drift onto a sibling section the way an `nth()` would — and it keeps
   * a spec from asserting that a policy string is merely "somewhere in the
   * panel" when what matters is which section it is under.
   */
  panelSection(title: string): Locator {
    return this.itemPanel().locator(
      `div:has(> div:text-is(${JSON.stringify(title)}))`,
    );
  }

  async closeItemPanel(): Promise<void> {
    return test.step('Close the version sheet item panel', async () => {
      await this.itemPanel().getByRole('button', { name: 'Close' }).click();
      await this.waitForPanelRow('');
    });
  }

  restoreButton(): Locator {
    return this.root.getByRole('button', { name: 'Restore this version' });
  }

  /** The confirmation the restore button raises. Named, so it never matches the sheet. */
  restoreDialog(): Locator {
    return this.page.getByRole('dialog', { name: 'Restore version' });
  }

  /**
   * Restore the opened version: click the sheet's button, confirm, and wait for
   * the sheet to close.
   *
   * The dialog is left for the caller to assert on BEFORE confirming — which
   * version it names is the thing a mis-wired restore gets wrong — so this
   * method only confirms an already-open dialog.
   */
  async confirmRestore(): Promise<void> {
    return test.step('Confirm the restore', async () => {
      await this.restoreDialog().getByRole('button', { name: 'Restore version' }).click();
      await this.restoreDialog().waitFor({ state: 'hidden' });
      await this.root.waitFor({ state: 'hidden' });
    });
  }

  async close(): Promise<void> {
    return test.step('Close the version sheet', async () => {
      await this.root.getByRole('button', { name: 'Close' }).first().click();
      await this.root.waitFor({ state: 'hidden' });
    });
  }

  /**
   * The `dvs_*` query params the sheet owns — its version, row, page, search,
   * filters, size and height.
   *
   * The sheet keeps its whole state in this namespace precisely so it cannot
   * disturb the Records tab's own params, and closing it clears every one. A
   * spec asserting that needs the set, not one key.
   */
  dvsParams(): Record<string, string> {
    const params = new URL(this.page.url()).searchParams;
    return Object.fromEntries(
      [...params.entries()].filter(([key]) => key.startsWith('dvs_')),
    );
  }

  /**
   * One titled block of the sheet's meta header — "Suite assertions",
   * "Execution policy".
   *
   * Same structural-CSS reasoning as `panelSection`, against the same
   * untouchable frontend: the block is a `<div><span>{label}</span><…/></div>`
   * pair with no testid. Addressed by its own label text so it cannot drift.
   *
   * `:not([data-header-wrapper])` is load-bearing, not defensive. The suite
   * table has a COLUMN called "Execution policy", and its header renders the
   * same `<div><span>Execution policy</span></div>` shape — so without this the
   * locator matches the meta block and the column header both, and an
   * assertion on the version's policy becomes a strict-mode violation instead
   * of a comparison.
   */
  /**
   * Wait until the sheet's own row param says what it should — `itemId` when a
   * panel has been opened, `''` when it has been closed.
   *
   * The panel is not mounted and unmounted: `ResizableSidePanel` stays in the
   * DOM and slides, so its container is permanently "visible" to Playwright
   * even when it holds nothing, and a `waitFor({ state: 'hidden' })` on it
   * never resolves. `dvs_row` is the state the panel is actually driven from,
   * so it is what to wait on. An absent param and an empty one are the same
   * closed state — `setActiveRowId('')` produces either depending on how the
   * query serialiser collapses it.
   */
  private async waitForPanelRow(expected: string): Promise<void> {
    await this.page.waitForFunction(
      (want) => (new URL(window.location.href).searchParams.get('dvs_row') ?? '') === want,
      expected,
    );
  }

  private sheetSection(label: string): Locator {
    return this.root.locator(
      `div:has(> span:text-is(${JSON.stringify(label)})):not([data-header-wrapper])`,
    );
  }
}
