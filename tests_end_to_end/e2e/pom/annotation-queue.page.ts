import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TracePanelPage } from './trace-panel.page';

/**
 * Column ids from the frontend's own definitions — `AUTOMATION_COLUMN_ID` in
 * `AnnotationQueuesPage.tsx` and `QUEUE_ITEM_SOURCE_COLUMN_ID` in
 * `queueItemSourceColumn.ts`. They form the second half of DataTable's
 * `data-cell-id`, so a rename there is what should break these locators.
 */
const AUTOMATION_COLUMN_ID = 'automation';
const QUEUE_ITEM_SOURCE_COLUMN_ID = 'queue_item_source';

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
   * The row's Automation cell — `AutomationCell`'s pill, which reads `On`,
   * `Off`, or `Cap reached`.
   *
   * Addressed by `data-cell-id` (`<rowId>_<columnId>`, stamped by DataTable)
   * rather than by position: the queues list's column order is user-configurable
   * and persisted in local storage, so an `nth-child` would be reading whichever
   * column happened to sit there.
   *
   * The pill is worth asserting separately from the API's own numbers because
   * the frontend DERIVES it — `items_count >= max_items_in_queue` — so a queue
   * that has hit its ceiling server-side can still render `On`, and that
   * disagreement is invisible to any API-level check.
   */
  automationCell(queueId: string): Locator {
    return this.page.locator(`td[data-cell-id="${queueId}_${AUTOMATION_COLUMN_ID}"]`);
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
   * Wait for the items tab to have settled on either rows or its empty state.
   *
   * `waitForReady` above only proves the tab strip mounted — the tabs render
   * independently of the queue fetch, so it resolves for a queue id that does
   * not exist. A spec asserting a row is ABSENT needs to know the table
   * finished, which is what racing a real row against "No items to review"
   * establishes.
   */
  async waitForItemsReady(): Promise<void> {
    return test.step('Wait for the queue items table ready', async () => {
      await this.queueItemsTab.waitFor({ state: 'visible' });
      const realRow = this.page.locator('tbody tr[data-row-id]').first();
      await Promise.race([
        realRow.waitFor({ state: 'visible' }),
        this.itemsEmptyState.waitFor({ state: 'visible' }),
      ]);
    });
  }

  /** The items table's own empty state, from `TraceQueueItemsTab`'s `noData`. */
  get itemsEmptyState(): Locator {
    return this.page.getByText('No items to review');
  }

  /**
   * An item row, scoped by the id of the trace (or thread) behind it — the
   * table's `getRowId` is the entity's own id, so this pins the row even while
   * sibling rows come and go as automation fills the queue.
   */
  itemRow(entityId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id="${entityId}"]`);
  }

  /**
   * An item row's Source cell — `QueueItemSourceCell`'s pill, `Manual` or
   * `Automated`.
   *
   * Note the cell renders EMPTY while the membership lookup is in flight and
   * stays empty if it fails, so asserting its text (rather than merely that the
   * cell exists) is what distinguishes "the column says Automated" from "the
   * column never loaded".
   */
  itemSourceCell(entityId: string): Locator {
    return this.page.locator(`td[data-cell-id="${entityId}_${QUEUE_ITEM_SOURCE_COLUMN_ID}"]`);
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
