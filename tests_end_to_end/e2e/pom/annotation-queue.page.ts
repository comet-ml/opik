import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TracePanelPage } from './trace-panel.page';

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
   * The list's Automation cell for one queue, reading "On" or "Off".
   *
   * Addressed by `data-cell-id`, which `DataTable` stamps as
   * `<rowId>_<columnId>` — column order here is user-configurable and persisted
   * per workspace, so any positional lookup would break the moment someone
   * reorders the table.
   */
  automationCell(queueId: string): Locator {
    return this.page.locator(`[data-cell-id="${queueId}_automation"]`);
  }

  /**
   * Wait for a queue's row to appear in the list, reloading between attempts.
   *
   * The listing lags the write that created the queue, and `waitForReady` races
   * a row against the empty state — so on a project whose only queue was just
   * created, the empty state can win and leave the page settled on a table that
   * will never contain the row. The query has no refetch interval, so re-asking
   * means reloading.
   */
  async waitForQueueRow(queueId: string, timeoutMs = 60_000): Promise<void> {
    return test.step(`Wait for annotation queue row ${queueId}`, async () => {
      await expect(async () => {
        if ((await this.queueRow(queueId).count()) === 0) {
          await this.page.reload();
          await this.waitForReady();
        }
        await expect(this.queueRow(queueId)).toHaveCount(1);
      }).toPass({ timeout: timeoutMs });
    });
  }

  /** Opens the create form from the list header. */
  async openCreateForm(): Promise<AnnotationQueueFormPage> {
    return test.step('Open the create annotation queue form', async () => {
      await this.page.getByRole('button', { name: 'Create queue' }).click();
      const form = new AnnotationQueueFormPage(this.page);
      await form.waitForReady();
      return form;
    });
  }

  /**
   * Opens a queue's edit form through its row actions menu.
   *
   * Same menu the delete action uses; neither the kebab nor the menu items carry
   * data-testids, so both go through the accessible names in
   * `AnnotationQueueRowActionsCell`.
   */
  async openEditForm(queueId: string): Promise<AnnotationQueueFormPage> {
    return test.step(`Open the edit form for annotation queue ${queueId}`, async () => {
      const row = this.queueRow(queueId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      const form = new AnnotationQueueFormPage(this.page);
      await form.waitForReady();
      return form;
    });
  }
}

/**
 * The create/edit annotation queue sheet (`AddEditAnnotationQueueDialog`).
 *
 * One class for both, as the component is one: they differ only in heading and
 * submit label, and the create→edit round trip is what the specs assert on.
 */
export class AnnotationQueueFormPage {
  constructor(private readonly page: Page) {}

  /**
   * The sheet, scoped by its own submit button rather than by its heading.
   *
   * The sheet can be raised from inside the "Add to annotation queue" dialog, so
   * more than one dialog may be mounted; the submit label is the one thing only
   * this form carries.
   */
  get sheet(): Locator {
    return this.page.getByRole('dialog').filter({ has: this.submitButtonInDocument });
  }

  private get submitButtonInDocument(): Locator {
    return this.page.getByRole('button', { name: /^(Create|Update) queue$/ });
  }

  /** "Create queue" when adding, "Update queue" when editing. */
  get submitButton(): Locator {
    return this.sheet.getByRole('button', { name: /^(Create|Update) queue$/ });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for the annotation queue form ready', async () => {
      await this.submitButton.waitFor({ state: 'visible' });
    });
  }

  get nameInput(): Locator {
    return this.sheet.getByLabel('Name', { exact: true });
  }

  get automationSwitch(): Locator {
    return this.sheet.getByRole('switch', { name: 'Enable automation' });
  }

  /** The Scope toggle group renders its options as radios, one per scope. */
  scopeOption(scope: 'Traces' | 'Threads'): Locator {
    return this.sheet.getByRole('radio', { name: scope });
  }

  async fillName(name: string): Promise<void> {
    return test.step(`fill the queue name "${name}"`, async () => {
      await this.nameInput.fill(name);
    });
  }

  /**
   * Submit the form and require the write to be accepted.
   *
   * The sheet is closed only in the mutation's `onSuccess`, so its disappearance
   * is proof the write landed — not merely that the click did. Exactly one of
   * two things follows a submit: the sheet closes, or an "Error" toast carrying
   * the server's message appears.
   *
   * Raced rather than waited out one at a time. Radix dismisses the toast after
   * its 5s default, so an assertion that waits ~10s on the sheet reports a bare
   * "still open" long after the message explaining why has gone — and then a
   * follow-up assertion on the toast finds nothing and passes, which is worse
   * than useless. Racing reports the rejection WITH its reason.
   */
  async submitExpectingSuccess(): Promise<void> {
    return test.step('submit the annotation queue form and expect it to be accepted', async () => {
      await this.submitButton.click();

      const outcome = await Promise.race([
        this.sheet.waitFor({ state: 'detached' }).then(() => 'accepted' as const),
        this.errorToast.first().waitFor({ state: 'visible' }).then(() => 'rejected' as const),
      ]);

      if (outcome === 'rejected') {
        const reason = (await this.errorToast.first().innerText()).replace(/\s+/g, ' ').trim();
        throw new Error(`the annotation queue form rejected the write: ${reason}`);
      }
    });
  }

  /**
   * The toast a rejected create/update raises: title "Error", the axios message
   * as its description. Neither mutation raises a success toast.
   *
   * Addressed by CSS rather than by `getByRole`, unlike
   * `PlaygroundPage.completionToast`. This form is a MODAL sheet, and Radix
   * marks the rest of the document `aria-hidden` while it is open — which takes
   * the toast viewport out of the accessibility tree exactly when it matters,
   * because a rejected submit is the case that leaves the sheet up. A role-based
   * lookup finds nothing there and reports it as "no error", the most misleading
   * answer available. Still scoped to the viewport, since Radix also renders a
   * visually-hidden announcer carrying the same text.
   */
  get errorToast(): Locator {
    return this.page
      .locator('[role="region"][aria-label^="Notification"] li[role="status"]')
      .filter({ hasText: 'Error' });
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

  /** One row of the Queue items table, pinned to the trace or thread it shows. */
  itemRow(itemId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id="${itemId}"]`);
  }

  /**
   * Wait for an item's row to appear in the Queue items table, reloading between
   * attempts.
   *
   * The table does not read queue membership directly: it runs the traces query
   * filtered by the queue, and that read lags the membership write. A queue whose
   * `items/search` already reports two items can still render "No items to
   * review" here for a few seconds. The query has no refetch interval, so
   * re-asking means reloading — waiting on the locator alone just watches a
   * settled empty table until it times out.
   */
  async waitForItemRow(itemId: string, timeoutMs = 60_000): Promise<void> {
    return test.step(`Wait for queue item row ${itemId}`, async () => {
      await expect(async () => {
        if ((await this.itemRow(itemId).count()) === 0) {
          await this.page.reload();
          await this.waitForReady();
        }
        await expect(this.itemRow(itemId)).toHaveCount(1);
      }).toPass({ timeout: timeoutMs });
    });
  }

  /**
   * The Queue items table's Source cell for one item, reading "Automated" or
   * "Manual".
   *
   * `queue_item_source` is the column id `createQueueItemSourceColumn` assigns;
   * `DataTable` stamps cells as `<rowId>_<columnId>`. The cell renders EMPTY
   * while the membership lookup is in flight, so assert on its text rather than
   * on its presence.
   */
  itemSourceCell(itemId: string): Locator {
    return this.page.locator(`[data-cell-id="${itemId}_queue_item_source"]`);
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
