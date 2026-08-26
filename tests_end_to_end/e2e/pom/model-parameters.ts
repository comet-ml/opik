import { test, expect } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';

/** What the Thinking level control is showing right now. */
export interface ThinkingLevelState {
  /** The preselected option's label, e.g. `High` or `High (Default)`. */
  selected: string;
  /** Every option the dropdown offers, in render order. */
  options: string[];
}

/**
 * The "Model parameters" popover — the gear button next to a model picker and
 * the per-provider config form inside it (`PromptModelConfigs` +
 * `providerConfigs/*`). The same component is mounted by the Playground variant
 * card and by the online-evaluation add/edit-rule dialog, so this is shared
 * rather than duplicated into both page objects; `root` scopes it to whichever
 * one is under test.
 */
export class ModelParametersPanel {
  constructor(
    private readonly page: Page,
    /** The card / dialog that owns this gear button. */
    private readonly root: Locator,
  ) {}

  /**
   * Read the Thinking level control's preselected value and its full option
   * list, leaving the popover closed again.
   *
   * Deliberately not tolerant of the control being absent: a caller that got
   * `null` back would be free to skip its assertion, and a spec about which
   * levels a model offers must fail — not quietly pass — when the control did
   * not render at all.
   */
  async readThinkingLevel(): Promise<ThinkingLevelState> {
    return test.step('read the Thinking level control', async () => {
      await this.open();
      await expect(
        this.menu().getByText('Thinking level', { exact: true }),
        'the model parameters form must render a Thinking level control',
      ).toHaveCount(1);

      const trigger = this.thinkingLevelTrigger();
      await expect(trigger).toHaveCount(1);
      const selected = ((await trigger.textContent()) ?? '').trim();

      await trigger.click();
      const options = this.page.getByRole('option');
      await options.first().waitFor({ state: 'visible' });
      const labels = (await options.allTextContents()).map((t) => t.trim());

      // Escape dismisses the Select and the popover that hosts it together.
      await this.dismiss(this.menu());
      return { selected, options: labels };
    });
  }

  /** Pick a Thinking level by its visible label and close the popover. */
  async setThinkingLevel(optionLabel: string): Promise<void> {
    return test.step(`set Thinking level to "${optionLabel}"`, async () => {
      await this.open();
      const trigger = this.thinkingLevelTrigger();
      await expect(trigger).toHaveCount(1);
      await trigger.click();
      await this.page.getByRole('option', { name: optionLabel, exact: true }).click();
      // The trigger renders the selected option's label, so this is the
      // control confirming it took the value rather than a bare click.
      await expect(trigger).toHaveText(optionLabel);
      await this.dismiss(this.menu());
    });
  }

  private menu(): Locator {
    return this.page.getByRole('menu');
  }

  /**
   * `SelectBox` renders a Radix `SelectTrigger` carrying `id="thinkingLevel"`,
   * paired with a `<Label htmlFor="thinkingLevel">Thinking level</Label>` — so
   * the label association is the accessible handle here. Page-scoped rather
   * than root-scoped: the popover is portalled to the document body, outside
   * the card/dialog subtree.
   */
  private thinkingLevelTrigger(): Locator {
    return this.page.getByLabel('Thinking level');
  }

  /**
   * The gear button that opens the popover.
   *
   * `PromptModelConfigs` renders it as an icon-only `DropdownMenuTrigger` whose
   * only label is a hover tooltip, which contributes no accessible name — so
   * there is no role- or label-based handle to select it by. It is matched here
   * by the icon it contains, scoped to the owning card/dialog, and asserted to
   * resolve to exactly one element. A `data-testid` on that button (e.g.
   * `model-parameters-trigger`) would be the stable contract and should be
   * added by the FE; it could not be added alongside this page object because
   * these specs were verified against a prebuilt deployment.
   */
  private trigger(): Locator {
    return this.root
      .locator('button[aria-haspopup="menu"]')
      .filter({ has: this.page.locator('svg.lucide-settings2') });
  }

  private async open(): Promise<void> {
    const trigger = this.trigger();
    await expect(trigger, 'exactly one Model parameters button in scope').toHaveCount(1);
    await trigger.click();
    await this.menu().waitFor({ state: 'visible' });
  }

  /**
   * Press Escape until the given layer is gone.
   *
   * Radix occasionally swallows the first Escape while the Select popover is
   * still animating in, and clicking elsewhere is not an option: an open menu
   * puts `pointer-events: none` on the body, so a click lands on nothing and
   * leaves the lock in place for every later interaction. Retrying the key is
   * the gesture that reliably unwinds both layers.
   */
  private async dismiss(layer: Locator): Promise<void> {
    await expect(async () => {
      await this.page.keyboard.press('Escape');
      await expect(layer).toBeHidden({ timeout: 1_000 });
    }).toPass({ timeout: 15_000 });
  }
}
