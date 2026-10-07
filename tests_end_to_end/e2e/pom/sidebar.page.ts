import type { Page, Locator } from '@playwright/test';
import { expect, test } from '@playwright/test';

/**
 * The project-scoped left sidebar (`SideBar` in the v2 layout).
 *
 * Items are tanstack `<Link>`s rendered as `<a>` inside the `<aside>`
 * (role `complementary`), with an icon and a label div. The label is only in
 * the DOM while the sidebar is expanded — collapsed, the link has no accessible
 * name and the label lives in a hover tooltip — so `navigateTo` expands the
 * sidebar first when the "Expand sidebar" toggle is showing. Expanded is the
 * default on desktop widths.
 */
export class SidebarNav {
  constructor(private readonly page: Page) {}

  private get sidebar(): Locator {
    return this.page.getByRole('complementary');
  }

  /** The sidebar link for `label`, matched exactly (e.g. "Logs", "Dashboards"). */
  link(label: string): Locator {
    return this.sidebar.getByRole('link', { name: label, exact: true });
  }

  /**
   * Click the sidebar item and wait for it to become the active route.
   *
   * Settles on the link's `aria-current="page"` rather than a URL change: the
   * router sets it for the matched route, and clicking the item you are already
   * on (a bare-URL re-navigation) changes no pathname to wait on.
   */
  async navigateTo(label: string): Promise<void> {
    return test.step(`Open "${label}" from the sidebar`, async () => {
      const expand = this.sidebar.getByRole('button', { name: 'Expand sidebar' });
      if (await expand.isVisible()) {
        await expand.click();
      }
      const link = this.link(label);
      await link.click();
      await expect(link).toHaveAttribute('aria-current', 'page');
    });
  }
}
