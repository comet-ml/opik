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

  /** Open the create dialog from the page's "Create queue" button. */
  async openCreateDialog(): Promise<AnnotationQueueFormDialog> {
    return test.step('Open the create-queue dialog', async () => {
      await this.page.getByRole('button', { name: 'Create queue' }).click();
      const dialog = new AnnotationQueueFormDialog(this.page, 'create');
      await dialog.waitForReady();
      return dialog;
    });
  }

  /**
   * Open the edit dialog from a row's kebab menu. Same menu as `deleteQueue`,
   * so the confirm-dialog caveat there applies: scope to the row first, then use
   * the accessible names from `AnnotationQueueRowActionsCell`.
   */
  async openEditDialog(queueId: string): Promise<AnnotationQueueFormDialog> {
    return test.step(`Open the edit dialog for queue ${queueId}`, async () => {
      const row = this.queueRow(queueId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      const dialog = new AnnotationQueueFormDialog(this.page, 'edit');
      await dialog.waitForReady();
      return dialog;
    });
  }
}

/**
 * The shared create/edit annotation-queue dialog (`AddEditAnnotationQueueDialog`).
 *
 * Create and edit are one component with two sets of copy, so the mode picks the
 * title and the submit label — note the submit button is "Create annotation
 * queue" / "Update annotation queue", not "Create"/"Save". Fields are addressed
 * by their form labels, which is the stable handle here: the dialog carries no
 * data-testids and its inputs are plain shadcn `FormField`s.
 */
export class AnnotationQueueFormDialog {
  constructor(
    private readonly page: Page,
    private readonly mode: 'create' | 'edit',
  ) {}

  private get title(): string {
    return this.mode === 'edit' ? 'Edit annotation queue' : 'Create a new annotation queue';
  }

  /** Scoped to this dialog's own title, so it can never resolve to another one. */
  get root(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByText(this.title, { exact: true }),
    });
  }

  async waitForReady(): Promise<void> {
    return test.step(`Wait for the ${this.mode} annotation-queue dialog`, async () => {
      await this.nameInput.waitFor({ state: 'visible' });
    });
  }

  get nameInput(): Locator {
    return this.root.getByLabel('Name', { exact: true });
  }

  /** The annotator-instructions textarea — the dialog's free-text field. */
  get instructionsInput(): Locator {
    return this.root.getByLabel('Instructions', { exact: true });
  }

  get submitButton(): Locator {
    return this.root.getByRole('button', {
      name: this.mode === 'edit' ? 'Update annotation queue' : 'Create annotation queue',
      exact: true,
    });
  }

  /** The name field's validation message, shown when the trimmed name is empty. */
  get nameRequiredMessage(): Locator {
    return this.root.getByText('Name is required', { exact: true });
  }

  async fill(fields: { name?: string; instructions?: string }): Promise<void> {
    return test.step('Fill the annotation-queue dialog', async () => {
      if (fields.name !== undefined) await this.nameInput.fill(fields.name);
      if (fields.instructions !== undefined) {
        await this.instructionsInput.fill(fields.instructions);
      }
    });
  }

  async submit(): Promise<void> {
    return test.step(`Submit the ${this.mode} annotation-queue dialog`, async () => {
      await this.submitButton.click();
    });
  }

  /**
   * Double-click submit, as a user with a heavy hand does.
   *
   * `dblclick` sends two real clicks milliseconds apart — well inside the create
   * request's round trip — so the second lands while the first is still in
   * flight. That is the state `disabled={isSubmitting}` exists to cover, and it
   * is asserted through its outcome (how many queues exist) rather than by
   * catching the button mid-flip, which would be a race against the network.
   */
  async doubleSubmit(): Promise<void> {
    return test.step('Double-click submit', async () => {
      await this.submitButton.dblclick();
    });
  }

  async expectClosed(): Promise<void> {
    return test.step(`Expect the ${this.mode} annotation-queue dialog to close`, async () => {
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
