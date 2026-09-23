import { test, expect, type Page, type Locator } from '@playwright/test';

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
   * The `<img>` an `image/*` attachment's thumbnail renders, addressed by its
   * `alt` — which `AttachmentThumbnail` sets to the file name.
   *
   * Only image attachments have one; every other media type renders an icon
   * instead, so this resolves to nothing for them by design. Separate from
   * `attachmentThumbnail` because that locator is the download anchor, which
   * exists whether or not the bytes behind it are a decodable image.
   */
  attachmentImage(fileName: string): Locator {
    return this.root.getByRole('img', { name: fileName, exact: true });
  }

  /**
   * The intrinsic size the browser decoded for an image attachment's thumbnail,
   * as `{ width, height }` — both `0` while the image is still loading, and both
   * `0` forever if the bytes behind its URL are not a decodable image.
   *
   * Scrolls the thumbnail into view first: it is `loading="lazy"`, so the
   * browser does not begin the fetch until it enters the viewport, and the
   * dimensions of an image whose request never started say nothing about the
   * stored object. Idempotent, so a caller can poll this while the decode
   * settles.
   *
   * Answers the pair rather than asserting on it, so the assertion stays in the
   * spec that cares what the size has to be.
   */
  async decodedImageSize(fileName: string): Promise<{ width: number; height: number }> {
    return test.step(`read the decoded size of ${fileName}`, async () => {
      const img = this.attachmentImage(fileName);
      await img.scrollIntoViewIfNeeded();
      return img.evaluate((el) => ({
        width: (el as HTMLImageElement).naturalWidth,
        height: (el as HTMLImageElement).naturalHeight,
      }));
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
