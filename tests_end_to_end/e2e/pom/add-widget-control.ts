import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * A section header's "Add widget" control, which is two different controls.
 *
 * `AddWidgetButton` renders a plain button that opens the widget config dialog
 * when Ollie is unavailable, and a `DropdownMenu` trigger offering "Pick a
 * chart" / "Ask Ollie" when it is (opik#9162, 2.2.118). Ollie needs
 * `OLLIE_ENABLED` *and* `CUSTOM_CHARTS_ENABLED_FOR_WORKSPACE` *and* the
 * `AssistantSidebar` plugin, all of which are off in OSS and self-hosted and on
 * for a cloud Custom-Charts workspace — which is exactly what
 * `envConfig.features.ollie` already records.
 *
 * Shared by `DashboardsPage` and `ProjectDashboardsPage` rather than written
 * twice: it is one component rendering both dashboards, and two copies of the
 * branch is how the two page objects quietly stop agreeing about which branch a
 * deployment is on.
 *
 * Which branch to drive is decided from the config, not sniffed at runtime: a
 * page object that tried the dialog and fell back to the menu would pass on a
 * deployment whose control had silently become the wrong one, which is the
 * regression this release could cause. The expected shape is asserted first, so
 * a misconfigured target fails saying which control it found instead of timing
 * out thirty seconds later waiting for a dialog that was never going to open.
 */
export async function openWidgetConfigDialog(page: Page, trigger: Locator): Promise<Locator> {
  return test.step('Open the widget config dialog from an Add widget control', async () => {
    const ollie = loadEnvConfig().features.ollie;

    await expect(trigger, 'exactly one Add widget control to drive').toHaveCount(1);
    if (ollie) {
      await expect(
        trigger,
        'with Ollie available the Add widget control is a menu trigger',
      ).toHaveAttribute('aria-haspopup', 'menu');
    } else {
      await expect(
        trigger,
        'without Ollie the Add widget control opens the dialog directly',
      ).not.toHaveAttribute('aria-haspopup', 'menu');
    }

    await trigger.click();

    if (ollie) {
      // Matched on the label's leading words: the item's accessible name is its
      // label followed by its description ("Pick a chart Choose a built-in chart
      // and configure it."), and the sibling item starts "Ask Ollie", so an
      // anchored match separates them without depending on the description text.
      //
      // The labels are the ones `ADD_WIDGET_OPTIONS` carries on main: opik#9169
      // reworded them from "Add widget"/"Describe a widget" after 2.2.118 was
      // cut, so a cloud workspace still on 2.2.118 renders the old pair and this
      // match will not find the item there. Tracking main is deliberate — this
      // spec's permanent home is main, and a tolerant alternation would let a
      // future rename pass unnoticed.
      const addItem = page.getByRole('menuitem', { name: /^Pick a chart/ });
      await expect(addItem, 'the menu offers exactly one "Pick a chart" item').toHaveCount(1);
      await addItem.click();
    }

    const dialog = page.getByRole('dialog');
    await dialog.waitFor({ state: 'visible' });
    return dialog;
  });
}
