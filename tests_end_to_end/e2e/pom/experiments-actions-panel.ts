import { test, type Locator, type Page } from '@playwright/test';

/**
 * `ExperimentsActionsPanel` — the Compare / Manage tags / Delete group that
 * acts on a selection of experiment rows.
 *
 * It has TWO hosts, which is why it is modelled here rather than inside one
 * page object (opik#9747):
 *
 * - the project Experiments list, where the panel sits inside the
 *   `SelectionActionBar` that replaces the toolbar while rows are ticked, and
 * - the prompt detail page's Experiments tab, which keeps its own toolbar and
 *   renders the panel in it with the `outline` button variant.
 *
 * Only the prompt tab can hold a dataset experiment and a test-suite
 * experiment at the same time — the Experiments page's two tabs each filter on
 * `evaluation_method` — so the panel's "item source" tooltip branch is
 * reachable there and nowhere else. One model for both hosts is what keeps the
 * two specs that drive them from growing near-duplicate locators.
 *
 * Locators are page-wide rather than scoped to a root element: in both hosts
 * these three controls are unique on the page, and neither the bar nor the
 * toolbar row carries a `data-testid` to scope by. Call sites that could be
 * ambiguous assert a count.
 */
export class ExperimentsActionsPanel {
  constructor(private readonly page: Page) {}

  /** The Compare button. Disabled when the selection spans two item sources. */
  get compareButton(): Locator {
    return this.page.getByRole('button', { name: 'Compare', exact: true });
  }

  /**
   * Hover the Compare button to raise its tooltip.
   *
   * Through the wrapper, not the button: a disabled button fires no pointer
   * events, so the panel wraps it in a span that owns the tooltip trigger.
   */
  async hoverCompare(): Promise<void> {
    await test.step('hover Compare', async () => {
      await this.compareButton.locator('..').hover();
    });
  }

  /**
   * The tooltip currently raised.
   *
   * Always read through a retrying assertion: `TooltipWrapper` opens after
   * `TOOLTIP_DELAY_DURATION`, so a one-shot read right after the hover sees
   * nothing and would make an absence assertion pass for the wrong reason.
   */
  get compareTooltip(): Locator {
    return this.page.getByRole('tooltip');
  }

  /**
   * Both of them are icon buttons carrying an `aria-label`, which is the
   * accessible name the role lookup resolves — on the selection bar Manage
   * tags also renders its label as text, and the `aria-label` keeps the two
   * hosts addressable the same way.
   */
  get manageTagsButton(): Locator {
    return this.page.getByRole('button', { name: 'Manage tags' });
  }

  get deleteButton(): Locator {
    return this.page.getByRole('button', { name: 'Delete', exact: true });
  }

  /**
   * Every open dialog, for asserting that a gesture raised NONE.
   *
   * The mixed-source branch used to open `FilterExperimentsToCompareDialog`
   * ("Select experiments to compare"), which opik#9747 deleted in favour of
   * the disabled Compare. Asserting on any dialog rather than on that one
   * heading catches both a resurrected guard and the same-dataset picker
   * ("Add to compare") being raised where it cannot apply.
   */
  get openDialogs(): Locator {
    return this.page.getByRole('dialog');
  }
}
