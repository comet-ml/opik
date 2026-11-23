import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * The shared `FiltersButton` as the Experiments list wears it since opik#9691
 * (OPIK-9252) — the `layout="add"` variant, which is a pill-shaped "+ Add
 * filter" chip while no filter is applied and the funnel-with-count once one is.
 *
 * Its own class rather than methods on `ExperimentsPage`, because the component
 * has TWO opted-in call sites — the project Experiments page's Datasets tab and
 * the prompt detail page's Experiments tab — and the whole risk of changing a
 * shared component is that the two drift. One driver used from both specs is
 * what makes them assertions about the same control.
 *
 * **The two call sites differ in copy, in two places that do not track each
 * other.** Both strings follow from one prop, `itemSourceLabel`, and they
 * follow from it differently:
 *
 *  - The Experiments page passes `itemSourceLabel: "Dataset"`. That relabels
 *    the filter column AND adds a `placeholder: "Select a dataset"` to the
 *    row's `keyComponentProps`, which is spread over `StringRow`'s own props
 *    and so replaces them.
 *  - The prompt tab passes no label. Its column falls back to "Item source",
 *    but with no `placeholder` in `keyComponentProps` there is nothing to
 *    override `StringRow`'s generic `placeholder="value"` — so the picker's
 *    accessible name is the literal string `value`, not anything resembling
 *    the column's label.
 *
 * So the placeholder is NOT derivable from the label, and both are explicit
 * constructor arguments. A driver that derived one from the other would time
 * out on the prompt tab and read as a product failure.
 */
export class ExperimentsFilterControl {
  private readonly itemSourceLabel: string;
  private readonly valuePlaceholder: string;

  /**
   * @param copy.itemSourceLabel The item-source column's label on THIS page —
   *   "Dataset" on the Experiments page, "Item source" on the prompt tab.
   * @param copy.valuePlaceholder The dataset picker's placeholder on THIS page.
   *   Independent of the label; see the class comment.
   */
  constructor(
    private readonly page: Page,
    copy: { itemSourceLabel: string; valuePlaceholder: string },
  ) {
    this.itemSourceLabel = copy.itemSourceLabel;
    this.valuePlaceholder = copy.valuePlaceholder;
  }

  /**
   * The Group control, whose accessible name carries the active group count.
   *
   * Here as the toolbar's anchor as well as a subject in its own right: it is
   * the one control in the left-hand toolbar group with a stable accessible
   * name in every state, and `toolbar` is defined as its parent.
   */
  get groupsButton(): Locator {
    return this.page.getByRole('button', { name: /^Group \(\d+\)$/ });
  }

  /**
   * The left-hand toolbar group: Search, then Group, then the filter control.
   *
   * Addressed as the Group button's PARENT rather than by a CSS path or a
   * testid it does not have. That holds because every element between the flex
   * container and the `<button>` is transparent to the DOM: `TooltipWrapper`
   * returns a bare fragment when it has no content, and both Radix's
   * `PopoverTrigger` and `TooltipTrigger` are `asChild`, so each control's
   * `<button>` really is a direct child of the container in every state.
   *
   * Scoping matters for one specific reason: the filters popover contains a
   * SECOND button also labelled "Add filter" (the one that appends another
   * filter row). The popover is portaled out of the page body, so anything
   * scoped to this toolbar cannot match it — which is what lets the chip be
   * addressed by its label without the two colliding.
   */
  get toolbar(): Locator {
    return this.groupsButton.locator('..');
  }

  /**
   * The "+ Add filter" chip — the control's no-filter state.
   *
   * By its accessible name, which is the text the chip renders; the leading
   * plus is an unlabelled icon. Scoped to the toolbar, so the popover's
   * same-named button cannot match (see `toolbar`).
   */
  get addFilterChip(): Locator {
    return this.toolbar.getByRole('button', { name: 'Add filter', exact: true });
  }

  /**
   * The funnel-with-count — the control's filtered state.
   *
   * Addressed by the funnel ICON, because in this state the button's only text
   * is the count itself: its accessible name is "1", which is no identity at
   * all, and a name-based locator would have to encode the very number the
   * caller is trying to assert. The icon is an identity rather than a position,
   * and the estate already addresses a Lucide icon this way where the DOM
   * offers nothing better (see `ExperimentsPage.groupIsExpanded`). A
   * `data-testid` on the control would be the better handle and belongs in the
   * frontend — but a release-time spec runs against a DEPLOYED build, so one
   * added here would not exist in the bundle under test.
   */
  get filterFunnel(): Locator {
    return this.toolbar.locator('button:has(svg.lucide-filter)');
  }

  /** The filters popover, which Radix renders as a portaled dialog. */
  get popover(): Locator {
    return this.page.getByRole('dialog').filter({ has: this.page.getByText('Clear all') });
  }

  /**
   * Assert the control is in its CHIP state, and that the funnel is not beside
   * it.
   *
   * Both halves, because they are different failures: the chip missing is the
   * feature absent, and the chip present alongside a funnel is the component
   * rendering both states at once. `toHaveCount(1)` rather than `toBeVisible`,
   * so a second chip fails loudly instead of resolving to the first.
   *
   * Only meaningful with the popover CLOSED — see `toolbar` for why.
   */
  async expectChipState(): Promise<void> {
    return test.step('the filter control shows the "+ Add filter" chip', async () => {
      await expect(this.addFilterChip, 'the "Add filter" chip').toHaveCount(1);
      await expect(this.addFilterChip).toBeVisible();
      await expect(this.filterFunnel, 'the funnel, which must not be rendered too').toHaveCount(0);
    });
  }

  /**
   * Assert the control has COLLAPSED to the funnel reading `count`, and that
   * the chip is gone.
   *
   * The count is asserted as the button's whole text, not a substring: the
   * funnel renders nothing else, so an equality is available and `11` must not
   * satisfy a check for `1`.
   */
  async expectFunnelState(count: number): Promise<void> {
    return test.step(`the filter control has collapsed to the funnel reading ${count}`, async () => {
      await expect(this.filterFunnel, 'the filter funnel').toHaveCount(1);
      await expect(this.filterFunnel, "the funnel's applied-filter count").toHaveText(
        String(count),
      );
      await expect(this.addFilterChip, 'the chip, which must be gone').toHaveCount(0);
    });
  }

  /**
   * Assert the Group control precedes the filter control in DOM order.
   *
   * The last thing opik#9691 changed, and the only one that is purely about
   * order: the prompt tab used to render Filters before Group, and the PR
   * swapped them so both pages agree. Compared with
   * `compareDocumentPosition` rather than by reading bounding boxes, because
   * the claim is about the document — a flex container could be reversed
   * visually while the DOM order (which is what a keyboard and a screen reader
   * follow) stayed wrong, and a coordinate comparison would call that a pass.
   */
  async expectGroupBeforeFilterControl(): Promise<void> {
    return test.step('Group precedes the filter control in DOM order', async () => {
      const group = this.groupsButton;
      await expect(group, 'the Group control').toHaveCount(1);
      // Whichever state the filter control is in; exactly one of the two.
      const filterControl = this.addFilterChip.or(this.filterFunnel);
      await expect(filterControl, 'the filter control').toHaveCount(1);

      const groupPrecedes = await group.evaluate((groupEl, otherEl) => {
        // DOCUMENT_POSITION_FOLLOWING (4) means `otherEl` comes after `groupEl`.
        // eslint-disable-next-line no-bitwise
        return Boolean(groupEl.compareDocumentPosition(otherEl as Node) & 4);
      }, await filterControl.elementHandle());

      expect(
        groupPrecedes,
        'the Group control must come before the filter control in the DOM',
      ).toBe(true);
    });
  }

  /** Open the filters popover from whichever state the control is in. */
  async open(): Promise<void> {
    return test.step('open the filters popover', async () => {
      const trigger = this.addFilterChip.or(this.filterFunnel);
      await expect(trigger, 'the filter control to open').toHaveCount(1);
      await trigger.click();
      await expect(this.popover, 'the filters popover').toBeVisible();
    });
  }

  /**
   * Close the popover by committing it.
   *
   * Clicking outside, never Escape: this popover COMMITS on click-outside and
   * DISCARDS on Escape, so Escape would silently undo the filter the caller
   * just built. The page heading is inert and always present, which is the same
   * route `CompareExperimentsPage.applyGridFilter` takes.
   */
  async commitAndClose(): Promise<void> {
    return test.step('commit the popover by clicking outside it', async () => {
      await this.page.getByRole('heading').first().click();
      await expect(this.popover, 'the filters popover after clicking outside').toBeHidden();
    });
  }

  /**
   * Fill the popover's one filter row with `<item source> = <datasetName>`,
   * and resolve once the server has answered the narrowed read.
   *
   * The value is CHOSEN, not typed: the item-source row's value slot is a
   * `DatasetSelectBox` over `LoadableSelectBox`, so there is no debounce to
   * race and no partial-value request to settle on. The option list is itself
   * part of what this exercises — the picker is scoped to the page's project
   * and dataset type, so an item source from elsewhere is not offered.
   *
   * `expectDatasetId` is the id the row must put ON THE WIRE, and passing it is
   * not restating the caller's intent: it is the predicate the response is
   * matched on, so a picker that resolved the wrong dataset times out here
   * rather than letting the spec assert rows for a filter nobody asked for.
   */
  async filterByItemSource(args: {
    datasetName: string;
    expectedDatasetId: string;
  }): Promise<void> {
    return test.step(`filter by ${this.itemSourceLabel} = "${args.datasetName}"`, async () => {
      // Armed before the choice that applies the filter: the row completes the
      // moment the value is picked, so a waiter armed afterwards can miss the
      // only response there is.
      const narrowedRead = this.page.waitForResponse(
        (response) => {
          const url = new URL(response.url());
          if (!url.pathname.endsWith('/experiments')) return false;
          const filters = url.searchParams.get('filters');
          if (filters === null) return false;
          const decoded = decodeURIComponent(filters);
          return (
            decoded.includes('"field":"dataset_id"') &&
            decoded.includes(`"value":"${args.expectedDatasetId}"`) &&
            response.ok()
          );
        },
        { timeout: 30_000 },
      );

      const columnSelect = this.popover.locator(
        'button[role="combobox"]:has([data-testid="filter-column"])',
      );
      await expect(columnSelect, 'exactly one filter row to fill in').toHaveCount(1);
      await columnSelect.click();
      await this.page
        .getByRole('option', { name: this.itemSourceLabel, exact: true })
        .click();

      // The item-source row offers `=` only and defaults to it, so the operator
      // is deliberately left alone: clicking through a single-option dropdown
      // would assert nothing and could only break.
      const operatorSelect = this.popover.locator(
        'button[role="combobox"]:has([data-testid="filter-operator"])',
      );
      await expect(operatorSelect, 'exactly one operator selector').toHaveCount(1);
      await expect(operatorSelect, "the item source row's only operator").toHaveText('=');

      // The dataset picker's trigger, by the placeholder it shows while nothing
      // is selected — which is this page's own string and not derivable from
      // its column label (see the class comment).
      const valuePicker = this.popover.getByRole('button', {
        name: this.valuePlaceholder,
        exact: true,
      });
      await expect(valuePicker, `the ${this.itemSourceLabel} value picker`).toHaveCount(1);
      await valuePicker.click();

      const option = this.page.getByRole('option', { name: args.datasetName, exact: true });
      await expect(option, `"${args.datasetName}" offered in the picker`).toHaveCount(1);
      await option.click();

      await narrowedRead;
    });
  }

  /**
   * Clear every applied filter through the popover's "Clear all".
   *
   * Resolves once the server has answered a read carrying NO `filters` param,
   * which is the only evidence that the clear reached the wire rather than
   * merely emptying the popover's local state.
   */
  async clearAll(): Promise<void> {
    return test.step('clear every filter', async () => {
      const unfilteredRead = this.page.waitForResponse(
        (response) => {
          const url = new URL(response.url());
          return (
            url.pathname.endsWith('/experiments') &&
            url.searchParams.get('filters') === null &&
            response.ok()
          );
        },
        { timeout: 30_000 },
      );
      await this.popover.getByRole('button', { name: 'Clear all', exact: true }).click();
      await this.commitAndClose();
      await unfilteredRead;
    });
  }
}
