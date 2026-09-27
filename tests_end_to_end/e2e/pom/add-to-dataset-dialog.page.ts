import { test, expect, type Page, type Locator } from '@playwright/test';

/**
 * The "Add to → Dataset" dialog opened from the Logs table
 * (`AddToDatasetDialog`), including its Advanced mapping section.
 *
 * The dialog morphs from a modal into a side panel when Advanced mapping is
 * switched on (`MorphDialog`), but it stays one Radix dialog throughout, so
 * everything here is scoped to that single `role="dialog"` rather than to
 * whichever shape it is currently wearing.
 */
export class AddToDatasetDialogPage {
  constructor(private readonly page: Page) {}

  /**
   * The dialog itself.
   *
   * Filtered by its title because the Logs toolbar mounts three sibling
   * dialogs — Test suite, Dataset and Annotation queue — from one dropdown.
   * Only the open one is portalled into the DOM, but naming the title keeps a
   * mis-click on the dropdown from silently driving the wrong dialog.
   */
  get root(): Locator {
    return this.page.getByRole('dialog').filter({ hasText: 'Add to dataset' });
  }

  /**
   * The dataset picker's trigger, addressed by the name it currently shows.
   *
   * `LoadableSelectBox` renders an unlabelled button whose accessible name is
   * the selected dataset (or the placeholder), which makes the dataset's own
   * name the stable handle — there is no testid and no associated `<label>`.
   */
  datasetPickerTrigger(name: string): Locator {
    return this.root.getByRole('button', { name, exact: false });
  }

  /** Wait until the picker is showing `name`, then assert it is. */
  async expectDatasetSelected(name: string): Promise<void> {
    return test.step(`the dialog is targeting dataset "${name}"`, async () => {
      await expect(this.datasetPickerTrigger(name)).toBeVisible();
    });
  }

  /** The Advanced mapping switch (`Switch id="advanced-mapping"` + its `<label>`). */
  get advancedMappingSwitch(): Locator {
    return this.root.getByRole('switch', { name: 'Advanced mapping' });
  }

  /**
   * Turn Advanced mapping on and wait for the switch itself to report it.
   *
   * Gated on `aria-checked` rather than on a mapping row appearing: the
   * modal→panel morph cross-fades the previous frame for ~130ms, so a row
   * locator can resolve against the frozen frame and pass before the switch
   * has actually flipped.
   */
  async enableAdvancedMapping(): Promise<void> {
    return test.step('Turn on Advanced mapping', async () => {
      await this.advancedMappingSwitch.click();
      await expect(
        this.advancedMappingSwitch,
        'the Advanced mapping switch is on',
      ).toHaveAttribute('aria-checked', 'true');
    });
  }

  /**
   * One mapping row, keyed by the dataset-item field it writes.
   *
   * `data-testid="mapping-row-<id>"`, and for the two fixed rows the id IS the
   * field name (`input`, `expected_output`) — see `FIXED_MAPPING_FIELDS`.
   */
  mappingRow(fieldName: string): Locator {
    return this.root.getByTestId(`mapping-row-${fieldName}`);
  }

  /** The source-path button inside a mapping row; its text is the current path. */
  mappingRowSource(fieldName: string): Locator {
    return this.mappingRow(fieldName).getByTestId('path-source-trigger');
  }

  /**
   * Re-point a mapping row at `path`, driving the same JSON tree a user does.
   *
   * `parents` are expanded in order first: the tree only renders a node's
   * children once its parent is expanded, and the popover opens expanded only
   * as far as the row's *current* path. Each node carries
   * `data-testid="json-tree-node-<path>"`, so both the chevron and the leaf are
   * addressed by the path they stand for rather than by position.
   */
  async setMappingSource(
    fieldName: string,
    path: string,
    parents: string[] = [],
  ): Promise<void> {
    return test.step(`Map "${fieldName}" to ${path}`, async () => {
      await this.mappingRowSource(fieldName).click();
      for (const parent of parents) {
        await this.page.getByTestId(`json-tree-node-${parent}`).getByRole('button').click();
      }
      await this.page.getByTestId(`json-tree-node-${path}`).click();
      await expect(
        this.mappingRowSource(fieldName),
        `the "${fieldName}" row now reads from ${path}`,
      ).toHaveText(path);
    });
  }

  /**
   * A cell of the Preview table, by the entity it previews and the field it
   * maps — the shared `DataTable` stamps `data-cell-id="<rowId>_<columnId>"`,
   * and `MappingPreviewSection` uses the trace/span id as the row id and the
   * dataset-item field name as the column id.
   */
  previewCell(entityId: string, fieldName: string): Locator {
    return this.root.locator(`[data-cell-id="${entityId}_${fieldName}"]`);
  }

  /**
   * Assert what one Preview cell renders.
   *
   * The count is asserted first rather than taking `.first()`: a column
   * rendered twice would otherwise be silently reduced to whichever matched
   * first, and the assertion would report on a cell it never meant. The text
   * assertion retries, which is also what waits out the sample-entity fetch
   * behind the Preview's skeleton.
   */
  async expectPreviewCell(entityId: string, fieldName: string, text: string): Promise<void> {
    return test.step(`the Preview "${fieldName}" cell reads ${text}`, async () => {
      const cell = this.previewCell(entityId, fieldName);
      await expect(cell, `exactly one Preview cell for "${fieldName}"`).toHaveCount(1);
      await expect(cell).toHaveText(text);
    });
  }

  /**
   * The submit button. Its label counts the selected rows ("Add 1 item"), which
   * is also the only thing distinguishing it from "Add to dataset" in the
   * empty-selection state — so the count is part of the name on purpose.
   */
  submitButton(itemCount: number): Locator {
    return this.root.getByRole('button', {
      name: `Add ${itemCount} ${itemCount === 1 ? 'item' : 'items'}`,
      exact: true,
    });
  }

  /** Submit, and wait for the dialog to close. */
  async submit(itemCount: number): Promise<void> {
    return test.step(`Submit the dialog (${itemCount} item(s))`, async () => {
      await this.submitButton(itemCount).click();
      await expect(this.root, 'the dialog closes on submit').toBeHidden();
    });
  }
}
