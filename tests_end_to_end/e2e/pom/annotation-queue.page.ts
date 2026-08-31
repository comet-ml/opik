import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TracePanelPage } from './trace-panel.page';

/**
 * The create/edit annotation queue dialog — one component
 * (`AddEditAnnotationQueueDialog`) reached from three entry points: the queues
 * list "Create queue" button, a queue row's Edit action, and the queue-detail
 * "Edit" button. Only the title and submit label differ between modes, so the
 * mode is a constructor argument rather than two near-identical classes.
 */
export class AnnotationQueueDialog {
  private static readonly TITLES = {
    create: 'Create a new annotation queue',
    edit: 'Edit annotation queue',
  } as const;

  private static readonly SUBMIT_LABELS = {
    create: 'Create annotation queue',
    edit: 'Update annotation queue',
  } as const;

  constructor(
    private readonly page: Page,
    private readonly mode: 'create' | 'edit',
  ) {}

  /**
   * Scoped by its own title so the two modes never resolve to each other's
   * dialog — the row-actions cell mounts an edit dialog on every row, and the
   * list page mounts a create dialog, so `getByRole('dialog')` alone is
   * ambiguous the moment either is open.
   */
  get root(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', {
        name: AnnotationQueueDialog.TITLES[this.mode],
        exact: true,
      }),
    });
  }

  /** `exact` matters: "Number of annotators per item" also contains "Name". */
  get nameInput(): Locator {
    return this.root.getByLabel('Name', { exact: true });
  }

  get submitButton(): Locator {
    return this.root.getByRole('button', {
      name: AnnotationQueueDialog.SUBMIT_LABELS[this.mode],
      exact: true,
    });
  }

  /** The zod `.trim().min(1)` message, rendered by the Name field's FormMessage. */
  get nameRequiredError(): Locator {
    return this.root.getByText('Name is required', { exact: true });
  }

  async waitForReady(): Promise<void> {
    return test.step(`Wait for the ${this.mode} annotation queue dialog`, async () => {
      await this.nameInput.waitFor({ state: 'visible' });
    });
  }

  async fillName(name: string): Promise<void> {
    return test.step(`Set the queue name to "${name}"`, async () => {
      await this.nameInput.fill(name);
    });
  }

  async submit(): Promise<void> {
    return test.step(`Submit the ${this.mode} annotation queue dialog`, async () => {
      await this.submitButton.click();
    });
  }

  /**
   * Submit, then wait for the dialog to go away.
   *
   * The dialog now closes only in the mutation's `onSuccess` (opik#8056), so
   * "hidden" is a real signal that the write landed — not, as before, a
   * synchronous close that happened whatever the server answered.
   */
  async submitAndExpectClosed(): Promise<void> {
    return test.step(`Submit the ${this.mode} dialog and wait for it to close`, async () => {
      await this.submitButton.click();
      await expect(this.root).toBeHidden();
    });
  }
}

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
   * The header "Create queue" button, which renders whether or not the project
   * already has queues — unlike the empty state's "Create your first queue",
   * which disappears as soon as one exists.
   */
  async openCreateDialog(): Promise<AnnotationQueueDialog> {
    return test.step('Open the create annotation queue dialog', async () => {
      await this.page.getByRole('button', { name: 'Create queue', exact: true }).click();
      const dialog = new AnnotationQueueDialog(this.page, 'create');
      await dialog.waitForReady();
      return dialog;
    });
  }

  /**
   * Open a queue's edit dialog from its row kebab menu. Scoped by row first, the
   * same way `deleteQueue` is, so the menu belongs to the queue under test.
   */
  async openEditDialog(queueId: string): Promise<AnnotationQueueDialog> {
    return test.step(`Open the edit dialog for annotation queue ${queueId}`, async () => {
      const row = this.queueRow(queueId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      const dialog = new AnnotationQueueDialog(this.page, 'edit');
      await dialog.waitForReady();
      return dialog;
    });
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
   * The page's `<h1>`, which renders the queue name in full.
   *
   * This is the only place in the UI that does: the list's Name column
   * truncates the text server-side of the ellipsis, so a padded name and its
   * trimmed twin render identically there and the row label cannot tell them
   * apart.
   */
  get queueNameHeading(): Locator {
    return this.page.getByRole('heading', { level: 1 });
  }

  /**
   * Open the edit dialog from the detail page's own Edit button — a second
   * mount of the same dialog as the list's row action, and a separate entry
   * point that can regress on its own.
   */
  async openEditDialog(): Promise<AnnotationQueueDialog> {
    return test.step('Open the edit dialog from the queue detail page', async () => {
      await this.page.getByRole('button', { name: 'Edit', exact: true }).click();
      const dialog = new AnnotationQueueDialog(this.page, 'edit');
      await dialog.waitForReady();
      return dialog;
    });
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
