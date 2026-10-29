import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The evaluation-suite item sidebar on the compare route's Items tab.
 *
 * A different panel from the one `CompareExperimentsPage` models, on the same
 * URL: `ExperimentItemsTab` mounts `TestSuiteExperimentPanel` when the first
 * experiment's `evaluation_method` is `evaluation_suite` and
 * `CompareExperimentsPanel` otherwise, and only one of the two is ever given a
 * row id. They are modelled separately because they are separate components
 * with separate props — notably where each gets the project id it looks
 * attachments up under — and sharing a POM between them would hide exactly that
 * difference.
 *
 * Its root is `data-testid="eval-suite-experiment"`, which `ResizableSidePanel`
 * stamps from its `panelId`.
 */
export class SuiteExperimentPanelPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
    private readonly datasetId: string,
    private readonly experimentIds: string[],
  ) {}

  /** Open the Items tab with one row's sidebar already expanded. */
  async gotoItemRow(datasetItemId: string): Promise<void> {
    await test.step(`open the suite item sidebar for ${datasetItemId}`, async () => {
      const env = loadEnvConfig();
      const experiments = encodeURIComponent(JSON.stringify(this.experimentIds));
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/experiments/` +
          `${this.datasetId}/compare?experiments=${experiments}&tab=items&row=${datasetItemId}`,
      );
      await expect(this.root, 'the evaluation-suite item sidebar').toBeVisible();
    });
  }

  /** The sidebar root. */
  get root(): Locator {
    return this.page.getByTestId('eval-suite-experiment');
  }

  /**
   * The sidebar's link out to the open row's suite item — the "View evaluation
   * item" tag (OPIK-8600).
   *
   * The second of the two call sites that carry the originating compare view in
   * `from`; the dataset-method one is `CompareExperimentsPage.datasetItemTag`.
   * Separate locators for the two because they are separate components
   * rendering different labels at different routes, and the Items tab mounts
   * exactly one of them on `evaluation_method` — so one can break while the
   * other keeps working, which is the same argument this POM exists for.
   *
   * A plain `Link` here, not a `NavigationTag`: it raises no tooltip, so there
   * is deliberately no tooltip reader beside this.
   */
  get evaluationItemTag(): Locator {
    return this.page.getByRole('link', { name: 'View evaluation item' });
  }

  /**
   * The evaluation-item tag's `href`, once it has rendered.
   *
   * The `toHaveCount(1)` is load-bearing, not decoration: the tag renders a
   * beat after the sidebar root becomes visible, so a read taken as soon as
   * `gotoItemRow` returns finds nothing and would report an absent link on a
   * good build. Generous timeout because the sidebar's own reads have to land
   * first.
   */
  async readEvaluationItemTagHref(): Promise<string> {
    return test.step('read the evaluation-item tag href from the sidebar', async () => {
      await expect(
        this.evaluationItemTag,
        'exactly one evaluation-item tag in the suite sidebar',
      ).toHaveCount(1, { timeout: 60_000 });
      const href = await this.evaluationItemTag.getAttribute('href');
      // Asserted, not defaulted — an href-less tag navigates nowhere while
      // looking entirely correct.
      expect(href, 'the evaluation-item tag carries an href').not.toBeNull();
      return href as string;
    });
  }

  /** Follow the evaluation-item tag and settle on the suite items page. */
  async clickEvaluationItemTag(): Promise<void> {
    await test.step('click the evaluation-item tag', async () => {
      await expect(
        this.evaluationItemTag,
        'exactly one evaluation-item tag in the suite sidebar',
      ).toHaveCount(1, { timeout: 60_000 });
      await this.evaluationItemTag.click();
      await this.page.waitForURL((url) => url.pathname.endsWith('/items'), { timeout: 30_000 });
    });
  }

  /**
   * The run tabs, in the order the panel renders them.
   *
   * `MultiRunTabs` labels them "Run 1", "Run 2", … and renders no tab list at
   * all for a single-run item, so an empty match here is a meaningful answer
   * rather than a missing selector. Anchored: `Run 1` as a substring would also
   * match a hypothetical `Run 10`.
   */
  get runTabs(): Locator {
    return this.root.getByRole('button', { name: /^Run \d+$/ });
  }

  /**
   * Show run `index` (0-based) and wait for the body to be the one that belongs
   * to it.
   *
   * The wait is on the run's own content, not on the click: `MultiRunTabs`
   * swaps the body in place, so a read taken straight after the click can still
   * see the previous run — which is precisely the cross-run bleed this panel is
   * being tested for, and would make the bug indistinguishable from a race.
   */
  async selectRun(index: number, previousText?: string): Promise<void> {
    await test.step(`show Run ${index + 1}`, async () => {
      const tab = this.runTabs.nth(index);
      await expect(tab, `the Run ${index + 1} tab`).toBeVisible();

      // Retried as a unit, and NOT because clicking is unreliable.
      // `ExperimentItemContent` holds the selected run in its own state and
      // resets it with `useEffect(() => setActiveRunIndex(0), [experimentItems])`,
      // so a refetch settling after the click bounces the panel back to Run 1 —
      // the body reverts to the first run's and stays there. Against a local
      // build that window is too small to hit; against a cloud deployment it is
      // not, and it showed up as this spec failing on staging with Run 2 still
      // rendering Run 1's output. Re-clicking is the honest response: the panel
      // really is on the wrong run, and absorbing it here keeps the assertion
      // below about CROSS-RUN BLEED rather than about query timing.
      await expect(async () => {
        await tab.click();
        // The panel's own state marker. A class is the house's last resort and
        // it is used here because `MultiRunTabs` gives its buttons no
        // aria-selected, no data-state and no test id — and the frontend is not
        // this branch's to change. React commits the highlight and the body in
        // one render, so the clicked tab carrying it is a sound settle for the
        // body being that run's.
        await expect(
          tab,
          `the Run ${index + 1} tab must be the active one after being clicked`,
        ).toHaveClass(/separator-light/, { timeout: 5_000 });
        await expect(this.outputLine, `Run ${index + 1}'s output line`).toHaveCount(1, {
          timeout: 5_000,
        });
        if (previousText !== undefined) {
          // Settle on the body having actually CHANGED, rather than on any
          // particular run's content. Waiting for the expected marker would
          // presuppose the tab order, which is the backend's to choose; waiting
          // for nothing at all would race the swap and report the previous run's
          // content as a cross-run bleed. "Switching tabs changes the body" is a
          // property the panel owes regardless of ordering, so it is the right
          // thing to wait on — and if it never changes, the message below says
          // precisely that rather than timing out on a mystery locator.
          await expect(
            this.outputLine,
            `Run ${index + 1}'s body must differ from the previously shown run's ` +
              `(${JSON.stringify(previousText)}); an unchanged body means the tab switch ` +
              'did not re-render, or this run is showing the other run\'s output',
          ).not.toHaveText(previousText, { timeout: 5_000 });
        }
      }).toPass({ timeout: 45_000 });
    });
  }

  /**
   * Every rendered text block in the sidebar.
   *
   * `.comet-markdown` covers both of `MarkdownHighlighter`'s branches (markdown
   * text becomes a `<p>` inside it, anything else a plain div with the same
   * class) and `.cm-line` the raw-JSON view behind the Pretty toggle — this
   * panel opens in the latter. A class selector is the house's last resort, but
   * this widget carries neither a test id nor a role, and
   * `CompareExperimentsPage` addresses its copy the same way.
   *
   * Deliberately wide: the sidebar also renders an "Item context" pane holding
   * the DATASET ITEM's data, which is a text block by the same markup. Narrowing
   * happens in `outputLine`.
   */
  private get outputBlocks(): Locator {
    return this.root.locator('.comet-markdown, .cm-line');
  }

  /**
   * The active run's output line specifically.
   *
   * Filtered on the placeholder token, which is what separates the run's output
   * from the Item context pane beside it: the dataset item's data carries no
   * media and so no `[image_N]`. Without the filter this resolves to both and
   * every read is a strict-mode violation.
   */
  private get outputLine(): Locator {
    return this.outputBlocks.filter({ hasText: /\[image_\d+\]/ });
  }

  /** The output text of the run currently on screen. */
  async readRunOutputText(): Promise<string> {
    return test.step('read the active run\'s output text', async () => {
      await expect(this.outputLine, 'the active run\'s rendered output').toHaveCount(1);
      return ((await this.outputLine.textContent()) ?? '').trim();
    });
  }

  /** Every inline-image thumbnail the active run is showing. */
  get runMediaThumbnails(): Locator {
    return this.root.locator('img[alt^="Base64: "]');
  }

  /**
   * The `src` of the active run's single thumbnail.
   *
   * `toHaveCount(1)` first: a run showing two pictures is one of the failures
   * worth catching (the other run's media leaking in beside its own), and
   * taking `.first()` would read straight past it.
   */
  async readRunMediaSrc(): Promise<string> {
    return test.step('read the active run\'s picture', async () => {
      const thumbnail = this.runMediaThumbnails;
      await expect(thumbnail, 'thumbnails shown by the active run').toHaveCount(1);
      await thumbnail.scrollIntoViewIfNeeded();
      // Decoded, not merely present: a wrong `src` and an unreadable one both
      // leave an `<img>` behind, and the tiles are `loading="lazy"`, so one that
      // never entered the viewport reports 0 whatever its source.
      await expect
        .poll(
          async () => thumbnail.evaluate((img) => (img as HTMLImageElement).naturalWidth),
          { message: 'naturalWidth of the active run\'s thumbnail' },
        )
        .toBeGreaterThan(0);
      const src = await thumbnail.getAttribute('src');
      expect(src, 'the active run\'s thumbnail carries a src').not.toBeNull();
      return src as string;
    });
  }
}
