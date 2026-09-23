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
   * The row's Automation cell — "On" or "Off", per `AutomationCell`.
   *
   * Addressed through the shared DataTable's `data-cell-id`
   * (`<rowId>_<columnId>`) rather than by column position: this table's columns
   * are user-reorderable and the order is persisted in localStorage, so an
   * nth-child path reads whatever column happens to sit there.
   */
  automationCell(queueId: string): Locator {
    return this.page.locator(`td[data-cell-id="${queueId}_automation"]`);
  }

  /**
   * The page header's create button. `exact` because the sheet this opens
   * carries a submit button of the same name — scoping to the header would not
   * help, since the sheet renders outside it in a portal.
   */
  get createQueueButton(): Locator {
    return this.page.getByRole('button', { name: 'Create queue', exact: true });
  }

  /** Open the create form from the page header's "Create queue" button. */
  async openCreateForm(): Promise<AnnotationQueueFormSheet> {
    return test.step('Open the create annotation queue form', async () => {
      await this.createQueueButton.click();
      const sheet = new AnnotationQueueFormSheet(this.page, 'create');
      await sheet.waitForReady();
      return sheet;
    });
  }

  /** Open a queue's edit form through its row actions menu. */
  async openEditForm(queueId: string): Promise<AnnotationQueueFormSheet> {
    return test.step(`Open the edit form for annotation queue ${queueId}`, async () => {
      const row = this.queueRow(queueId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      const sheet = new AnnotationQueueFormSheet(this.page, 'edit');
      await sheet.waitForReady();
      return sheet;
    });
  }
}

/** Which of the two titles / submit labels `AddEditAnnotationQueueDialog` is showing. */
export type QueueFormMode = 'create' | 'edit';

const FORM_TITLE: Record<QueueFormMode, string> = {
  create: 'New annotation queue',
  edit: 'Edit annotation queue',
};

const FORM_SUBMIT_LABEL: Record<QueueFormMode, string> = {
  create: 'Create queue',
  edit: 'Update queue',
};

/**
 * The create/edit annotation queue sheet (`AddEditAnnotationQueueDialog`),
 * including the automation condition builder it embeds.
 *
 * The sheet is scoped by its own title so nothing here can match the list page
 * behind it — notably the header's "Create queue" button, which shares its
 * accessible name with the sheet's submit button.
 */
export class AnnotationQueueFormSheet {
  constructor(
    private readonly page: Page,
    private readonly mode: QueueFormMode,
  ) {}

  get root(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: FORM_TITLE[this.mode], exact: true }),
    });
  }

  async waitForReady(): Promise<void> {
    return test.step(`Wait for the ${this.mode} annotation queue form`, async () => {
      await this.root.waitFor({ state: 'visible' });
    });
  }

  get nameInput(): Locator {
    return this.root.getByLabel('Name', { exact: true });
  }

  get instructionsInput(): Locator {
    return this.root.getByLabel('Instructions (optional)', { exact: true });
  }

  /** The scope segmented control's option for `label` ("Traces" / "Threads"). */
  scopeOption(label: 'Traces' | 'Threads'): Locator {
    return this.root.getByRole('radio', { name: label, exact: true });
  }

  get automationSwitch(): Locator {
    return this.root.getByRole('switch', { name: 'Enable automation' });
  }

  /**
   * The automation card's explanatory line. Its subject follows the scope
   * ("traces" vs "threads"), which is the only place the form states which one
   * the automation will actually collect.
   */
  get automationDescription(): Locator {
    return this.root.getByText(/Set conditions to automatically add matching (traces|threads)/);
  }

  /**
   * The condition builder's group captions ("Group 1", "Group 2", …).
   *
   * The caption is what names a group to the user and it is the only handle a
   * group carries: `FeedbackScoreConditions` renders every group from the same
   * markup with no id, role or testid of its own. Counting and reading these is
   * therefore how a spec asserts how many groups are rendered and in what
   * order — a group's own controls are reached through the flattened accessors
   * below instead. A `data-testid` per group would be better and should be
   * added, but the environments these specs are verified against serve a
   * prebuilt frontend, so one added here could not be exercised before merge.
   */
  get groupCaptions(): Locator {
    return this.root.getByText(/^Group \d+$/);
  }

  /**
   * Every condition row's threshold input, in render order.
   *
   * Matched on the placeholder rather than on `input[type=number]`: the form's
   * "Annotators per item" and "Lock timeout" steppers are number inputs too.
   * Render order is group order, then position within the group, which is the
   * order the automation's `conditions.groups` is stored and read back in — so
   * `nth(i)` is addressing the i-th condition, not the i-th thing on screen.
   */
  get conditionThresholds(): Locator {
    return this.root.getByPlaceholder('0.7');
  }

  /**
   * Every condition row's option for `operator`, in the same order.
   *
   * Matched on the accessible name (`OPERATOR_LABELS`) rather than on the
   * glyph: ">" and "<" are single characters that a text match finds all over
   * the row.
   */
  conditionOperators(operator: '<' | '>' | '='): Locator {
    const labels = { '<': 'less than', '>': 'greater than', '=': 'equals' } as const;
    return this.root.getByRole('radio', { name: labels[operator], exact: true });
  }

  /**
   * Every condition row whose score has been chosen and reads `scoreName`.
   *
   * The score picker is a popover trigger button whose accessible name IS the
   * chosen score (or "Select score" while empty), so this is both the handle
   * and the assertion: a rehydration that lost the score renders "Select score"
   * and matches nothing here. `exact` keeps it off the queue's own "Feedback
   * scores" field, whose empty trigger reads "Select scores".
   */
  conditionScores(scoreName: string): Locator {
    return this.root.getByRole('button', { name: scoreName, exact: true });
  }

  /** Condition rows that still have no score chosen. */
  get emptyConditionScores(): Locator {
    return this.root.getByRole('button', { name: 'Select score', exact: true });
  }

  /**
   * Configure the condition row rendered last — the one `addOrGroup()` just
   * appended, or the single blank row the builder opens with.
   *
   * Addressed as "last" rather than by index because that is what the builder
   * guarantees: `useFieldArray.append` puts a new group at the end, and its
   * blank condition with it.
   */
  async fillLastCondition(condition: {
    scoreName: string;
    operator: '<' | '>' | '=';
    threshold: string;
  }): Promise<void> {
    return test.step(
      `Set the last condition to ${condition.scoreName} ${condition.operator} ${condition.threshold}`,
      async () => {
        await this.emptyConditionScores.last().click();
        await this.page.getByRole('option', { name: condition.scoreName, exact: true }).click();
        await this.conditionOperators(condition.operator).last().click();
        await this.conditionThresholds.last().fill(condition.threshold);
      },
    );
  }

  /** Append a second (third, …) OR-ed group, pre-filled with one blank condition. */
  async addOrGroup(): Promise<void> {
    return test.step('Add an OR group to the automation', async () => {
      const before = await this.groupCaptions.count();
      await this.root.getByRole('button', { name: 'Add OR group' }).click();
      await expect(
        this.groupCaptions,
        'the new group is rendered before the test configures it',
      ).toHaveCount(before + 1);
    });
  }

  get submitButton(): Locator {
    return this.root.getByRole('button', { name: FORM_SUBMIT_LABEL[this.mode], exact: true });
  }

  /**
   * Submit, and require the sheet to close.
   *
   * `AddEditAnnotationQueueDialog` closes only in the mutation's `onSuccess`, so
   * a closed sheet is proof the write was accepted — not merely that the click
   * landed. A rejected write leaves the sheet open and raises a toast, which is
   * what `errorToast` is for.
   */
  async submit(): Promise<void> {
    return test.step(`Submit the ${this.mode} annotation queue form`, async () => {
      await this.submitButton.click();
      await expect(
        this.root,
        'the form closes once the queue write is accepted',
      ).toBeHidden();
    });
  }

  /**
   * The destructive toast a rejected write raises.
   *
   * Scoped to the notifications region because Radix also renders a
   * visually-hidden announcer carrying the same text.
   */
  get errorToast(): Locator {
    return this.page
      .getByRole('region', { name: 'Notifications (F8)' })
      .getByRole('status')
      .filter({ hasText: /Error/i });
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
