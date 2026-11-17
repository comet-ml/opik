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

  /**
   * The header's Create queue button.
   *
   * Rendered only for a user holding `canCreateAnnotationQueues`, so a spec
   * that cannot find it on a workspace where the role is missing fails here
   * naming the button, rather than later as a sheet that never opened.
   */
  get createQueueButton(): Locator {
    return this.page.getByRole('button', { name: 'Create queue', exact: true });
  }

  /**
   * Open the create-queue sheet from the list header, and wait for the
   * annotator card's own sample query to have answered.
   *
   * The wait is not optional politeness. `AnnotatorViewCard` branches on
   * `!isSamplesPending && !samples.length`, so WHILE the query is in flight it
   * renders the has-samples card — identical, text and all, to the one it
   * settles on when the project really does have traces. There is no DOM
   * difference between "still loading" and "loaded, with samples", so a spec
   * reading the card the instant the sheet opens would report the has-samples
   * state for an empty project (measured on staging 2.2.133: the no-data case
   * failed with Preview + Out of Ollie credits) and, worse, would pass over a
   * build whose query never found the traces at all.
   *
   * Hence a settled response rather than an element: the one honest signal.
   * See {@link AnnotationQueueFormSheet.isAnnotatorSampleQuery} for why the
   * page size is part of the match.
   */
  async openCreateQueueDialog(): Promise<AnnotationQueueFormSheet> {
    return test.step('open the Create queue sheet', async () => {
      // Armed before the click: the sheet mounts and fires the query
      // immediately, so subscribing afterwards would race it.
      const sampled = this.page.waitForResponse(
        (response) => AnnotationQueueFormSheet.isAnnotatorSampleQuery(response.request()),
        { timeout: 30_000 },
      );
      await this.createQueueButton.click();
      const sheet = new AnnotationQueueFormSheet(this.page, 'create');
      await sheet.waitForOpen();
      await sampled;
      return sheet;
    });
  }

  /**
   * Open one queue's edit sheet through the row's kebab menu.
   *
   * The same route a user takes, and the only one: the row click opens the
   * queue's detail page, not the form. The menu items carry no testids — the
   * accessible names come from `AnnotationQueueRowActionsCell`, the same place
   * {@link deleteQueue} takes "Delete" from.
   */
  async openEditQueueDialog(queueId: string): Promise<AnnotationQueueFormSheet> {
    return test.step(`open the edit sheet for queue ${queueId}`, async () => {
      const row = this.queueRow(queueId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      const sampled = this.page.waitForResponse(
        (response) => AnnotationQueueFormSheet.isAnnotatorSampleQuery(response.request()),
        { timeout: 30_000 },
      );
      await this.page.getByRole('menuitem', { name: 'Edit', exact: true }).click();
      // Gone from the DOM, not merely hidden: the menu animates out over the
      // sheet and clicks land on it until it is really gone — the same wait
      // `OnlineEvaluationPage.openCreateRulePanel` makes, for the same reason.
      await expect(this.page.getByRole('menu')).toHaveCount(0);
      const sheet = new AnnotationQueueFormSheet(this.page, 'edit');
      await sheet.waitForOpen();
      // Same settling wait as the create sheet, and for the same reason — see
      // `openCreateQueueDialog`.
      await sampled;
      return sheet;
    });
  }
}

/** Which of the two sheets `AddEditAnnotationQueueDialog` is rendering. */
export type QueueFormMode = 'create' | 'edit';

/**
 * The sheet's heading and submit label per mode, from
 * `AddEditAnnotationQueueDialog`'s own `title` / `submitText`.
 *
 * Both are kept here as data rather than inlined at each call site because the
 * EDIT submit was renamed "Update queue" -> "Save changes" in opik#9259, and a
 * spec asserting the rename needs to name the label it expects AND the one it
 * must not find.
 */
const QUEUE_FORM_HEADING: Record<QueueFormMode, string> = {
  create: 'Create annotation queue',
  edit: 'Edit annotation queue',
};

const QUEUE_FORM_SUBMIT: Record<QueueFormMode, string> = {
  create: 'Create queue',
  edit: 'Save changes',
};

/** The submit label this sheet used to carry in edit mode, before opik#9259. */
export const RETIRED_QUEUE_FORM_SUBMIT = 'Update queue';

/**
 * `PREVIEW_SAMPLE_SIZE` from `AnnotatorView/annotatorViewData.ts` — the page
 * size the annotator card asks the traces endpoint for, which is what tells its
 * query apart from every other read of the same endpoint.
 */
const ANNOTATOR_PREVIEW_SAMPLE_SIZE = 3;

/**
 * The create / edit annotation-queue sheet — `AddEditAnnotationQueueDialog`,
 * reached from the list header or a row's Edit action.
 *
 * Scoped to the sheet rather than the page throughout: the list behind it keeps
 * its own "Create queue" button mounted, which has the same accessible name as
 * the create sheet's submit, so a page-wide lookup would resolve to whichever
 * came first in the DOM.
 */
export class AnnotationQueueFormSheet {
  constructor(
    private readonly page: Page,
    private readonly mode: QueueFormMode,
  ) {}

  /**
   * Whether a request is the annotator card's own trace-sample query.
   *
   * `GET /v1/private/traces/` with `size=PREVIEW_SAMPLE_SIZE` — the page size is
   * part of the match on purpose, because the Logs table and the queue-items
   * table read the same endpoint at their own sizes and a path-only match would
   * be satisfied by whichever of them answered first.
   *
   * On the EDIT sheet two such queries can fire: `useAnnotatorTracePreviewSamples`
   * asks for the queue's own items first and falls back to the project's traces
   * when that comes back empty. A caller that awaits one response has therefore
   * only seen the first; that is enough for the edit sheet, which asserts the
   * card RENDERS rather than which sample branch it settled on, and the one
   * spec that does assert the branch opens the CREATE sheet, where there is no
   * queue id and so only ever one query.
   */
  static isAnnotatorSampleQuery(request: { method(): string; url(): string }): boolean {
    if (request.method() !== 'GET') return false;
    const url = new URL(request.url());
    return (
      /\/v1\/private\/traces\/?$/.test(url.pathname) &&
      url.searchParams.get('size') === String(ANNOTATOR_PREVIEW_SAMPLE_SIZE)
    );
  }

  /**
   * The sheet itself, pinned by its own heading.
   *
   * `SheetContent` is a Radix `Dialog.Content` (role `dialog`) and `SheetTitle`
   * a `Dialog.Title`, which renders as a heading — so the heading is what tells
   * the create sheet from the edit one, and both from the delete ConfirmDialog
   * the same list raises.
   */
  get sheet(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', {
        name: QUEUE_FORM_HEADING[this.mode],
        exact: true,
      }),
    });
  }

  async waitForOpen(): Promise<void> {
    return test.step(`wait for the ${this.mode} queue sheet`, async () => {
      await expect(this.sheet, `the "${QUEUE_FORM_HEADING[this.mode]}" sheet`).toHaveCount(1);
      await this.nameInput.waitFor({ state: 'visible' });
    });
  }

  /** Resolves once the sheet has closed, which a submit only does once it succeeded. */
  async waitForClosed(): Promise<void> {
    return test.step(`wait for the ${this.mode} queue sheet to close`, async () => {
      await expect(this.sheet).toHaveCount(0);
    });
  }

  /** `FormLabel`'s `htmlFor` reaches the `FormControl`'s id, so both fields are labelled. */
  get nameInput(): Locator {
    return this.sheet.getByRole('textbox', { name: 'Name', exact: true });
  }

  get instructionsInput(): Locator {
    return this.sheet.getByRole('textbox', { name: 'Instructions (optional)', exact: true });
  }

  /** The submit for this mode — "Create queue" or "Save changes". */
  get submitButton(): Locator {
    return this.sheet.getByRole('button', { name: QUEUE_FORM_SUBMIT[this.mode], exact: true });
  }

  /**
   * The Annotator view card, rewritten in opik#9259.
   *
   * The card's title is the handle on it — `comet-body-s-accented` text with no
   * role and no testid — and the card ROOT has to be addressed because the
   * actions and the description below are read relative to it: a bare
   * `getByText` would hand back the title span, which contains neither.
   *
   * Hence the class. `AnnotatorViewCard` and one block of the automation
   * section are the sheet's only two `bg-soft-background` containers, and only
   * one of them holds the exact text "Annotator view" — so the pair resolves to
   * exactly one element, which {@link expectAnnotatorViewCardRendered} asserts
   * rather than assumes. A descriptive `data-testid` on the card is the right
   * fix and the conventions ask for one; this branch may only touch
   * `tests_end_to_end/`, so it is flagged here instead — the same compromise
   * `OnlineEvaluationPage.panelErrorMessages` records.
   *
   * `exact` matters: "Annotator view" is also the preview page's breadcrumb and
   * the iframe title the SME route mounts.
   */
  get annotatorViewCard(): Locator {
    return this.sheet
      .locator('div.bg-soft-background')
      .filter({ has: this.page.getByText('Annotator view', { exact: true }) });
  }

  /**
   * Assert the card is there, exactly once, with its title and some action.
   *
   * One method rather than three locator assertions at each call site because
   * every spec touching this sheet needs the same premise: a render error in
   * the rewritten card takes out queue creation entirely, and the card being
   * missing would otherwise surface as a description that reads as empty.
   */
  async expectAnnotatorViewCardRendered(): Promise<void> {
    return test.step('assert the Annotator view card rendered', async () => {
      await expect(this.annotatorViewCard, 'the Annotator view card').toHaveCount(1);
      await expect(
        this.annotatorViewCard.getByText('Annotator view', { exact: true }),
        'the card title',
      ).toBeVisible();
      await expect(
        this.annotatorViewDescription,
        'the card renders exactly one description paragraph',
      ).toHaveCount(1);
    });
  }

  /** The card's description paragraph — the copy that says what annotators see. */
  get annotatorViewDescription(): Locator {
    return this.annotatorViewCard.locator('p');
  }

  /**
   * Every action button the card currently offers, by accessible name.
   *
   * The whole list rather than a membership check: which actions appear is a
   * three-way decision (`LogTracesButton` with no samples, `Preview` plus an
   * Ollie action otherwise), and "Preview is there" and "only Preview is there"
   * are different claims — the no-data case is defined by Preview being absent.
   */
  async annotatorViewActionNames(): Promise<string[]> {
    return test.step('read the annotator view card actions', async () => {
      const names = await this.annotatorViewCard.getByRole('button').allInnerTexts();
      return names.map((name) => name.trim()).filter((name) => name.length > 0);
    });
  }

  /**
   * Submit a CREATE and return the new queue's id from the 201's `Location`.
   *
   * The header and not a name lookup: the create answers over an
   * eventually-consistent write, so a list read straight afterwards may not
   * carry the row yet — and the id is what a teardown has to be registered
   * with before anything else can fail.
   *
   * Armed before the click, because the request is in flight the moment the
   * form submits.
   */
  async submitAndCaptureQueueId(): Promise<string> {
    return test.step('submit the create queue sheet and capture the new id', async () => {
      const created = this.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          // Trailing slash optional: `ANNOTATION_QUEUES_REST_ENDPOINT` carries
          // one, and matching the bare path saw the create answered and still
          // timed out on staging 2.2.133.
          /\/v1\/private\/annotation-queues\/?$/.test(new URL(response.url()).pathname),
      );
      await this.submitButton.click();
      const response = await created;
      expect(response.status(), `the queue was created (got ${response.status()})`).toBe(201);
      const location = response.headers()['location'] ?? '';
      const id = location.split('/').filter(Boolean).pop() ?? '';
      expect(id, `the 201 carried a usable Location header (got '${location}')`).not.toBe('');
      await this.waitForClosed();
      return id;
    });
  }

  /**
   * Press "Save changes" and resolve once the PATCH has been ANSWERED.
   *
   * Both the request and the response are awaited, for the reason
   * `online-evaluation-edit-rule-model-switch-effort.spec.ts` gives about the
   * rule editor: the sheet closes from the mutation's `onSuccess`, so waiting
   * only for it to disappear would leave a read-back free to observe the
   * pre-save row on a backend that answered an error.
   */
  async saveChanges(queueId: string): Promise<void> {
    return test.step(`save changes to queue ${queueId}`, async () => {
      const answered = this.page.waitForResponse(
        (response) =>
          response.request().method() === 'PATCH' &&
          new URL(response.url()).pathname.endsWith(`/v1/private/annotation-queues/${queueId}`),
      );
      await this.submitButton.click();
      const response = await answered;
      expect(response.ok(), `the save was accepted (got ${response.status()})`).toBe(true);
      await this.waitForClosed();
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
   * The orange header tag a queue carries once it stores a custom annotator
   * view (opik#9079).
   *
   * `AnnotationQueuePage.tsx` renders it on `annotator_view?.html` alone, so it
   * is the one place the product says, outside the SME flow itself, that this
   * queue shows annotators something other than the default view. Matched on
   * the exact text: the tag carries no testid and no role beyond its text, and
   * `AnnotatorViewStatusTag` elsewhere reads "Custom view" — a substring match
   * would conflate the two.
   */
  get customAnnotatorViewTag(): Locator {
    return this.page.getByText('Custom annotator view', { exact: true });
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
