import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * The "way back to the experiment" controls an items page renders when it was
 * reached from one (OPIK-8600).
 *
 * ONE class for both items pages on purpose: the dataset items page and the
 * test-suite items page are the same React component (`DatasetDetailPage`,
 * discriminated by URL prefix), and the two controls modelled here —
 * `DatasetItemsPageHeader`'s back button and `ViewInExperimentButton` — are
 * literally the same components in both. Modelling them twice would let the two
 * copies drift apart while the product's single implementation could not.
 *
 * What DOES differ between the two pages is which side panel the Experiment
 * button sits in (`dataset-item-editor` vs `test-suite-item-panel`), so the
 * panel is a constructor argument rather than something this class knows. The
 * routes differ too, and those stay with the page objects that own them —
 * `DatasetItemsPage.gotoWithReturn` and `TestSuiteItemsPage.gotoWithReturn`.
 */
export class ItemReturnNav {
  /**
   * @param panel The OPEN item side panel. Both pages mount two elements under
   *   the same testid (an Add panel and a detail panel), so the caller is
   *   expected to hand over the disambiguated one.
   */
  constructor(
    private readonly page: Page,
    private readonly panel: Locator,
  ) {}

  /**
   * The header back button.
   *
   * `BackButton` renders an icon-only `<a>` with no testid, no `aria-label` and
   * no text — its only label is a hover tooltip, which contributes nothing to
   * the accessible name — so neither a testid nor a role+name lookup can reach
   * it. Identified instead by the icon that IS its identity: a back button is
   * the link whose icon is an arrow pointing left. That is a class selector,
   * the house's documented last resort, and it is used here because this branch
   * may not touch `apps/opik-frontend/`; a `data-testid` on `BackButton` is the
   * right fix and belongs in a front-end change.
   *
   * Deliberately page-scoped and asserted to resolve to exactly one element by
   * every method below: the lucide class would also match an arrow-left icon
   * elsewhere on the page, and a silent `.first()` could then read a different
   * control's tooltip.
   */
  get backButton(): Locator {
    return this.page.locator('a:has(svg.lucide-arrow-left)');
  }

  /**
   * The back button's tooltip text — "Back to experiment" when the page
   * honoured its `from`, the page's own default otherwise.
   *
   * **Requires the item side panel to be CLOSED.** The panel lays a
   * full-viewport scrim over the header, so a hover with it open never reaches
   * the button and times out with no hint as to why. Callers that need both the
   * tooltip and the panel load the page twice, once without `row`.
   */
  async expectBackTooltip(expected: string): Promise<void> {
    await test.step(`the back button's tooltip reads "${expected}"`, async () => {
      await expect(this.backButton, 'exactly one back button in the header').toHaveCount(1);
      await this.backButton.hover();
      // Radix portals the content out of the header, so it is looked up at page
      // scope by role; `TooltipWrapper` opens on a timer, so the wait is on the
      // tooltip appearing rather than on a fixed delay. Asserted as exactly one
      // so a second tooltip left open elsewhere cannot be read as this one's.
      const tooltip = this.page.getByRole('tooltip');
      await expect(tooltip, 'exactly one tooltip raised by the back button').toHaveCount(1, {
        timeout: 10_000,
      });
      // An auto-retrying assertion rather than a one-shot read, for the reason
      // spelled out on `expectExperimentTooltip`: these labels are what a
      // reader of a failure needs named, and retrying is what keeps a late
      // render from reading as a wrong label. This one has no fetch behind it,
      // so it settles on the first attempt — the shape is shared so the two
      // tooltip checks cannot drift apart.
      await expect(tooltip, "the back button's tooltip").toHaveText(expected, {
        timeout: 15_000,
      });
    });
  }

  /** Where the back button points, as the `href` attribute carries it. */
  async readBackHref(): Promise<string> {
    return test.step('read the back button href', async () => {
      await expect(this.backButton, 'exactly one back button in the header').toHaveCount(1);
      const href = await this.backButton.getAttribute('href');
      // Asserted rather than defaulted: a back button rendered without an href
      // is a real defect (it looks identical and navigates nowhere), and an
      // empty-string fallback here would turn it into a confusing URL
      // comparison further down instead of naming it.
      expect(href, 'the back button carries an href').not.toBeNull();
      return href as string;
    });
  }

  /** Follow the back button and settle on the compare route it lands on. */
  async clickBackToCompare(): Promise<void> {
    await test.step('click the back button', async () => {
      await expect(this.backButton, 'exactly one back button in the header').toHaveCount(1);
      await this.backButton.click();
      await this.page.waitForURL((url) => url.pathname.endsWith('/compare'), {
        timeout: 30_000,
      });
    });
  }

  /**
   * The side panel's "Experiment" button.
   *
   * Matched with a `^Experiment` prefix rather than exactly: the button renders
   * an `ArrowUpRight` icon after its label, and the accessible name picks up
   * whatever the icon contributes. Anchored at the start so it cannot match some
   * other control whose name merely contains the word.
   */
  get experimentButton(): Locator {
    return this.panel.getByRole('button', { name: /^Experiment/ });
  }

  /**
   * Assert the Experiment button's tooltip — "View this item in experiment(s):
   * <names>".
   *
   * Unlike the back button this one IS hoverable with the panel open; it lives
   * inside the panel, above the scrim.
   *
   * An assertion rather than a reader, and the distinction is load-bearing. The
   * button renders its tooltip from `useExperimentsByIds`, and until that fetch
   * resolves `experimentNames` is empty — so the component's own ternary falls
   * back to the NAMELESS "View this item in experiment", which is a real
   * rendered state and not an empty locator. A one-shot `innerText()` therefore
   * races the fetch and reports that transient label as the final answer; it
   * failed twice in three runs before this was an auto-retrying assertion.
   * `toHaveText` polls until the names arrive, and a genuinely wrong name still
   * fails with both the expected and the actual text named.
   */
  async expectExperimentTooltip(expected: string): Promise<void> {
    await test.step(`the Experiment button's tooltip reads "${expected}"`, async () => {
      await expect(this.experimentButton, 'exactly one Experiment button').toHaveCount(1);
      await this.experimentButton.hover();
      const tooltip = this.page.getByRole('tooltip');
      await expect(tooltip, 'exactly one tooltip raised by the Experiment button').toHaveCount(1, {
        timeout: 10_000,
      });
      await expect(tooltip, "the Experiment button's tooltip").toHaveText(expected, {
        timeout: 15_000,
      });
    });
  }

  /** Follow the Experiment button and settle on the compare route. */
  async clickExperiment(): Promise<void> {
    await test.step('click the Experiment button', async () => {
      await expect(this.experimentButton, 'exactly one Experiment button').toHaveCount(1);
      await this.experimentButton.click();
      await this.page.waitForURL((url) => url.pathname.endsWith('/compare'), {
        timeout: 30_000,
      });
    });
  }

  /**
   * Step the panel to the next item with the panel's own Next control, and
   * answer with the `row` the URL ended up on.
   *
   * `Next` specifically, and the caller is expected to have opened the panel on
   * the FIRST rendered row: which item sits in which row is the backend's to
   * order, but "the first row has a next one" holds for any ordering of two or
   * more rows, so this needs no branch on which arrow happens to be enabled.
   *
   * The settle is on `row` having CHANGED rather than on a particular value —
   * the caller asserts which item it became. Without the wait, a read taken
   * straight after the click can still report the row the panel arrived with,
   * which is indistinguishable from the "Experiment follows `from` instead of
   * the panel" defect the caller is testing for.
   */
  async stepToNextItem(currentRow: string): Promise<string> {
    return test.step('step the panel to the next item', async () => {
      const next = this.panel.getByRole('button', { name: /^Next/ });
      await expect(next, 'exactly one Next control in the panel').toHaveCount(1);
      await expect(next, 'the Next control is enabled on the first row').toBeEnabled();
      await next.click();
      await this.page.waitForFunction(
        (previous) => new URL(window.location.href).searchParams.get('row') !== previous,
        currentRow,
        { timeout: 15_000 },
      );
      const stepped = new URL(this.page.url()).searchParams.get('row');
      expect(stepped, 'the URL carries a row after stepping the panel').not.toBeNull();
      return stepped as string;
    });
  }
}

/**
 * A URL's query as a plain map, for comparing one view's search against
 * another's as a whole.
 *
 * Compared as a map rather than as a string because param ORDER is not part of
 * what a round trip owes: TanStack rebuilds the search from a parsed object, so
 * the same view can legitimately serialise its params in a different order than
 * the URL it was reconstructed from. Values are compared verbatim, encoding
 * resolved once by `URLSearchParams` — which is what makes a dropped filter or
 * a re-defaulted page size visible.
 */
export const searchParamMap = (search: string): Record<string, string> =>
  Object.fromEntries(new URLSearchParams(search).entries());

/**
 * An absolute URL reduced to the in-app href form `from` carries: path plus
 * query, no origin.
 *
 * Taken from the browser's own settled URL rather than rebuilt from the pieces
 * a spec navigated with, so the `from` a spec hands to the items page is the
 * same string the product's `useLocation().href` would have produced — params
 * the compare route adds for itself on mount included. Rebuilding it by hand
 * would make a round-trip assertion compare the view against a href no link
 * ever emits.
 */
export const toRelativeHref = (absoluteUrl: string): string => {
  const url = new URL(absoluteUrl);
  return `${url.pathname}${url.search}`;
};

/**
 * The `{ pathname, search }` an in-app `from` value points at.
 *
 * `from` is a path with its own query, carried inside another URL's query — so
 * reading it means decoding one layer and then parsing the result as a relative
 * URL. Resolved against a throwaway origin for exactly the reason the product's
 * own `parseExperimentReturnHref` does: `new URL` needs a base for a relative
 * href, and the origin is then discarded.
 */
export const parseFromParam = (from: string): { pathname: string; search: string } => {
  const url = new URL(from, 'http://from.invalid');
  return { pathname: url.pathname, search: url.search };
};
