import { test, expect, type Page, type Locator } from '@playwright/test';

/**
 * Where to send the pointer to get it off a hover target inside the panel.
 *
 * Hard left of the viewport: the trace panel is a right-hand sheet, so nothing
 * it renders — including a tooltip anchored to one of its cells — reaches this
 * far, whatever side Radix chose to open on. Deliberately not (0, 0), which is
 * the corner of the app nav and raises a tooltip of its own.
 */
const PANEL_POINTER_PARK = { x: 4, y: 300 } as const;

export class TracePanelPage {
  constructor(
    private readonly page: Page,
    private readonly traceId: string,
  ) {}

  async waitForFullyLoaded(): Promise<void> {
    return test.step(`Wait for trace panel ${this.traceId} to load`, async () => {
      await this.page.waitForURL((url) => url.searchParams.get('trace') === this.traceId);
      await this.closeButton.waitFor({ state: 'visible', timeout: 30_000 });
      await this.page.getByTestId('data-viewer-created-at').waitFor({ state: 'visible', timeout: 30_000 });
    });
  }

  /** Root locator for the side-panel content, scoped to the panel testid. */
  get root(): Locator {
    return this.page.getByTestId('traces');
  }

  get closeButton(): Locator {
    return this.root.getByRole('button', { name: 'Close' });
  }

  get inputSection(): Locator {
    return this.root.getByRole('button', { name: 'Input', expanded: true });
  }

  get outputSection(): Locator {
    return this.root.getByRole('button', { name: 'Output', expanded: true });
  }

  /**
   * A collapsible section header, whatever its state — the counterpart to
   * {@link inputSection} and {@link outputSection}, which bake `expanded: true`
   * into the locator and so cannot express "this section is collapsed".
   *
   * Matched on the accessible name, exactly: the shared `CodeBlock` renders
   * every section identically and only the title tells them apart, and a
   * substring match would let `Input` also address a future `Input schema`.
   * The locator is left strict rather than reduced with `.first()`, so a title
   * that stops being unique fails loudly instead of testing whichever section
   * the DOM happened to put first.
   */
  sectionHeader(title: string): Locator {
    return this.root.getByRole('button', { name: title, exact: true });
  }

  /**
   * Click a section header and wait for its own `aria-expanded` to flip.
   *
   * Gated on the flip rather than on the body appearing: a collapsed section's
   * body is hidden with a class, not unmounted, so "the content is in the DOM"
   * is true in both states and would make the wait a no-op.
   */
  async toggleSection(title: string): Promise<void> {
    return test.step(`Toggle the ${title} section`, async () => {
      const header = this.sectionHeader(title);
      const wasExpanded = (await header.getAttribute('aria-expanded')) === 'true';
      await header.click();
      await header
        .and(this.root.locator(`[aria-expanded="${wasExpanded ? 'false' : 'true'}"]`))
        .waitFor();
    });
  }

  /** Heading-area locator for the trace name shown in the panel toolbar. */
  traceNameInHeader(name: string): Locator {
    return this.root.getByText(name, { exact: true }).first();
  }

  /** Text matching `Spans (n)` shown above the spans tree. */
  spansCountLabel(n: number): Locator {
    return this.root.getByText(new RegExp(`^Spans\\s*\\(${n}\\)$`)).first();
  }

  /** A node in the span tree, keyed by the span/trace name. */
  spanTreeNode(name: string): Locator {
    return this.root.getByTestId(`trace-tree-node-${name}`);
  }

  /** The expand/collapse toggle within a given tree node. */
  spanTreeToggle(name: string): Locator {
    return this.spanTreeNode(name).getByRole('button', { name: 'Expand or collapse span' });
  }

  /** Collapse a span tree node by clicking its toggle. */
  async collapseSpan(name: string): Promise<void> {
    return test.step(`Collapse span "${name}"`, async () => {
      await this.spanTreeToggle(name).click();
    });
  }

  /** Expand a span tree node by clicking its toggle. */
  async expandSpan(name: string): Promise<void> {
    return test.step(`Expand span "${name}"`, async () => {
      await this.spanTreeToggle(name).click();
    });
  }

  /** Select a span in the tree, opening its detail in the inspect area. */
  async selectSpan(name: string): Promise<void> {
    return test.step(`Select span "${name}"`, async () => {
      await this.spanTreeNode(name).click();
      await this.page.waitForURL((url) => (url.searchParams.get('span') ?? '') !== '');
    });
  }

  /** The provider/model chip shown in the inspect header for an LLM span. */
  get spanModelChip(): Locator {
    return this.root.getByTestId('data-viewer-provider-model');
  }

  /** Text within the panel — use to assert token usage / cost values render. */
  panelText(value: string | RegExp): Locator {
    return this.root.getByText(value);
  }

  /**
   * The detail half of the panel — the pane that renders whichever entity is
   * selected in the span tree, as opposed to the tree itself.
   */
  get dataViewer(): Locator {
    return this.root.locator('#data-viewer');
  }

  /**
   * The estimated-cost stat in the data viewer's header row, as it is formatted.
   *
   * Scoped to the viewer, not the whole panel: the span tree renders a cost per
   * node too, so a panel-wide lookup matches the tree row and the header both.
   * Exact, not substring: `$3` must not pass for `$30`, and `$6` must not pass
   * for `$6.25`. Whose cost it reads follows the panel's own selection — the
   * trace's rolled-up total while the trace is selected, that span's own once a
   * span is. `TraceStatsDisplay` carries no `data-testid`, so the formatted
   * amount is the handle; adding one is a worthwhile follow-up, but a spec
   * verified against a deployed build cannot depend on an attribute that build
   * does not have.
   */
  estimatedCost(formatted: string): Locator {
    return this.dataViewer.getByText(formatted, { exact: true });
  }

  // --- Attachments ---

  /**
   * The "Attachments" collapsible section header.
   *
   * The section renders nothing at all when the trace carries no media, so its
   * presence is itself an assertion and not just a scoping step.
   */
  get attachmentsSection(): Locator {
    return this.root.getByRole('button', { name: 'Attachments' });
  }

  /**
   * One attachment thumbnail, addressed by the download link it stamps with its
   * own file name.
   *
   * By name rather than by position: the list is re-sorted by media type before
   * it renders (`ATTACHMENT_ORDER_MAP`), so the upload order is not the display
   * order and any index-based locator would be asserting on the sort instead of
   * on the attachment. `download="<file name>"` is the one attribute unique to
   * a single thumbnail — a thumbnail carries no `data-testid`, and its visible
   * label is plain text that a matching input/output payload could also
   * produce. The link only *becomes visible* on hover, so assert on its count,
   * not its visibility; `attachmentLabel` covers what the user actually sees.
   */
  attachmentThumbnail(fileName: string): Locator {
    return this.root.locator(`a[download="${fileName}"]`);
  }

  /** The file name as the thumbnail renders it. */
  attachmentLabel(fileName: string): Locator {
    return this.root.getByText(fileName, { exact: true });
  }

  /**
   * One attachment's tile.
   *
   * `AttachmentThumbnail` renders an `<img>` for a media type it classified as
   * IMAGE and a lucide icon for everything else, and the two never coexist — so
   * telling those branches apart needs a scope that is exactly one tile.
   *
   * Scoped by the download link that carries the file name, which is the only
   * attribute unique to a single tile (see `attachmentThumbnail`). The `div.group`
   * outer selector is a CSS class rather than a `data-testid` because the tile
   * has none, and these specs run against a pre-built deployment where an
   * attribute added alongside them would not exist. `group` is load-bearing
   * rather than cosmetic — the hover controls key off it via `group-hover:` — and
   * every caller asserts a count, so a structural change fails loudly instead of
   * silently widening the scope.
   */
  private attachmentTile(fileName: string): Locator {
    return this.root
      .locator('div.group')
      .filter({ has: this.page.locator(`a[download="${fileName}"]`) });
  }

  /**
   * The `<img>` preview for one attachment — present only for an IMAGE type.
   *
   * Addressed by `alt`, which `AttachmentThumbnail` sets to the file name, so
   * this is an identity match and not a positional one.
   */
  attachmentImage(fileName: string): Locator {
    return this.root.locator(`img[alt="${fileName}"]`);
  }

  /**
   * Every attachment preview the panel rendered as an image.
   *
   * A DIRECT child of the tile, which is what makes this "classified as IMAGE"
   * rather than "has a picture in it somewhere": the IMAGE branch renders its
   * `<img>` straight into the tile, while the VIDEO branch renders a
   * `VideoThumbnail` whose poster `<img>` sits one container deeper. A
   * descendant selector would count a video's poster as an image and quietly
   * satisfy a count that is meant to exclude it.
   */
  get attachmentImages(): Locator {
    return this.root.locator('div.group > img');
  }

  /**
   * The generic file icon one attachment fell back to.
   *
   * `lucide-file` exactly, not a prefix match: `lucide-file-text`,
   * `lucide-file-image` and friends are separate class tokens for the PDF, TEXT,
   * AUDIO and VIDEO branches, and a spec that accepted any of them would stop
   * distinguishing "classified as OTHER" from "classified as something else that
   * also is not an image".
   */
  attachmentGenericIcon(fileName: string): Locator {
    return this.attachmentTile(fileName).locator('svg.lucide-file');
  }

  /**
   * The attachment rendered a real, decoded picture.
   *
   * `naturalWidth` rather than visibility: a broken `<img>` is still visible, so
   * visibility alone would pass for a tile whose source never loaded.
   */
  async expectAttachmentDecodes(fileName: string): Promise<void> {
    return test.step(`${fileName} renders a decoded image`, async () => {
      const image = this.attachmentImage(fileName);
      await expect(image, `exactly one <img> for ${fileName}`).toHaveCount(1);
      // Lazy-loaded: an `<img>` that never entered the viewport reports
      // naturalWidth 0 whether or not its source is good.
      await image.scrollIntoViewIfNeeded();
      await expect
        .poll(async () => image.evaluate((img) => (img as HTMLImageElement).naturalWidth), {
          message: `naturalWidth of the ${fileName} preview`,
        })
        .toBeGreaterThan(0);
    });
  }

  /** Opens the Attachments section if it is collapsed. Idempotent. */
  async openAttachments(): Promise<void> {
    return test.step('Open the Attachments section', async () => {
      const header = this.attachmentsSection;
      await header.waitFor({ state: 'visible', timeout: 30_000 });
      if ((await header.getAttribute('aria-expanded')) !== 'true') {
        await header.click();
      }
    });
  }

  // --- Tags ---

  get addTagButton(): Locator {
    return this.root.getByTestId('add-tag-button');
  }

  /** A rendered tag chip matching the given tag text. */
  tagChip(tag: string): Locator {
    return this.root.getByText(tag, { exact: true });
  }

  /** Add a tag via the add-tag popover input. */
  async addTag(tag: string): Promise<void> {
    return test.step(`Add tag "${tag}"`, async () => {
      await this.addTagButton.click();
      const input = this.page.getByPlaceholder('New tag');
      await input.waitFor({ state: 'visible' });
      await input.fill(tag);
      await input.press('Enter');
      await this.page.keyboard.press('Escape');
    });
  }

  // --- Annotate panel: manual feedback scores ---

  /** Open the Annotate panel section. Idempotent. */
  async openAnnotate(): Promise<void> {
    return test.step('Open Annotate panel', async () => {
      await this.root.getByRole('button', { name: /Annotate/ }).click();
      await this.page.waitForURL((url) => url.searchParams.get('lastSection') === 'annotate');
    });
  }

  /** The annotate score row for a named feedback definition. */
  annotateScoreRow(definitionName: string): Locator {
    return this.root.getByTestId(`annotate-score-row-${definitionName}`);
  }

  /** Set (or change) the numeric value in a named annotate score row. */
  async setAnnotateScore(definitionName: string, value: number): Promise<void> {
    return test.step(`Set ${definitionName} score to ${value}`, async () => {
      const input = this.annotateScoreRow(definitionName).getByTestId('annotate-score-input');
      await input.fill(String(value));
      // The score input debounces; blur to flush the write.
      await input.blur();
    });
  }

  /**
   * The reason textarea for a named annotate score. Disabled until a score is set.
   * Sits in a grid cell that is a sibling of the score-input cell (the one carrying
   * the row testid), not a descendant — so it's scoped to the panel root, same as
   * the score's "Clear score" button.
   */
  annotateReasonInput(definitionName: string): Locator {
    return this.root.getByTestId(`annotate-score-reason-${definitionName}`);
  }

  /** Set (or change) the reason text in a named annotate score row. Requires a score to be set first. */
  async setAnnotateReason(definitionName: string, reason: string): Promise<void> {
    return test.step(`Set ${definitionName} reason to "${reason}"`, async () => {
      const input = this.annotateReasonInput(definitionName);
      await input.fill(reason);
      // The reason input debounces; blur to flush the write.
      await input.blur();
    });
  }

  /**
   * Clear the score in a named annotate score row. The clear button sits in a
   * grid cell that is a sibling of the score-input cell (the one carrying the
   * row testid), not a descendant — so it's scoped to the panel root. With a
   * single seeded definition this is unambiguous; the void param documents
   * which score the call clears.
   */
  async clearAnnotateScore(definitionName: string): Promise<void> {
    return test.step(`Clear ${definitionName} score`, async () => {
      await this.root.getByRole('button', { name: 'Clear score' }).click();
    });
  }

  /** A rendered feedback score tag matching the given score name. */
  feedbackScoreTag(scoreName: string): Locator {
    return this.root
      .getByTestId('feedback-score-tag')
      .filter({ has: this.page.getByTestId('feedback-score-tag-label').filter({ hasText: scoreName }) });
  }

  /** Read the value rendered on a feedback score tag by score name. */
  async readFeedbackScoreTagValue(scoreName: string): Promise<string> {
    const tag = this.feedbackScoreTag(scoreName);
    await tag.waitFor({ state: 'visible' });
    return (await tag.getByTestId('feedback-score-tag-value').textContent())?.trim() ?? '';
  }

  /** Rendered input text inside the panel's Details tab. */
  inputValue(value: string): Locator {
    return this.root.getByText(value, { exact: true }).first();
  }

  /** Rendered output text inside the panel's Details tab. */
  outputValue(value: string): Locator {
    return this.root.getByText(value, { exact: true }).first();
  }

  async close(): Promise<void> {
    return test.step('Close trace panel', async () => {
      await this.closeButton.click();
      await this.page.waitForURL((url) => !url.searchParams.get('trace'));
    });
  }

  /**
   * The panel's own next/previous-row control, which walks the table behind it.
   *
   * Two selectors, because the builds disagree and both are live targets for
   * this suite. The OSS panel renders an icon-only button carrying
   * `data-testid="side-panel-next"` and no accessible name — its label lives in
   * a tooltip, which contributes nothing to the a11y tree. The cloud build
   * renders a labelled button ("Next", plus a hotkey chip) and does not carry
   * the testid. Neither locator alone resolves on both, and preferring the
   * testid while silently having no fallback is how a cloud run spends its
   * whole action budget waiting for an element that build never renders.
   */
  rowNavButton(direction: 'next' | 'previous'): Locator {
    const label = direction === 'next' ? /^Next\b/ : /^Previous\b/;
    return this.root
      .getByTestId(`side-panel-${direction}`)
      .or(this.root.getByRole('button', { name: label }));
  }

  /**
   * Move to the adjacent table row using the panel's own arrows, and answer a
   * page object for whichever trace it landed on.
   *
   * This, rather than navigating to the next trace's URL: the panel stays
   * mounted across a row change and resets its per-node state from the id
   * alone, so a reload would answer a question nobody asked. Which trace is
   * adjacent depends on the table's sort, so the id is read back from the URL
   * instead of assumed.
   */
  async goToAdjacentRow(direction: 'next' | 'previous'): Promise<TracePanelPage> {
    return test.step(`Move to the ${direction} trace row`, async () => {
      await this.rowNavButton(direction).click();
      await this.page.waitForURL((url) => {
        const shown = url.searchParams.get('trace') ?? '';
        return shown !== '' && shown !== this.traceId;
      });
      const shown = new URL(this.page.url()).searchParams.get('trace') as string;
      const panel = new TracePanelPage(this.page, shown);
      await panel.waitForFullyLoaded();
      return panel;
    });
  }

  /**
   * The collapsible Error section header. Matched on its accessible name rather
   * than a testid: the shared CodeBlock renders every section the same way, and
   * only the title distinguishes them.
   */
  get errorSection(): Locator {
    return this.root.getByRole('button', { name: /^Error$/ });
  }

  /** Expand the Error section. */
  async expandError(): Promise<void> {
    return test.step('Expand the Error section', async () => {
      await this.errorSection.click();
      await this.errorSection.and(this.root.locator('[aria-expanded="true"]')).waitFor();
    });
  }

  /** Collapse the Error section. */
  async collapseError(): Promise<void> {
    return test.step('Collapse the Error section', async () => {
      await this.errorSection.click();
      await this.errorSection.and(this.root.locator('[aria-expanded="false"]')).waitFor();
    });
  }

  /** The `Fix via MCP` pill, shown once the user opens the error. */
  get mcpHintButton(): Locator {
    return this.root.getByTestId('mcp-hint-button');
  }

  /** Portalled, so it is looked up on the page rather than inside the panel. */
  get mcpHintPopover(): Locator {
    return this.page.getByTestId('mcp-hint-popover');
  }

  /** The hint fades in, so give the animation a moment to land. */
  async waitForMcpHint(): Promise<void> {
    return test.step('Wait for the MCP hint to appear', async () => {
      await this.mcpHintButton.waitFor({ state: 'visible', timeout: 5_000 });
    });
  }

  /** Opens the hint popover by hovering the pill, as a user would. */
  async openMcpHintPopover(): Promise<void> {
    return test.step('Open the MCP hint popover', async () => {
      await this.mcpHintButton.hover();
      await this.mcpHintPopover.waitFor({ state: 'visible' });
    });
  }

  /**
   * Wait until the hint pill has stopped moving.
   *
   * The pill fades and slides in from a few pixels above its resting spot,
   * after a delay held at the animation's first frame. Playwright calls it
   * visible as soon as it has a non-empty box — which is true throughout that
   * slide — so anything measuring its position has to wait for the box itself
   * to settle. Two consecutive equal reads, not a timeout: the animation's
   * duration is a stylesheet value this has no business encoding.
   */
  async waitForMcpHintSettled(): Promise<void> {
    return test.step('Wait for the MCP hint pill to settle', async () => {
      await this.waitForMcpHint();
      let previousTop: number | null = null;
      await expect
        .poll(
          async () => {
            const box = await this.mcpHintButton.boundingBox();
            const top = box?.y ?? null;
            const settled = top !== null && top === previousTop;
            previousTop = top;
            return settled;
          },
          { message: 'the MCP hint pill should stop moving', timeout: 10_000 },
        )
        .toBe(true);
    });
  }

  /**
   * The "Search" control of one section header — the icon that opens the
   * section's find box.
   *
   * Addressed by its accessible name, which is what `CodeBlockSearch` gives it;
   * there is no `data-testid` on this build, and adding one would leave the spec
   * unrunnable against every deployment that predates it. Scoped to the
   * section's own header row (the button's parent element) so it cannot resolve
   * to a sibling section's icon.
   */
  sectionSearchButton(title: string): Locator {
    return this.sectionHeader(title)
      .locator('xpath=..')
      .getByRole('button', { name: 'Search', exact: true });
  }

  /** The find box a section's Search icon opens. */
  sectionSearchInput(title: string): Locator {
    return this.sectionHeader(title).locator('xpath=..').getByPlaceholder('Search...');
  }

  /**
   * The element the browser would actually deliver a click at `(x, y)` to,
   * as a short descriptor: `TAG[data-testid]`, or `TAG[aria-label]` when the
   * element has no testid.
   *
   * `document.elementFromPoint` rather than a Playwright click, because a click
   * that lands on the wrong element reports as an actionability timeout — the
   * right outcome, but it names Playwright's own machinery instead of naming
   * the element that took the click. The descriptor walks up from the hit node
   * to the nearest labelled ancestor, since the topmost element under a pointer
   * is usually an unlabelled `<svg>` inside the control.
   */
  async hitTargetAt(x: number, y: number): Promise<string | null> {
    return this.page.evaluate(
      ([px, py]) => {
        const hit = document.elementFromPoint(px, py);
        if (!hit) return null;
        const labelled = hit.closest('[data-testid], [aria-label]');
        const target = labelled ?? hit;
        const label = target.getAttribute('data-testid') ?? target.getAttribute('aria-label') ?? '';
        return `${target.tagName}[${label}]`;
      },
      [x, y],
    );
  }

  /**
   * Scroll the data viewer's own overflow container to `top`, clamped to what
   * it can actually reach, and answer the scrollTop it ended at.
   *
   * The container is found by walking up from the section header to the nearest
   * scrollable ancestor rather than by selector: it carries neither a testid nor
   * a stable class, and the alternative — a structural path down from the
   * resizable panel — would break on any wrapper added between the two.
   */
  async scrollDataViewerTo(sectionTitle: string, top: number): Promise<number> {
    return test.step(`Scroll the trace panel to ${Math.round(top)}px`, async () => {
      const reached = await this.sectionHeader(sectionTitle).evaluate((element, requested) => {
        let node: Element | null = element.parentElement;
        while (node) {
          const overflowY = getComputedStyle(node).overflowY;
          if (/(auto|scroll)/.test(overflowY) && node.scrollHeight > node.clientHeight) {
            node.scrollTop = Math.max(0, Math.min(requested, node.scrollHeight - node.clientHeight));
            return node.scrollTop;
          }
          node = node.parentElement;
        }
        return null;
      }, top);

      if (reached === null) {
        throw new Error(
          'TracePanelPage.scrollDataViewerTo: the trace panel has no scrollable ancestor — ' +
            'the seeded trace is not tall enough to overflow the viewer.',
        );
      }
      return reached;
    });
  }

  /** Locator for the Feedback scores tab inside the panel. */
  get feedbackScoresTab(): Locator {
    return this.root.getByRole('tab', { name: 'Feedback scores' });
  }

  /** Locator for the Feedback scores tab panel content (the table area). */
  get feedbackScoresTabPanel(): Locator {
    return this.root.getByRole('tabpanel', { name: 'Feedback scores' });
  }

  /** Switches to the Feedback scores tab. Idempotent if already selected. */
  async openFeedbackScoresTab(): Promise<void> {
    await this.feedbackScoresTab.click();
    await this.feedbackScoresTabPanel.waitFor({ state: 'visible' });
  }

  /**
   * Row in the Trace scores table matching the given score name. The Key cell
   * truncates long names with CSS ellipsis, so the accessible name reads as
   * "cuj-..." rather than the full string; matching by `hasText` against the
   * row's DOM text content (which preserves the full name) is reliable across
   * panel widths.
   */
  feedbackScoreRow(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.getByRole('row').filter({ hasText: scoreName });
  }

  /**
   * Every score table rendered on the Feedback scores tab.
   *
   * The tab renders a "Trace scores" table, and a second "Span scores" one when
   * the trace's spans carry scores of their own. Neither is labelled in the DOM,
   * so a spec that means "the trace's own scores" and wants an exhaustive row
   * assertion should assert this is exactly 1 first — otherwise the row
   * locators below silently range over both tables' rows.
   */
  feedbackScoreTables(): Locator {
    return this.feedbackScoresTabPanel.getByRole('table');
  }

  /**
   * Every data row on the Feedback scores tab, for counting.
   *
   * `[data-row-id]` is what makes this the rows and not the header: the shared
   * `DataTable` stamps it on body rows only.
   */
  feedbackScoreRows(): Locator {
    return this.feedbackScoresTabPanel.locator('tbody tr[data-row-id]');
  }

  /**
   * The row for one score, addressed by identity rather than by text.
   *
   * This table's row id IS the score name, so the attribute match is exact —
   * unlike {@link feedbackScoreRow}'s `hasText`, which also matches a score
   * whose name merely contains this one.
   */
  feedbackScoreRowByName(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.locator(
      `tbody tr[data-row-id="${scoreName}"]`,
    );
  }

  /** The Score cell of one row — `<rowId>_value`, the cell id `DataTable` stamps. */
  feedbackScoreValueCell(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.locator(
      `td[data-cell-id="${scoreName}_value"]`,
    );
  }

  /**
   * The Reason cell of one row — `<rowId>_reason`, the sibling of
   * {@link feedbackScoreValueCell}. `reason` is the column id
   * `FeedbackScoreTableColumns.REASON` declares, so this is stable against the
   * column reordering the Reason/Score/Author columns are configurable for.
   *
   * The cell itself only ever shows ONE line: its `<span>` carries `truncate`,
   * which is `white-space: nowrap` plus an ellipsis. The full reason lives in
   * the hover tooltip — see {@link feedbackScoreReasonTooltipText}.
   */
  feedbackScoreReasonCell(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.locator(
      `td[data-cell-id="${scoreName}_reason"]`,
    );
  }

  /**
   * Hover a score's Reason cell and read back the tooltip's RENDERED text.
   *
   * `innerText` rather than `textContent` is the whole point of this method.
   * `textContent` returns the source string, so it reports a `\n` the browser
   * may have collapsed to a space and a caller asserting on it would pass
   * whatever the CSS did. `innerText` is defined over the rendered text and
   * applies white-space processing, so the line breaks it returns are the line
   * breaks a reader sees — which is the only way to tell
   * `whitespace-pre-line` from `white-space: normal` without reaching into the
   * class attribute of the element under test.
   *
   * The returned string is the WHOLE tooltip, header included: it opens with an
   * `author (value) <time ago>` row whose parts are separate block-level boxes,
   * so how many `innerText` lines they occupy is a layout detail no caller
   * should depend on. The reason is always the tail, so assert on it from the
   * END of the string rather than by dropping a fixed number of leading lines.
   *
   * Radix portals the content out of the panel, so the tooltip cannot be found
   * by descending from the cell. It is resolved through `aria-describedby`
   * instead of by a page-scoped `getByRole('tooltip')`, which is what
   * `PlaygroundPage.outputErrorTooltipText` does: that page has one tooltip at a
   * time, this table has one per row, and reading whichever is open the instant
   * a second cell is hovered is a race — the previous row's tooltip is still
   * closing, and taking `.first()` would read the wrong reason and still pass
   * shape checks. Radix stamps the open content's id on its own trigger, so
   * this always reads the tooltip belonging to the cell just hovered.
   *
   * The span, not the cell, is what gets hovered: the span is the trigger Radix
   * wrapped, and for a short reason it covers only part of a wide cell, so
   * hovering the cell's centre can miss it entirely.
   */
  async feedbackScoreReasonTooltipText(scoreName: string): Promise<string> {
    return test.step(`hover the reason for "${scoreName}" and read its tooltip`, async () => {
      // Park the pointer and let any previous row's tooltip close first. This
      // is not tidiness: the open content is a real box laid out next to its
      // own cell, it keeps its pointer events, and it covers the neighbouring
      // rows — so hovering a second reason without closing the first fails as
      // "subtree intercepts pointer events", not as a wrong read.
      //
      // `steps` is load-bearing, and this is the whole reason the parking move
      // exists as its own line rather than a bare `mouse.move`. Radix keeps
      // hoverable tooltip content open across a "grace area" between trigger
      // and content, and it only reconsiders on a pointermove it actually
      // observes. A default single-step move teleports the cursor and the
      // tooltip stays open (`data-state="delayed-open"`) indefinitely, however
      // far away it lands — so the wait below would never resolve.
      //
      // The wait is scoped to THIS table's reason cells rather than to
      // `getByRole('tooltip')` at page scope: the parking position is over the
      // app nav, which raises a tooltip of its own, and waiting for zero
      // tooltips anywhere would never resolve.
      await this.page.mouse.move(PANEL_POINTER_PARK.x, PANEL_POINTER_PARK.y, { steps: 12 });
      await expect(
        this.feedbackScoresTabPanel.locator('td[data-cell-id$="_reason"] [aria-describedby]'),
        'no reason tooltip left open by a previous hover',
      ).toHaveCount(0);

      const trigger = this.feedbackScoreReasonCell(scoreName).locator('span').first();
      await trigger.hover();

      // `aria-describedby` is present only while the content is open, so this
      // is also the wait for the tooltip's open timer.
      await expect(trigger, 'the reason cell raised its tooltip').toHaveAttribute(
        'aria-describedby',
        /.+/,
        { timeout: 10_000 },
      );
      const contentId = await trigger.getAttribute('aria-describedby');
      // Attribute-matched rather than `#id`: Radix ids look like `radix-:r7:`,
      // and the colons are not valid in a CSS id selector.
      const tooltip = this.page.locator(`[id="${contentId}"]`);
      await expect(tooltip).toBeVisible({ timeout: 10_000 });
      return (await tooltip.innerText()).trim();
    });
  }

  /**
   * Read the numeric value rendered in the Score column for the given score name.
   * Requires the Feedback scores tab to be open (call openFeedbackScoresTab first).
   * Throws if the row doesn't exist or the cell isn't a parseable number.
   */
  async readFeedbackScoreValue(scoreName: string): Promise<number> {
    const row = this.feedbackScoreRow(scoreName);
    await row.waitFor({ state: 'visible' });
    // Columns are: Key | Score | Reason | <actions>
    const cellText = (await row.getByRole('cell').nth(1).textContent()) ?? '';
    const parsed = Number(cellText.trim());
    if (Number.isNaN(parsed)) {
      throw new Error(
        `TracePanelPage.readFeedbackScoreValue: cell text "${cellText}" for score "${scoreName}" is not a number`,
      );
    }
    return parsed;
  }
}
