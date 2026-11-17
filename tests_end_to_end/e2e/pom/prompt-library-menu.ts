import { test, expect, type Locator, type Page } from '@playwright/test';

/**
 * The shared `PromptLibraryMenu` popover — the one library picker behind every
 * door that loads a prompt into a message editor: the Playground variant
 * header's Load, each message row's own Load, and the Optimization Studio
 * composer's rows.
 *
 * Its own module rather than a method on one page object because the SAME
 * popover is reached from surfaces that have no page object in common. Before
 * this existed, `PlaygroundPage` held the only driver for it and a spec about
 * the Studio composer had to restate the hover/retry dance verbatim —
 * `PlaygroundPage.navigateLibraryMenuToVersion` now delegates here, so the two
 * cannot drift.
 *
 * The menu has two entry states, and which one it opens in is a fact about the
 * library rather than about the caller: with `chooseTemplateStructure` set (the
 * variant header, and only the variant header) and both prompt types present,
 * it asks for the type first; otherwise it opens straight on the searchable
 * list. It renders a spinner until it knows which, and never both at once — so
 * an "it did NOT ask" assertion has to settle the list first, or it is racing
 * the spinner. {@link expectOpensOnList} is that assertion written the right
 * way round.
 */
export class PromptLibraryMenu {
  constructor(private readonly page: Page) {}

  /** The popover itself. Radix unmounts a closed one, so count is presence. */
  get root(): Locator {
    return this.page.getByTestId('prompt-library-menu');
  }

  /** The Text/Chat choice, mounted INSTEAD of the list rather than above it. */
  get structureOptions(): Locator {
    return this.page.getByTestId('prompt-library-structure-options');
  }

  structureOption(structure: 'text' | 'chat'): Locator {
    return this.page.getByTestId(`prompt-library-structure-${structure}`);
  }

  /**
   * The chevron back to the type choice. Mounted only once a type has been
   * chosen, and its text is that type's own list label ("Chat prompts"), which
   * is what makes it readable as "which list am I in".
   */
  get back(): Locator {
    return this.page.getByTestId('prompt-library-menu-back');
  }

  get searchBox(): Locator {
    return this.root.getByPlaceholder('Search');
  }

  /**
   * One prompt's row in the list, by the name it displays.
   *
   * `filter({ hasText })` and not an exact match: the row also renders the
   * prompt's stage, environments and relative time, so its text is a superset
   * of the name. Callers assert `toHaveCount(1)` — every name these specs use
   * carries the per-test namespace, so a substring match still addresses one
   * row, and an ambiguous one is a failure rather than a `.first()`.
   */
  promptRow(promptName: string): Locator {
    return this.root.getByRole('button').filter({ hasText: promptName });
  }

  /** The hovercard listing one prompt's versions. */
  get versionsSubmenu(): Locator {
    return this.page.getByTestId('prompt-versions-submenu');
  }

  async waitForOpen(): Promise<void> {
    return test.step('wait for the prompt library menu to open', async () => {
      await this.root.waitFor({ state: 'visible' });
    });
  }

  /**
   * Assert the menu opened straight on the searchable list, with no type
   * choice — the state a library holding exactly one prompt type produces.
   *
   * The ORDER here is the whole point: the search box is awaited first, which
   * is what proves `useProjectPromptsList` has answered, and only then is the
   * absence of the type choice asserted. Asserting the absence first would
   * pass against the spinner the menu shows while it still does not know.
   */
  async expectOpensOnList(): Promise<void> {
    return test.step('the menu opened on the list, with no type choice', async () => {
      await expect(this.searchBox).toBeVisible();
      await expect(this.structureOptions, 'no type choice').toHaveCount(0);
      await expect(this.back, 'no back chevron, since no type was chosen').toHaveCount(0);
    });
  }

  /** Pick a prompt type and settle on its list. */
  async chooseStructure(structure: 'text' | 'chat'): Promise<void> {
    return test.step(`choose the ${structure}-prompt list`, async () => {
      await this.structureOption(structure).click();
      await expect(this.searchBox).toBeVisible();
    });
  }

  /**
   * Click one named prompt's row, which selects its LATEST version — the
   * ordinary gesture, as distinct from {@link selectVersion} below.
   *
   * The click is dispatched rather than performed for the same reason
   * {@link selectVersion}'s is: hovering the row opens the version hovercard
   * over it, and Playwright's actionability check then never settles on the
   * element underneath. Searching first narrows the list to one row, so the
   * count assertion is a real check that the prompt is there and unique.
   */
  async selectPrompt(promptName: string): Promise<void> {
    return test.step(`select prompt "${promptName}" (latest version)`, async () => {
      await this.searchBox.fill(promptName);
      const row = this.promptRow(promptName);
      await expect(row, `one row for "${promptName}"`).toHaveCount(1);
      await row.dispatchEvent('click');
      await expect(this.root, 'the menu closes on a selection').toBeHidden();
    });
  }

  /**
   * Hover a named prompt to reveal its version submenu and click one version
   * label (e.g. "v1").
   *
   * Two things here are worked around rather than waited on, and neither is a
   * product defect — a human moving a mouse continuously hits neither:
   *
   * 1. The row list REMOUNTS when the search result lands, so a pointer already
   *    parked over the row raises no fresh `pointerenter` and the Radix
   *    `HoverCard` (`openDelay: 120`) never opens. The pointer is moved away and
   *    the row hovered again until the submenu is really up.
   * 2. The HoverCard content ANIMATES in, and a real click has to walk the
   *    pointer off the trigger to reach it — so Playwright's actionability check
   *    never settles and `click()` times out. The click is dispatched on the
   *    version row instead.
   */
  async selectVersion(promptName: string, versionLabel: string): Promise<void> {
    await this.searchBox.fill(promptName);

    const promptRow = this.promptRow(promptName).first();
    await promptRow.waitFor({ state: 'visible' });

    const versionRow = this.versionsSubmenu
      .getByRole('button')
      .filter({ has: this.page.getByText(versionLabel, { exact: true }) });

    // The hover, the count check and the click are retried as a GROUP, and the
    // search above is not: `PromptsSelectBox` mounts the menu with
    // `refetchOnMount`, so the list can re-render under the cursor and detach
    // the version submenu between opening it and clicking into it. Re-hovering
    // re-opens it against the row that is mounted now; re-filling the search
    // box would reset the list and lose the hover that opens the submenu at
    // all. The mouse is parked first so each re-hover is a real pointer move.
    await expect(async () => {
      await this.page.mouse.move(0, 0);
      await promptRow.hover();
      await expect(this.versionsSubmenu).toBeVisible({ timeout: 2_000 });
      // Exactly one, not `.first()`: an ambiguous match would silently load a
      // version the caller did not ask for.
      await expect(versionRow, `version "${versionLabel}" of "${promptName}"`).toHaveCount(1, {
        timeout: 2_000,
      });
      await versionRow.dispatchEvent('click');
    }).toPass({ timeout: 20_000 });
  }
}
