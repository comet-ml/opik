import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TracePanelPage } from './trace-panel.page';

/**
 * The condition threshold input has no label, so its accessible name falls back
 * to its placeholder. Named here so the coupling to that placeholder is stated
 * once rather than looking like a magic number at the call site.
 */
const THRESHOLD_PLACEHOLDER = '0.7';

/** What a condition's score select reads before a score has been chosen. */
const EMPTY_SCORE_LABEL = 'Select score';

/** The project-scoped queues list at /projects/$projectId/annotation-queues. */
export class AnnotationQueuesPage {
  constructor(private readonly page: Page) {}

  async goto(projectId: string): Promise<void> {
    return test.step('Open the annotation queues list', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/annotation-queues`,
      );
    });
  }

  /**
   * Race a real row against the empty state — the table unmounts entirely when
   * the project has no queues, so waiting on the table alone hangs on an empty
   * project (and after the last queue is deleted).
   */
  async waitForReady(): Promise<void> {
    return test.step('Wait for annotation queues list ready', async () => {
      const realRow = this.page.locator('tbody tr[data-row-id]').first();
      await Promise.race([
        realRow.waitFor({ state: 'visible' }),
        this.emptyState.waitFor({ state: 'visible' }),
      ]);
    });
  }

  /**
   * Row scoped by queue id. DataTable stamps `data-row-id` with the entity id,
   * which pins the row to the queue under test even when sibling queues share a
   * name prefix.
   */
  queueRow(queueId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id="${queueId}"]`);
  }

  get emptyState(): Locator {
    return this.page.getByText('No annotation queues yet');
  }

  /**
   * Delete a queue through the row's kebab menu, confirming the destructive
   * dialog. Resolves once the row is gone from the list.
   *
   * The kebab trigger, the menu items and the ConfirmDialog carry no
   * data-testids (ConfirmDialog is a generic shared component); we scope by the
   * row first, then use the accessible names from AnnotationQueueRowActionsCell.
   * The confirm button must be dialog-scoped — "Delete" also names the menu item.
   */
  async deleteQueue(queueId: string): Promise<void> {
    return test.step(`delete annotation queue ${queueId} via row actions`, async () => {
      const row = this.queueRow(queueId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Delete' }).click();

      const confirm = this.deleteQueueConfirmDialog;
      await confirm.waitFor({ state: 'visible' });
      await confirm.getByRole('button', { name: 'Delete', exact: true }).click();

      await confirm.waitFor({ state: 'hidden' });
      await row.waitFor({ state: 'detached' });
    });
  }

  /** The destructive confirm dialog raised by the row's Delete action. */
  get deleteQueueConfirmDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Delete annotation queue?' }),
    });
  }

  /**
   * The Automation cell of one queue's row.
   *
   * Addressed by `data-cell-id`, which DataTable stamps as
   * `<rowId>_<columnId>` — the row id being the queue id. Column order on this
   * table is user-configurable and persisted, so a positional selector
   * (`td:nth-child(7)`) would read a different column for anyone who has
   * reordered it.
   */
  automationCell(queueId: string): Locator {
    return this.queueRow(queueId).locator('td[data-cell-id$="_automation"]');
  }

  /** Header of the Automation column, to confirm it is on screen at all. */
  get automationColumnHeader(): Locator {
    return this.page.getByRole('columnheader', { name: 'Automation' });
  }

  /** Open the create form from the list's own "Create queue" button. */
  async openCreateQueueForm(): Promise<AnnotationQueueFormSheet> {
    return test.step('Open the create annotation queue form', async () => {
      await this.page.getByRole('button', { name: 'Create queue' }).first().click();
      const sheet = AnnotationQueueFormSheet.create(this.page);
      await sheet.waitForReady();
      return sheet;
    });
  }

  /**
   * Open a queue's edit form through the row's kebab menu.
   *
   * Same scoping as `deleteQueue`: the trigger and menu items carry no
   * testids, so the row is located first and the accessible names from
   * AnnotationQueueRowActionsCell are used inside it.
   */
  async openEditQueueForm(queueId: string): Promise<AnnotationQueueFormSheet> {
    return test.step(`Open the edit form for annotation queue ${queueId}`, async () => {
      const row = this.queueRow(queueId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();

      const sheet = AnnotationQueueFormSheet.edit(this.page);
      await sheet.waitForReady();
      return sheet;
    });
  }
}

/**
 * The create/edit annotation queue form, which opens as a side sheet.
 *
 * Scoped to its own dialog rather than the page, because the queues list
 * underneath carries controls with the same accessible names (a "Create queue"
 * button, an "Automation" column header) and an unscoped locator would resolve
 * against whichever mounted first.
 */
export class AnnotationQueueFormSheet {
  constructor(
    private readonly page: Page,
    private readonly title: string,
  ) {}

  /** The form as it opens for a new queue. */
  static create(page: Page): AnnotationQueueFormSheet {
    return new AnnotationQueueFormSheet(page, 'New annotation queue');
  }

  /** The form as it opens for an existing queue. */
  static edit(page: Page): AnnotationQueueFormSheet {
    return new AnnotationQueueFormSheet(page, 'Edit annotation queue');
  }

  /** Scoped by the sheet's own heading, which is what distinguishes create from edit. */
  get root(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: this.title, exact: true }),
    });
  }

  async waitForReady(): Promise<void> {
    return test.step(`Wait for the "${this.title}" form`, async () => {
      await this.root.waitFor({ state: 'visible' });
      await this.automationSwitch.waitFor({ state: 'visible' });
    });
  }

  get nameInput(): Locator {
    return this.root.getByRole('textbox', { name: 'Name', exact: true });
  }

  /** Radix Switch, addressed by the aria-label the form sets on it. */
  get automationSwitch(): Locator {
    return this.root.getByRole('switch', { name: 'Enable automation' });
  }

  /**
   * One Scope option. Radix exposes a single-select ToggleGroup as radios, so
   * the option's checked state is the selection and its disabled state is the
   * "scope is fixed once the queue exists" rule.
   */
  scopeOption(label: 'Traces' | 'Threads'): Locator {
    return this.root.getByRole('radio', { name: label, exact: true });
  }

  /**
   * The comparison operator of one condition, by its accessible name.
   *
   * No collision with the Scope radios above: those are named "Traces" and
   * "Threads", these "greater than" / "less than" / "equals". `nth` indexes
   * conditions in the order the form renders them.
   */
  conditionOperator(operator: '>' | '<' | '=', conditionIndex = 0): Locator {
    const label = { '>': 'greater than', '<': 'less than', '=': 'equals' }[operator];
    return this.root.getByRole('radio', { name: label, exact: true }).nth(conditionIndex);
  }

  /**
   * The numeric threshold of one condition.
   *
   * The input carries no label, so its accessible name falls back to the
   * placeholder — which is also what distinguishes it from the form's two other
   * spinbuttons ("Annotators per item" and "Lock timeout"). A `data-testid`
   * would be the better handle; it could not be added here because these specs
   * run against a pre-built deployment of the change under test, which no
   * frontend edit in this PR would reach.
   */
  conditionThreshold(conditionIndex = 0): Locator {
    return this.root
      .getByRole('spinbutton', { name: THRESHOLD_PLACEHOLDER, exact: true })
      .nth(conditionIndex);
  }

  /**
   * A condition's score select, located by the value it is showing.
   *
   * The control's accessible name *is* its current value — the chosen score, or
   * "Select score" while it is still empty — so it is addressed by what a
   * reader would see rather than by position. Matching exactly also keeps it
   * clear of the "Feedback scores (optional)" chip above, whose accessible name
   * is "<score> Remove <score>".
   */
  conditionScoreSelect(shownValue: string): Locator {
    return this.root.getByRole('button', { name: shownValue, exact: true });
  }

  /**
   * Pick a score for an as-yet-unfilled condition. The list renders in a portal
   * outside the sheet, so the option is located on the page rather than within
   * `root`.
   */
  async selectConditionScore(scoreName: string): Promise<void> {
    return test.step(`Select score "${scoreName}" for the condition`, async () => {
      await this.conditionScoreSelect(EMPTY_SCORE_LABEL).click();
      const option = this.page.getByRole('option', { name: scoreName, exact: true });
      await option.waitFor({ state: 'visible' });
      await option.click();
      // The list closes on selection; waiting for that keeps the next action
      // from landing on the overlay still covering the sheet.
      await option.waitFor({ state: 'hidden' });
    });
  }

  /** Submit the form with its own primary button ("Create queue" / "Update queue"). */
  async submit(label: 'Create queue' | 'Update queue'): Promise<void> {
    return test.step(`Submit the queue form with "${label}"`, async () => {
      await this.root.getByRole('button', { name: label, exact: true }).click();
      // The sheet stays open and raises a toast when the save is rejected, so
      // its disappearance is the signal the write was accepted — not a fixed
      // wait, and not merely that the click landed.
      await this.root.waitFor({ state: 'hidden' });
    });
  }
}

export class AnnotationQueuePage {
  constructor(private readonly page: Page) {}

  async goto(projectId: string, queueId: string): Promise<void> {
    return test.step(`Open annotation queue ${queueId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/annotation-queues/${queueId}`,
      );
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for annotation queue page ready', async () => {
      await this.queueItemsTab.waitFor({ state: 'visible' });
    });
  }

  /**
   * The detail shell's items tab. Present for ANY queue id, valid or not — the
   * tabs render independently of the queue fetch — so it confirms the shell
   * mounted, never that the queue exists.
   */
  get queueItemsTab(): Locator {
    return this.page.getByRole('tab', { name: 'Queue items' });
  }

  /**
   * Asserts the browser is on this queue's detail route.
   *
   * Anchored on the full origin + workspace-scoped pathname, not a substring:
   * an unanchored match would also accept a different workspace, an extra path
   * prefix, or another host entirely, none of which mean navigation landed on
   * the requested queue. Query strings are allowed (the page writes `tab=`).
   */
  async expectOnQueueRoute(projectId: string, queueId: string): Promise<void> {
    return test.step(`Assert URL is the detail route for queue ${queueId}`, async () => {
      const env = loadEnvConfig();
      // Both sides go through URL so the comparison is on canonical form. Without
      // it, a baseUrl that carries a trailing slash, an explicit default port
      // (:80/:443), mixed-case host, or a workspace needing percent-encoding
      // would fail a perfectly valid navigation — env-provided baseUrls (cloud,
      // self-hosted) legitimately arrive in any of those shapes.
      const expected = new URL(
        `${env.workspace}/projects/${projectId}/annotation-queues/${queueId}`,
        env.baseUrl.endsWith('/') ? env.baseUrl : `${env.baseUrl}/`,
      );
      await expect(this.page).toHaveURL((url) => {
        const strip = (p: string) => p.replace(/\/+$/, '');
        return url.origin === expected.origin && strip(url.pathname) === strip(expected.pathname);
      });
    });
  }

  /**
   * A not-found signal for a queue that doesn't exist, matched on intent rather
   * than exact copy: any acceptable fix must say the queue is unavailable, but
   * is free to word it differently from the SME route's NoDataView.
   *
   * Deliberately excludes generic load-failure copy ("unable to load", "failed
   * to load"): an items-fetch error would satisfy that while the queue itself is
   * fine, so it would let an unrelated failure masquerade as the not-found state.
   * Every alternative below asserts something about the QUEUE's existence.
   */
  get notFoundState(): Locator {
    return this.page
      .getByText(/queue (is )?not available|queue not found|no longer exists|may not exist/i)
      .first();
  }

  /**
   * One item's row on the Queue items tab, pinned by the id DataTable stamps as
   * the row id.
   */
  itemRow(itemId: string): Locator {
    return this.page.locator(`tr[data-row-id="${itemId}"]`);
  }

  /**
   * The Source cell of one queue item — whether a person added it or automation
   * matched it.
   *
   * By `data-cell-id` rather than position: this table's columns are
   * user-configurable and persisted, so a positional cell selector would read a
   * different column for anyone who has reordered them.
   */
  itemSourceCell(itemId: string): Locator {
    return this.itemRow(itemId).locator('td[data-cell-id$="_queue_item_source"]');
  }

  /**
   * Header of the Source column, to confirm the column rendered at all.
   *
   * Anchored at the start rather than matched exactly: every header on this
   * table appends its column stat to its label, so this one's accessible name
   * is "Source -" (a string column has no aggregate) and would change to
   * something else the moment one were added.
   */
  get sourceColumnHeader(): Locator {
    return this.page.getByRole('columnheader', { name: /^Source\b/ });
  }

  /**
   * Wait for the items table to have settled into rows or an empty state.
   *
   * Racing the two matters because a queue that routed nothing renders no
   * table at all — waiting on a row alone would burn the test budget here
   * instead of failing on the assertion that explains why.
   */
  async waitForItemsReady(): Promise<void> {
    return test.step('Wait for the queue items table', async () => {
      await this.waitForReady();
      await Promise.race([
        this.page.locator('tr[data-row-id]').first().waitFor({ state: 'visible' }),
        this.page
          .getByText(/no items|no traces/i)
          .first()
          .waitFor({ state: 'visible' }),
      ]);
    });
  }

  /**
   * Open a queue item's trace panel by navigating directly with a `trace` query
   * param — the same pattern LogsPage uses. Avoids depending on table row
   * selectors for a table whose row set changes as items are scored.
   */
  async openItem(projectId: string, queueId: string, traceId: string): Promise<TracePanelPage> {
    return test.step(`Open queue item ${traceId}`, async () => {
      const env = loadEnvConfig();
      const url = `${env.baseUrl}/${env.workspace}/projects/${projectId}/annotation-queues/${queueId}?trace=${traceId}`;
      await this.page.goto(url);
      const panel = new TracePanelPage(this.page, traceId);
      await panel.waitForFullyLoaded();
      return panel;
    });
  }
}
