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

  /**
   * A row for a column the TARGET DATASET already has, listed under "Additional
   * fields".
   *
   * `useFieldMappings` namespaces these row ids `existing:<column>` to keep
   * them apart from a custom row a user named the same thing, so the id is not
   * the field name and `mappingRow()` cannot address them. The namespace is
   * also what makes this locator discriminating: a row matching it came from
   * the dataset's own column list, not from the fixed fields, the quick-add
   * chips or anything the user typed.
   */
  existingMappingRow(column: string): Locator {
    return this.root.getByTestId(`mapping-row-existing:${column}`);
  }

  /**
   * A row Opik fills itself, keyed by the enrichment option that produced it
   * (`includeMetadata`, `includeTags`, …) — which `useFieldMappings` uses
   * verbatim as the row id.
   */
  managedMappingRow(option: string): Locator {
    return this.root.getByTestId(`mapping-row-${option}`);
  }

  /**
   * Any REMOVABLE mapping row, addressed by the field name its remove control
   * carries (`aria-label="Remove <field>"`).
   *
   * Needed because re-pointing an Opik-managed row changes its id: the row
   * stops being managed and becomes a custom one with a freshly minted
   * `custom-N` id, so neither `managedMappingRow` nor any id-based locator
   * still resolves it. The field NAME is the one thing the re-point preserves.
   *
   * Not usable for the two fixed rows (`input`, `expected_output`): they render
   * no remove control at all, which is exactly what `mappingRow` is for.
   */
  mappingRowNamed(fieldName: string): Locator {
    return this.root
      .getByTestId(/^mapping-row-/)
      .filter({
        has: this.page.getByRole('button', { name: `Remove ${fieldName}`, exact: true }),
      });
  }

  /** The source-path button inside a mapping row; its text is the current path. */
  mappingRowSource(fieldName: string): Locator {
    return this.rowSource(this.mappingRow(fieldName));
  }

  /** The source-path button of an arbitrary mapping row. */
  rowSource(row: Locator): Locator {
    return row.getByTestId('path-source-trigger');
  }

  /**
   * The remove control of an arbitrary mapping row.
   *
   * `FieldRowShell` renders the same button whether or not the row may be
   * removed — a disabled one when the caller passed a `removeDisabledReason`
   * instead of an `onRemove` — so "listed but not removable" is a state this
   * locator can assert rather than one that reads as a missing button.
   */
  rowRemoveButton(row: Locator, fieldName: string): Locator {
    return row.getByRole('button', { name: `Remove ${fieldName}`, exact: true });
  }

  /**
   * Re-point a mapping row at `path`, driving the same JSON tree a user does.
   *
   * For a row whose id IS its field name — the two fixed rows. See
   * `setRowSource` for the rest, and `selectTreePath` for how `parents` works.
   */
  async setMappingSource(
    fieldName: string,
    path: string,
    parents: string[] = [],
  ): Promise<void> {
    return test.step(`Map "${fieldName}" to ${path}`, async () => {
      await this.setRowSource(this.mappingRow(fieldName), fieldName, path, parents);
    });
  }

  /**
   * The same re-point against an arbitrary row, for the rows whose id is not
   * their field name (`existing:<column>`, a re-pointed managed row).
   *
   * The row is asserted to resolve to exactly one element before it is driven:
   * a locator that matched two rows would otherwise click whichever came first
   * and then assert about it, reporting on a mapping the caller never named.
   */
  async setRowSource(
    row: Locator,
    fieldName: string,
    path: string,
    parents: string[] = [],
  ): Promise<void> {
    return test.step(`Map "${fieldName}" to ${path}`, async () => {
      await expect(row, `exactly one "${fieldName}" mapping row`).toHaveCount(1);
      await this.rowSource(row).click();
      await this.selectTreePath(path, parents);
      await expect(
        this.rowSource(row),
        `the "${fieldName}" row now reads from ${path}`,
      ).toHaveText(path);
    });
  }

  /**
   * Use the picker's "Leave unmapped" link to clear a row's path.
   *
   * Only rows the dialog hands an `onPathClear` offer it, and only once a path
   * is already set — `PathSourcePicker` renders the link on `onClear && path`.
   * So this is the one control that distinguishes "a dataset column the user
   * chose not to fill" from "a column that was never mapped", and the
   * `Not mapped` placeholder it falls back to is that state's only rendering.
   */
  async leaveRowUnmapped(row: Locator, fieldName: string): Promise<void> {
    return test.step(`Leave "${fieldName}" unmapped`, async () => {
      await expect(row, `exactly one "${fieldName}" mapping row`).toHaveCount(1);
      await this.rowSource(row).click();
      // Page-scoped, like the tree nodes below: the picker's popover is
      // portalled to the body, outside the dialog this POM is rooted at.
      await this.page.getByRole('button', { name: 'Leave unmapped', exact: true }).click();
      await expect(
        this.rowSource(row),
        `the "${fieldName}" row reads back as unmapped`,
      ).toHaveText('Not mapped');
    });
  }

  /**
   * Re-point an Opik-managed row at `path`, the way a user replaces Opik's own
   * enrichment for that field.
   *
   * Two things happen at once. The row stops being managed — it becomes a
   * custom row with a new id, now showing the path instead of
   * "<label> · filled by Opik" — and the enrichment option behind it is
   * switched off, which is what makes the submitted request carry
   * `include_<option>: false`. Only the first is visible on the row, so a
   * caller has to assert the second against the request the dialog sends.
   */
  async remapManagedRow(
    option: string,
    fieldName: string,
    path: string,
    parents: string[] = [],
  ): Promise<void> {
    return test.step(`Re-point Opik's "${fieldName}" at ${path}`, async () => {
      const managed = this.managedMappingRow(option);
      await expect(managed, `exactly one Opik-filled row for ${option}`).toHaveCount(1);
      await this.rowSource(managed).click();
      await this.selectTreePath(path, parents);

      // Addressed by field name from here on: the re-point replaced the row's
      // id, so `managed` no longer resolves.
      const row = this.mappingRowNamed(fieldName);
      await expect(row, `exactly one "${fieldName}" row after the re-point`).toHaveCount(1);
      await expect(
        this.rowSource(row),
        `the "${fieldName}" row now reads from ${path}`,
      ).toHaveText(path);
    });
  }

  /**
   * Pick `path` out of the open JSON tree popover.
   *
   * `parents` are expanded in order first: the tree only renders a node's
   * children once its parent is expanded, and the popover opens expanded only
   * as far as the row's *current* path. Each node carries
   * `data-testid="json-tree-node-<path>"`, so both the chevron and the leaf are
   * addressed by the path they stand for rather than by position.
   */
  private async selectTreePath(path: string, parents: string[]): Promise<void> {
    for (const parent of parents) {
      await this.page.getByTestId(`json-tree-node-${parent}`).getByRole('button').click();
    }
    await this.page.getByTestId(`json-tree-node-${path}`).click();
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
   * The submit button. Its label counts the selected rows ("Add 1 trace"), which
   * is also the only thing distinguishing it from "Add to dataset" in the
   * empty-selection state — so the count is part of the name on purpose.
   */
  submitButton(itemCount: number, entity: 'trace' | 'span' = 'trace'): Locator {
    return this.root.getByRole('button', {
      name: `Add ${itemCount} ${itemCount === 1 ? entity : `${entity}s`}`,
      exact: true,
    });
  }

  /** Submit, and wait for the dialog to close. */
  async submit(itemCount: number, entity: 'trace' | 'span' = 'trace'): Promise<void> {
    return test.step(`Submit the dialog (${itemCount} ${entity}(s))`, async () => {
      await this.submitButton(itemCount, entity).click();
      await expect(this.root, 'the dialog closes on submit').toBeHidden();
    });
  }
}
