import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { assertAllowedModelDisplayName } from '@e2e/core/llm-model-policy';
import { readRecordedToasts, startRecordingToasts } from './toast-recorder';

export type RunExperimentSourceMode = 'dataset' | 'test_suite';

/**
 * The version `POST /v1/private/prompts/versions` answers a Playground save
 * with, in the wire's own snake_case — this is the raw REST body, not a value
 * that has been through the suite's typed backend client.
 *
 * Every field is optional because that is what the endpoint's contract allows,
 * and a caller asserting a save deduped must be able to see an absent `commit`
 * as the real answer it is rather than have it defaulted into a passing
 * comparison.
 */
export interface SavedPromptVersionResponse {
  id?: string;
  prompt_id?: string;
  commit?: string;
  version_number?: string;
  template?: string;
}

const escapeForRegExp = (literal: string): string =>
  literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Empty-state text an output cell shows until its row has been run. */
const IDLE_CELL_TEXT = 'No runs yet';

/**
 * A failed run surfaces as the output cell's own value — `processCombination` catches and
 * writes `error.message` there, with no flag to distinguish it from a real completion, so
 * text is the only signal available. Kept narrow on purpose: these two are strings Opik
 * itself emits, whereas a broad /error/i would fire on legitimate model output. Provider
 * errors are free-form and stay undetected here by design.
 */
const RUN_ERROR_TEXT = /\bnot defined\b|returned an empty response/i;

/**
 * Whether a cell has finished.
 *
 * Deliberately does NOT require a test-suite cell's Passed/Failed verdict. A suite cell does
 * leave the idle state before its verdict lands, but the verdict comes from
 * `PlaygroundOutputAssertionStatus`, which polls the experiment for up to its own 5-minute
 * ceiling — far past the 120s callers allow — so requiring it times the wait out on a run
 * that is working. It also overshoots what these specs claim, which is that a run produces
 * output, not that its assertions were scored. A spec that wants the verdict should wait on
 * it explicitly, with a budget to match.
 *
 * Text is the only handle here: the idle placeholder carries no testid, so output that
 * itself contained "No runs yet" would read as idle. Adding one would not help — these specs
 * run against deployed Opik, so it would not exist in the version under test.
 */
const hasProducedOutput = (text: string): boolean =>
  text.trim() !== '' && !text.includes(IDLE_CELL_TEXT);

/** The title `useRunCompletionToast` gives the toast a finished run raises. */
const RUN_COMPLETE_TEXT = /Run complete/;

/** The localStorage key zustand persists the Playground store under. */
const PLAYGROUND_STORE_KEY = 'PLAYGROUND_STATE';

/**
 * One persisted Playground prompt, reduced to the fields that decide whether a
 * remount re-applies a library template over the user's own edits.
 *
 * Every field is optional because their ABSENCE is the state under test: a
 * prompt written before `appliedChatPromptVersionId` existed carries no marker,
 * and a card that followed the library's latest version carries no pinned
 * version either.
 */
export interface StoredChatPrompt {
  loadedChatPromptId?: string;
  loadedChatPromptVersionId?: string;
  appliedChatPromptVersionId?: string;
  messages: Array<{ role: string; content: string }>;
}

/**
 * A `PLAYGROUND_STATE` blob to plant, as a user carrying one would have it.
 *
 * Optional fields are omitted to model a state that genuinely lacks them —
 * see {@link StoredChatPrompt} — so `undefined` here means "delete the key",
 * not "set it to undefined".
 */
export interface PlantedChatPrompt {
  loadedChatPromptId: string;
  /** Omit to model a card that followed the library's LATEST version. */
  loadedChatPromptVersionId?: string;
  /** Omit to model state written before the applied-version marker existed. */
  appliedChatPromptVersionId?: string;
  messages: Array<{ role: 'system' | 'user'; content: string }>;
  /** Omit to model state written before the backfill flag existed. */
  backfilled?: boolean;
}

export interface PlaygroundVariantConfig {
  systemPrompt?: string;
  /** User prompt body. Supports `{{column}}` templating against suite/dataset items. */
  userPrompt: string;
  /** Model display name as shown in the model picker (e.g., "gpt-5-nano (free)"). */
  modelDisplayName?: string;
}

export interface RunSimplePromptArgs {
  modelDisplayName: string;
  prompt: string;
  timeoutMs?: number;
}

export interface RunSimplePromptResult {
  outputText: string;
  isError: boolean;
}

/** One instant's view of the Playground's Metrics picker. */
export interface MetricsPickerState {
  /** The "<selected> of <total> selected" footer, verbatim. */
  summary: string;
  /** One entry per rule row, in the order the picker lists them. */
  rules: Array<{
    name: string;
    checked: boolean;
    /**
     * Whether the row's checkbox is disabled — which the picker uses to mark a
     * rule that scores every dataset run whether or not it was chosen, so it
     * cannot be given back.
     */
    locked: boolean;
  }>;
}

/**
 * Prompt Playground page object. Two distinct user-modes:
 *   - "free" (no dataset/suite loaded): variants run against ad-hoc prompts; results
 *     appear as individual outputs below each variant card.
 *   - "experiment" (dataset or test-suite loaded): variants run against all items;
 *     results appear in the table at the bottom. Every Re-run creates a new
 *     experiment server-side automatically (no separate "Save as experiment" step).
 */
export class PlaygroundPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  async goto(): Promise<void> {
    return test.step('navigate to Playground', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/playground`,
      );
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('wait for Playground to be ready', async () => {
      await this.page
        .getByRole('heading', { name: 'Playground', level: 1 })
        .waitFor({ state: 'visible' });
      // Variant card "A" should be mounted with its model picker.
      await this.modelPicker(0).waitFor({ state: 'visible' });
    });
  }

  async configureVariant(index: number, cfg: PlaygroundVariantConfig): Promise<void> {
    return test.step(`configure variant ${index}`, async () => {
      if (cfg.modelDisplayName) {
        await this.setModelForVariant(index, cfg.modelDisplayName);
      }

      if (cfg.systemPrompt !== undefined) {
        await this.fillMessageBody(this.variantMessage(index, 'system'), cfg.systemPrompt);
      }
      await this.fillMessageBody(this.variantMessage(index, 'user'), cfg.userPrompt);
    });
  }

  /**
   * Add a variant that copies the last one — same model and messages
   * (`PlaygroundAddVariant.handleDuplicateLastPrompt`).
   *
   * Duplicating rather than adding a blank variant is deliberate: a blank one
   * inherits only the last-picked model and needs its messages configured
   * again, which is setup the naming assertion does not care about.
   */
  async duplicateLastVariant(): Promise<void> {
    return test.step('add variant (duplicate of the last one)', async () => {
      await this.page.getByTestId('playground-add-variant-button').click();
      await this.page.getByRole('button', { name: 'Duplicate variant' }).click();
    });
  }

  /**
   * Type the run's experiment name into the output-actions strip (OPIK-3268).
   *
   * One name per run, not per variant: the suffix that separates the variants
   * (`_a`, `_b`) is generated by `buildExperimentName`, so this control is the
   * only editable part of the name.
   *
   * `Enter` commits rather than clicking the ✓, because `InlineEditableText`
   * also saves on blur — clicking anything to commit would make a failure
   * ambiguous between "the keystroke path works" and "the blur path does".
   */
  async setExperimentName(name: string): Promise<void> {
    return test.step(`set experiment name to "${name}"`, async () => {
      const editor = this.experimentNameEditor();
      await editor.click();
      const input = editor.getByRole('textbox');
      await input.fill(name);
      await input.press('Enter');
      await expect(editor).toContainText(name.trim() === '' ? 'Auto-generated name' : name.trim());
    });
  }

  /**
   * The name currently held for the next run, read out of the editor itself.
   *
   * Read from the input rather than the idle span because the span falls back
   * to the "Auto-generated name" placeholder, which is indistinguishable from a
   * run genuinely named that. `Escape` leaves edit mode without saving — a
   * plain click-away would blur, and blur saves.
   */
  async readExperimentName(): Promise<string> {
    return test.step('read experiment name', async () => {
      const editor = this.experimentNameEditor();
      await editor.click();
      const input = editor.getByRole('textbox');
      const value = await input.inputValue();
      await input.press('Escape');
      return value;
    });
  }

  /**
   * Wait until a run has finished, whatever its outcome.
   *
   * `waitForRunsComplete` is the wrong signal for a naming test: it throws on a
   * cell that errored, and these specs run against a provider that refuses
   * every connection on purpose. The strip is swapped for the progress
   * indicator for the whole run (`PlaygroundExperimentOutputActions`), so the
   * editor coming back IS "no longer running" — and it is also the element the
   * post-run assertions read, so nothing here can pass against a stale render.
   */
  async waitForRunSettled(timeoutMs = 180_000): Promise<void> {
    return test.step('wait for the run to settle', async () => {
      await expect(this.experimentNameEditor()).toBeVisible({ timeout: timeoutMs });
    });
  }

  /**
   * The "Run complete" toast raised at the end of a run.
   *
   * Scoped to the toast viewport because Radix also renders a visually-hidden
   * `role="status"` announcer carrying the same text, and an unscoped lookup
   * matches both. Radix dismisses it on its own 5s default, so assert on it
   * immediately after `waitForRunSettled` and before any backend polling.
   */
  completionToast(): Locator {
    return this.page
      .getByRole('region', { name: 'Notifications (F8)' })
      .getByRole('status')
      .filter({ hasText: 'Run complete' });
  }

  /**
   * Start recording every toast the page raises, from before the first one can
   * appear. Must be called BEFORE `goto()`.
   *
   * `completionToast()` above reads a LIVE locator, which only works when the
   * run is slow enough that the toast is still on screen when the assertion
   * runs. It is not, on the runs these specs drive: Radix dismisses a toast on
   * its own 5s default, and a run against a provider that refuses every
   * connection finishes in about a second, so a point-in-time read races the
   * dismissal — and worse, it cannot tell "never raised" from "raised and
   * already gone", which is exactly the distinction a spec asserting that a
   * STOPPED run stays silent depends on.
   *
   * The mechanism lives in `pom/toast-recorder.ts`, shared with the datasets
   * POM, which needs the same guarantee for an upload that must not raise a
   * success toast. Kept as methods here so every existing caller is unchanged.
   */
  async startRecordingToasts(): Promise<void> {
    return startRecordingToasts(this.page);
  }

  /**
   * The text of every toast raised since `startRecordingToasts()`, oldest
   * first, whether or not it is still on screen. See `pom/toast-recorder.ts`.
   */
  async recordedToasts(): Promise<string[]> {
    return readRecordedToasts(this.page);
  }

  /** Recorded toasts that announce a finished run. */
  async recordedRunCompletionToasts(): Promise<string[]> {
    return test.step('read the recorded run-completion toasts', async () => {
      return (await this.recordedToasts()).filter((text) => RUN_COMPLETE_TEXT.test(text));
    });
  }

  /** The "Creates: {name}_a  +N more" preview; absent while no name is set. */
  experimentNamePreview(): Locator {
    return this.page.getByTestId('playground-experiment-name-preview');
  }

  experimentNameEditor(): Locator {
    return this.page.getByTestId('playground-experiment-name-editor');
  }

  /** Open the "Run experiment" entry menu (when no suite/dataset is loaded). */
  async clickRunExperiment(): Promise<void> {
    return test.step('click Run experiment', async () => {
      await this.runExperimentTriggerButton().click();
      await this.page
        .getByTestId('run-experiment-source-dataset')
        .waitFor({ state: 'visible' });
    });
  }

  /**
   * Pick a source from the inline "Run experiment" control. Chooses Dataset or
   * Test suite from the entry menu, then selects the entity by name from the
   * inline dropdown (which auto-opens). Selection is live — clicking the entity
   * row picks its latest version and commits, so there is no submit button.
   */
  async selectRunExperimentSource(args: {
    mode: RunExperimentSourceMode;
    entityName: string;
  }): Promise<void> {
    return test.step(`select ${args.mode} "${args.entityName}" for run experiment`, async () => {
      const optionTestId =
        args.mode === 'test_suite'
          ? 'run-experiment-source-test-suite'
          : 'run-experiment-source-dataset';
      await this.page.getByTestId(optionTestId).click();

      // The inline source selector auto-opens. Search and pick the entity by
      // name; clicking the row selects the latest version and commits.
      const listbox = this.page.getByRole('listbox');
      await listbox.waitFor({ state: 'visible' });
      await listbox.getByPlaceholder('Search').fill(args.entityName);
      await listbox.getByText(args.entityName, { exact: true }).first().click();

      await expect(this.loadedSourcePill()).toContainText(args.entityName);
    });
  }

  // ── reset ───────────────────────────────────────────────────────────────

  /**
   * Reset the Playground and confirm the dialog.
   *
   * Returns only once the dialog has gone, so a caller counting variant cards
   * straight afterwards is reading the post-reset render rather than the one
   * still behind the overlay.
   */
  async resetPlayground(): Promise<void> {
    return test.step('reset the Playground', async () => {
      await this.resetButton().click();
      const dialog = this.resetDialog();
      await dialog.waitFor({ state: 'visible' });
      // The confirm button carries the same text as the dialog's own title, so
      // the lookup MUST be scoped to the dialog — an unscoped one matches both.
      await dialog.getByRole('button', { name: 'Reset playground' }).click();
      await dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * The Reset control in the Playground header.
   *
   * Icon-only, with no accessible name: "Reset playground" is a Radix
   * `TooltipContent`, not an `aria-label`, so `getByRole('button', { name })`
   * finds nothing and the rendered icon is the only handle. A `data-testid` on
   * it would be better — but these specs run against a deployed Opik, so an
   * attribute added alongside them would not exist in the version under test.
   * Same trade-off, and the same reason, as `modelParametersTrigger` below.
   */
  resetButton(): Locator {
    return this.page.locator('button:has(svg.lucide-rotate-ccw)');
  }

  /** The reset confirmation dialog, identified by its own title. */
  resetDialog(): Locator {
    return this.page.getByRole('dialog').filter({ hasText: 'Reset playground' });
  }

  /**
   * Every variant card currently mounted. After a reset there must be exactly
   * one — the two failure modes the same-flush race produced were zero cards
   * and two, and both leave the Playground unusable.
   */
  variantCards(): Locator {
    return this.page.getByTestId('playground-variant-card');
  }

  /**
   * The message editors of every variant card on the page.
   *
   * Counted rather than addressed by index so "exactly one empty prompt" is
   * assertable as a single statement: a second card that also happened to be
   * empty would otherwise pass an index-based check.
   */
  messageEditors(): Locator {
    return this.variantCards().getByTestId('playground-message-row').locator('.cm-content');
  }

  /**
   * The text content of every mounted message editor, in DOM order.
   *
   * Read from `textContent` of the CodeMirror content node rather than through
   * `innerText`: an empty editor still renders its "Type your message"
   * placeholder as a child element, which `innerText` would report as the
   * message body and so make a reset that cleared nothing look like one that
   * did. The placeholder node is excluded explicitly.
   */
  async messageBodies(): Promise<string[]> {
    return test.step('read every message editor body', async () => {
      return this.messageEditors().evaluateAll((editors) =>
        editors.map((editor) => {
          const clone = editor.cloneNode(true) as HTMLElement;
          clone.querySelectorAll('.cm-placeholder').forEach((node) => node.remove());
          return (clone.textContent ?? '').trim();
        }),
      );
    });
  }

  messageEditor(variantIndex: number, messageIndex: number): Locator {
    return this.variantCard(variantIndex)
      .getByTestId('playground-message-row')
      .nth(messageIndex)
      .locator('.cm-content')
      .first();
  }

  async addMessage(variantIndex: number): Promise<void> {
    return test.step(`click + Message on variant ${variantIndex}`, async () => {
      await this.variantCard(variantIndex).getByRole('button', { name: 'Message' }).click();
    });
  }

  async focusUserMessageAtLineStart(): Promise<void> {
    return test.step('put the cursor at the start of the user message', async () => {
      await this.variantMessage(0, 'user').locator('.cm-content').first().click();
      await this.page.keyboard.press('Home');
    });
  }

  /**
   * CodeMirror draws its own blinking `.cm-cursor` and `.cm-scroller` clips it,
   * so "is the cursor visible" is asked as geometry: a pixel check would depend
   * on the blink phase. Signed: positive is how many CSS pixels of the cursor
   * stick out past the scroll box's left edge and get cut off (OPIK-5298);
   * zero or negative means the cursor is fully inside.
   */
  async userMessageCursorLeftOverflowPx(): Promise<number> {
    return test.step('measure how far the user message cursor sticks out on the left', async () => {
      return this.variantMessage(0, 'user')
        .locator('.cm-editor')
        .first()
        .evaluate((editor) => {
          const cursor = editor.querySelector('.cm-cursor');
          const scroller = editor.querySelector('.cm-scroller');
          if (!cursor || !scroller) return Number.POSITIVE_INFINITY;
          return scroller.getBoundingClientRect().left - cursor.getBoundingClientRect().left;
        });
    });
  }

  async emptyUserMessageWithBackspace(): Promise<void> {
    return test.step('replace the user message with one character, then delete it with Backspace', async () => {
      await this.variantMessage(0, 'user').locator('.cm-content').first().click();
      await this.page.keyboard.press('ControlOrMeta+A');
      await this.page.keyboard.type('a');
      await this.page.keyboard.press('Backspace');
    });
  }

  async userMessageCursorHeightPx(): Promise<number> {
    return test.step('measure the height of the user message cursor', async () => {
      return this.variantMessage(0, 'user')
        .locator('.cm-cursor')
        .first()
        .evaluate((cursor) => cursor.getBoundingClientRect().height);
    });
  }

  /**
   * The "Run experiment" entry control — present only while NO dataset or test
   * suite is loaded. Its return after a reset is what says the reset really
   * put the Playground back into free mode, rather than merely blanking the
   * pill while keeping the loaded source in the store.
   */
  runExperimentEntryControl(): Locator {
    return this.runExperimentTriggerButton();
  }

  /** Click Re-run when a suite/dataset is already loaded. */
  async clickReRun(): Promise<void> {
    return test.step('click Re-run', async () => {
      await this.runButton().click();
    });
  }

  // ── per-column run / stop ───────────────────────────────────────────────
  //
  // `PlaygroundRunButton` is mounted per variant, but only once there are two
  // or more; with one, it would duplicate the header's Run. In experiment mode
  // (a dataset or suite loaded) it renders inside the variant card — in free mode
  // the same component renders under the OUTPUT column instead, which is why
  // these are scoped to the card rather than looked up page-wide. It carries no
  // testid and its label swaps between Run and Stop with the variant's own
  // state; the accessible name is the only handle. A `data-testid` on it would
  // be better, but these specs run against a deployed Opik, so an attribute
  // added alongside them would not exist in the version under test.

  /** The Run control on one variant's card. Present only while that variant is idle. */
  variantRunButton(index: number): Locator {
    return this.variantCard(index).getByRole('button', { name: 'Run', exact: true });
  }

  /** The Stop control on one variant's card. Present only while that variant is running. */
  variantStopButton(index: number): Locator {
    return this.variantCard(index).getByRole('button', { name: 'Stop', exact: true });
  }

  /**
   * The header's "Stop all", which replaces the Run button for the duration of
   * a run. Named exactly, so it cannot also match a variant card's "Stop".
   */
  stopAllButton(): Locator {
    return this.page.getByRole('button', { name: 'Stop all', exact: true });
  }

  /**
   * The run progress indicator's own readout, mounted only while a whole-run
   * launch is in flight (`PlaygroundProgressIndicator`, `useShowsRunProgress`).
   *
   * Both of the two things it can say, as one locator: "Preparing dataset
   * items..." before `/execute` has answered with a total, and
   * "<done>/<total> completed (<pct>%)" once it has. A spec that needs to know
   * a run is still going wants either — which of the two it happens to be is
   * a race against the server's paging, and pinning one would make the
   * assertion about timing instead of about the run.
   *
   * Matched on the text because the component carries no `data-testid`, and
   * anchored so neither phrase can be satisfied by prose elsewhere on the
   * page. Also the reason this is the readout and not the bar: the bar is an
   * unlabelled `div` whose only distinguishing feature is an inline width.
   */
  runProgressReadout(): Locator {
    return this.page.getByText(
      /^(Preparing dataset items\.\.\.|\d+\/\d+ completed \(\d+%\))$/,
    );
  }

  /**
   * Run ONE variant, from its own card.
   *
   * The count is asserted rather than `.first()`-ed: a lookup that matched two
   * buttons would run whichever the DOM happened to order first, and a spec
   * whose whole claim is "only this column ran" would then be asserting against
   * a column it did not choose.
   */
  async clickVariantRun(index: number): Promise<void> {
    return test.step(`click Run on variant ${index}`, async () => {
      const button = this.variantRunButton(index);
      await expect(button).toHaveCount(1);
      await button.click();
    });
  }

  /** Stop ONE running variant, from its own card. */
  async clickVariantStop(index: number, timeoutMs = 30_000): Promise<void> {
    return test.step(`click Stop on variant ${index}`, async () => {
      const button = this.variantStopButton(index);
      await expect(button).toHaveCount(1, { timeout: timeoutMs });
      await button.click();
    });
  }

  /** Stop every running variant, from the header. */
  async clickStopAll(timeoutMs = 30_000): Promise<void> {
    return test.step('click Stop all', async () => {
      const button = this.stopAllButton();
      await expect(button).toBeVisible({ timeout: timeoutMs });
      await button.click();
    });
  }

  // ── the run gate (opik#8813) ────────────────────────────────────────────
  //
  // Both Run surfaces refuse a source whose SELECTED version has no items, and
  // each decides that for itself — `PlaygroundHeader` from the `versionItemsTotal`
  // prop, `PlaygroundRunButton` from its own `usePlaygroundDataset()` call — so a
  // spec about the gate has to address them separately. One is not evidence
  // about the other.
  //
  // The reason a control gives is a Radix `TooltipContent`, not an `aria-label`
  // or a `title`, so hovering is the only way to read it. Both buttons keep
  // `pointer-events: auto` while disabled (`style={isRunDisabled ? ... : {}}`)
  // precisely so that the hover still lands — which is what makes the reason
  // readable at all in the state it matters in.

  /**
   * The header's Run / Re-run control, whichever of the two is mounted.
   *
   * Public because the gate is about the button's own disabled state, not about
   * clicking it: `clickReRun` presses the same element but cannot say anything
   * about a press that must not be possible.
   */
  headerRunButton(): Locator {
    return this.runButton();
  }

  /** Hover the header's Run and read the reason it gives. */
  async headerRunTooltipText(): Promise<string> {
    return test.step('read the header Run tooltip', async () =>
      this.hoverAndReadTooltip(this.headerRunButton()),
    );
  }

  /** Hover one variant's own Run and read the reason it gives. */
  async variantRunTooltipText(index: number): Promise<string> {
    return test.step(`read variant ${index}'s Run tooltip`, async () =>
      this.hoverAndReadTooltip(this.variantRunButton(index)),
    );
  }

  /**
   * Fire the Run-all keyboard shortcut.
   *
   * `PlaygroundHeader` listens on `window` in the CAPTURE phase and returns
   * early on `isRunDisabled`, so the shortcut reaches the handler whatever has
   * focus — including the CodeMirror message editor a variant's configuration
   * leaves focused. That is why this does not park focus anywhere first: doing
   * so would test a path the keyboard handler does not have.
   */
  async pressRunAllHotkey(): Promise<void> {
    return test.step('press Shift+Enter', async () => {
      await this.page.keyboard.press('Shift+Enter');
    });
  }

  /**
   * Hover a control and return the reason IT gave.
   *
   * Read through the trigger's own `aria-describedby`, which Radix points at
   * the content it opened for that trigger, rather than by looking up "the
   * tooltip on the page". The difference is load-bearing when several controls'
   * reasons are read in a row: a DISABLED button never receives the
   * pointer-leave that would close its tooltip, so the previous one is still
   * mounted when the next is read — measured here, where the header's reason
   * outlived a move to the far corner of the page for a full ten seconds. A
   * page-scoped lookup would have quietly returned the header's reason as the
   * variant card's.
   *
   * Addressed with an attribute selector rather than `#id`: Radix's generated
   * ids contain colons, which a CSS id selector reads as a pseudo-class.
   */
  private async hoverAndReadTooltip(control: Locator): Promise<string> {
    // Dismiss whatever is still open before hovering the next control, and
    // confirm it went. A disabled button never receives the pointer-leave that
    // would close its own tooltip, and the positioning wrapper Radix puts
    // around the content is wider than the visible bubble — wide enough, from
    // the header, to sit over the variant cards' Run buttons, where it
    // swallowed the hover entirely and left the next read waiting on a tooltip
    // that could never open. Escape is the dismissal Radix listens for on
    // `document`, and `TooltipWrapper` honours it.
    await this.page.keyboard.press('Escape');
    await expect(this.page.getByRole('tooltip')).toHaveCount(0, { timeout: 10_000 });

    await control.hover();
    await expect(control).toHaveAttribute('aria-describedby', /.+/, { timeout: 10_000 });
    const describedBy = await control.getAttribute('aria-describedby');
    if (!describedBy) {
      throw new Error('tooltip trigger carried no aria-describedby after it opened');
    }
    const tooltip = this.page.locator(`[id="${describedBy}"]`);
    await expect(tooltip).toHaveCount(1);
    return (await tooltip.innerText()).trim();
  }

  /**
   * Wait until a loaded dataset/suite can actually be run.
   *
   * The source pill commits with the *selection*, which is strictly before
   * `useDatasetItemsList` resolves. Running in that window builds the
   * prompt/item combinations from an empty item list, so every row fails
   * client-side with "<var> not defined" — no LLM call, no experiment, and a
   * permanent error state that no later wait can recover. The output cells come
   * from the same query as the runner's items, so their presence is the signal
   * that the run has something to iterate.
   */
  async waitForRunReady(opts: { expectedRows: number; timeoutMs?: number }): Promise<void> {
    return test.step(`wait for ${opts.expectedRows} row(s) to be run-ready`, async () => {
      await expect
        .poll(
          async () => {
            const total = await this.outputCells().count();
            return total >= opts.expectedRows && (await this.idleOutputCells().count()) === total;
          },
          { timeout: opts.timeoutMs ?? 30_000, intervals: [250, 500, 1000] },
        )
        .toBe(true);
    });
  }

  /**
   * Wait for every output cell to finish.
   *
   * A run that fails writes the failure into the cell as its value, so "no longer idle"
   * does not mean "succeeded". The failure scan runs inside the poll, not after it: one
   * errored cell next to one that never ran would otherwise hold the predicate false for
   * the full timeout and report a bare poll timeout instead of the failure.
   */
  async waitForRunsComplete(opts: { expectedRows: number; timeoutMs?: number }): Promise<void> {
    return test.step(`wait for ${opts.expectedRows} run(s) to complete`, async () => {
      let failures: string[] = [];
      await expect
        .poll(
          async () => {
            const texts = await this.outputCells().allInnerTexts();
            failures = texts.filter((t) => RUN_ERROR_TEXT.test(t));
            if (failures.length > 0) return true;
            return (
              texts.length >= opts.expectedRows &&
              texts.every(hasProducedOutput)
            );
          },
          { timeout: opts.timeoutMs ?? 120_000, intervals: [1000, 2000, 3000] },
        )
        .toBe(true);

      if (failures.length > 0) {
        throw new Error(
          `Playground run failed in ${failures.length} cell(s): ${failures[0].trim()}`,
        );
      }
    });
  }

  /**
   * Output cells that have produced content — one per dataset row *per variant*, so this
   * exceeds the row count whenever more than one variant is configured.
   */
  async countCompletedOutputCells(): Promise<number> {
    return (await this.outputCells().allInnerTexts()).filter(hasProducedOutput).length;
  }

  // ── free-mode failed-run output (OPIK-8468) ─────────────────────────────
  //
  // A run that fails renders `PlaygroundOutputError` — a red "Run failed: <message>" tag —
  // in place of the model's answer. The members below address the FREE-MODE surface, the
  // single-prompt `PlaygroundPromptOutput` panel; `outputErrorTags` / `outputMarkdownBlocks`
  // further down are the dataset-grid equivalents, scoped to the results table's cells.
  // The two components gate their bodies on separate `hasOutput` expressions and so can
  // regress apart, which is why each surface gets its own locator rather than a shared
  // page-wide one — a page-wide lookup would let a grid assertion pass on a panel tag.
  //
  // `RUN_ERROR_TEXT` above sniffs a failure out of a cell's own text instead, because
  // before OPIK-8468 the failure WAS the output string and there was nothing else to read.
  // It is deliberately left in place: these specs run against deployed Opik, so
  // `waitForRunsComplete` still has to recognise a failure on a version predating this tag.

  /**
   * The "Run failed:" tags of the single-prompt output panels. Page-scoped because the
   * panel has no container of its own to hang a locator off — safe only in free mode,
   * where no results table is mounted, which is the only mode these are used in.
   */
  promptOutputErrorTags(): Locator {
    return this.page.getByTestId('playground-output-error');
  }

  /**
   * Rendered model answers in the single-prompt output panels. `MarkdownPreview` stamps
   * `.comet-markdown` on both branches it can take — the parsed one and the plain-text
   * fallback — so "a failed run renders none of these" is the assertion OPIK-8468 exists
   * for. Addressed by class rather than by testid because the component is shared far
   * outside the Playground, where stamping one would reach well beyond this area.
   */
  promptOutputMarkdownBlocks(): Locator {
    return this.page.locator('.comet-markdown');
  }

  /** The "No runs yet" placeholder an output surface shows until its prompt has been run. */
  noRunsYetPlaceholders(): Locator {
    return this.page.getByText(IDLE_CELL_TEXT, { exact: true });
  }

  /**
   * The duration and token chips of the single-prompt output header. Each is its own span
   * whose whole text is the chip, so both are matched anchored — a substring match would
   * also fire on a model name that happened to end in "s".
   */
  durationChips(): Locator {
    return this.page.getByText(/^\d+\.\d+s$/);
  }

  tokenChips(): Locator {
    return this.page.getByText(/^\d+ tokens$/);
  }

  /**
   * Wait until exactly `count` failure tags have rendered in the single-prompt panels.
   *
   * `waitForRunsComplete` is the wrong signal for a spec that provokes a failure on
   * purpose — it treats an errored cell as a fault and throws. The count is exact rather
   * than a floor so that a run which fails some variants and silently drops the rest fails
   * here, loudly, instead of passing on the first tag to appear.
   */
  async waitForPromptOutputErrors(count: number, timeoutMs = 60_000): Promise<void> {
    return test.step(`wait for ${count} failed-run tag(s)`, async () => {
      await expect(this.promptOutputErrorTags()).toHaveCount(count, { timeout: timeoutMs });
    });
  }

  // ── output freshness (opik#8758) ────────────────────────────────────────
  //
  // An output whose run inputs have changed is marked `stale`: it stays on
  // screen, dimmed, under a note naming what changed, rather than being cleared
  // back to "No runs yet". Two surfaces again, and for the same reason the
  // failure tags have two — the single-prompt panel and the grid cell gate on
  // separate `hasOutput` expressions and pass `PlaygroundStaleOutputNote`
  // different `compact` settings, so they regress apart and each needs its own
  // locator. A page-wide lookup would let a grid assertion pass on a panel note.
  //
  // One trap for any spec that runs TWICE, measured on 2.2.91 and caused by this
  // very change: `runFreeMode` and `runSimplePromptAndAwaitResponse` both treat
  // the "No runs yet" placeholder disappearing as "the run finished", and a
  // stale output keeps it gone — so they return the instant Run is pressed.
  // `waitForRunReady` has the mirror problem on the grid: it requires every cell
  // to be the idle placeholder, and a stale cell is not idle, so it times out
  // instead. Wait on the output itself (`waitForPromptOutputErrors`) or on the
  // run going idle (`waitForFreeRunIdle`).

  /**
   * The stale-output notes of the single-prompt panels.
   *
   * Page-scoped, which is safe only in free mode where no results table is
   * mounted — the same constraint `promptOutputErrorTags` carries.
   */
  promptStaleOutputNotes(): Locator {
    return this.page.getByTestId('playground-stale-output-note');
  }

  /** The stale-output notes inside the dataset-grid output cells. */
  outputCellStaleNotes(): Locator {
    return this.outputCells().getByTestId('playground-stale-output-note');
  }

  /**
   * The rendered opacity of each single-prompt failure tag.
   *
   * The computed style and not the `opacity-50` class: the claim is that the
   * user sees the tag dimmed, and reading what the browser resolved fails both
   * when the class stops being applied and when it is renamed. The exact level
   * is a design token, so callers compare against 1 rather than pinning 0.5 —
   * "dimmed or not" is the whole of what this spec asserts, and pinning the
   * token would turn a restyle into a test failure.
   */
  async promptOutputErrorOpacities(): Promise<number[]> {
    return this.promptOutputErrorTags().evaluateAll((els) =>
      els.map((e) => Number(window.getComputedStyle(e).opacity)),
    );
  }

  /** The same reading for the dataset-grid failure tags. */
  async outputErrorOpacities(): Promise<number[]> {
    return this.outputErrorTags().evaluateAll((els) =>
      els.map((e) => Number(window.getComputedStyle(e).opacity)),
    );
  }

  /**
   * Hover a grid cell's stale note and read the tooltip it raises.
   *
   * The grid renders the note `compact`, which trades the full sentence for a
   * two-word summary and moves the sentence into the tooltip — so the tooltip
   * is the only place the grid says WHY the cell is dimmed, and asserting the
   * visible text alone would not cover it. Radix portals the content out of the
   * cell, so it is read at page scope, exactly as `outputErrorTooltipText` does.
   */
  async staleNoteTooltipText(index = 0): Promise<string> {
    return test.step(`hover grid stale note ${index} and read its tooltip`, async () => {
      await this.outputCellStaleNotes().nth(index).hover();
      const tooltip = this.page.getByRole('tooltip');
      await expect(tooltip.first()).toBeVisible({ timeout: 10_000 });
      return (await tooltip.first().innerText()).trim();
    });
  }

  /**
   * Detach the prompt loaded into variant 0, through `LoadedPromptDisplay`'s
   * own control.
   *
   * While a chat prompt is loaded, `LoadedPromptDisplay` REPLACES the library
   * trigger, so `load-prompt-button` does not exist and a second pick is only
   * reachable through this control.
   *
   * By its icon, because the trigger is an icon-only `Button` whose only label
   * is a `TooltipWrapper` — no testid, no accessible name — and these specs run
   * against a deployed Opik, where an attribute added alongside them would not
   * exist in the version under test. `lucide-circle-x` and NOT
   * `lucide-x-circle`: `XCircle` is an alias re-exporting `CircleX` in
   * lucide-react, and the class is built from the canonical name. Asserted
   * unique before the click, so another CircleX arriving in the card fails here
   * rather than silently detaching through some other button.
   */
  async detachLoadedPrompt(): Promise<void> {
    return test.step('detach the loaded prompt from variant 0', async () => {
      const card = this.variantCard(0);
      // The chip sits in a strip that only expands on group-hover.
      await card.hover();
      const detach = card.locator('button:has(svg.lucide-circle-x)');
      await expect(detach, 'the Detach loaded prompt control').toHaveCount(1);
      await detach.click();
      // Resolving only once the chip has gone is what makes this method a
      // gesture rather than a click: the chip and the library menu share one
      // slot in the card, so a caller asserting on state "after the detach"
      // would otherwise be free to read it before the detach landed.
      await expect(
        detach,
        'the loaded-prompt chip left the card, so the prompt really is detached',
      ).toHaveCount(0);
      // The library trigger comes back only once the prompt is really detached,
      // so this is the other half of the signal — and it is also what the next
      // pick needs.
      await expect(card.getByTestId('load-prompt-button')).toHaveCount(1);
    });
  }

  /**
   * The message ids variant 0's prompt currently holds, read from the persisted
   * `PLAYGROUND_STATE`.
   *
   * Not an implementation detail a spec could do without — it is the
   * discriminator for the false-stale case. Re-applying a prompt from the
   * library rebuilds its messages with FRESH ids, and it is that payload (same
   * roles, same content, different ids) which used to grey a finished output
   * for no user edit. A spec asserting only "no note after a library load"
   * would go green on a build where the load silently did nothing, so the id
   * change is what makes the absence of a note mean something.
   *
   * Variant 0 is addressed through `promptIds[0]`, the store's own ordering,
   * which is the order the cards render in; the card carries no prompt id.
   * Reading localStorage follows `LogsPage.readStoredFilters` and
   * `PromptDetailPage.readPinnedExperimentIds`.
   */
  async storedMessageIds(): Promise<string[]> {
    return test.step('read variant 0\'s message ids from PLAYGROUND_STATE', async () => {
      const ids = await this.page.evaluate(() => {
        const raw = window.localStorage.getItem('PLAYGROUND_STATE');
        if (!raw) return null;
        const state = (JSON.parse(raw) as { state?: unknown }).state as
          | {
              promptIds?: string[];
              promptMap?: Record<string, { messages?: Array<{ id?: string }> }>;
            }
          | undefined;
        const first = state?.promptIds?.[0];
        if (!first) return null;
        const messages = state?.promptMap?.[first]?.messages;
        if (!messages) return null;
        return messages.map((m) => m.id ?? '');
      });
      // Asserted rather than defaulted to []: an absent store is a different
      // failure from a prompt with no messages, and returning [] for both would
      // let a comparison of "the ids changed" pass on two empty lists.
      expect(ids, 'PLAYGROUND_STATE holds variant 0 and its messages').not.toBeNull();
      return ids as string[];
    });
  }

  /**
   * Read the "<N>% pass rate" badge from the Prompt A column header.
   * Returns null if no run has completed yet.
   */
  async passRateText(): Promise<string | null> {
    const badge = this.resultsTable().getByText(/^\d+% pass rate$/);
    if ((await badge.count()) === 0) return null;
    return (await badge.first().textContent()) ?? null;
  }

  /** Confirm the suite/dataset pill is showing with the expected entity name. */
  loadedSourcePill(): Locator {
    return this.page.getByTestId('playground-loaded-source-pill');
  }

  // ── the dataset grid's own Filters popover (opik#8750) ───────────────────
  //
  // Mounted beside the loaded-source pill, and only in dataset-experiment
  // mode. It is a `FiltersButton` in `layout="icon"`, so the trigger renders
  // the icon alone with NO accessible name and no `data-testid` — and adding
  // one is not an option for a spec verified against a deployed build, which
  // would not have it. It is addressed as the one dialog-popup button on the
  // page carrying a filter icon, and the count is asserted at every call site
  // below so a second such control arriving fails loudly rather than driving
  // whichever the DOM ordered first.
  //
  // Three things about this popover cost a discovery pass, and all three
  // differ from the grid filters elsewhere in the app:
  //
  //  - it is `deferOnChange`, so the filter is applied when the popover
  //    CLOSES, not as the value is typed. An incomplete row is dropped in
  //    silence, and a row the popover considers incomplete commits as no
  //    filter at all — which, from a grid that was already unfiltered, is
  //    indistinguishable from nothing having happened.
  //  - the value box is a `DebounceInput`: it holds the typed text locally and
  //    only hands it up after 300ms, or IMMEDIATELY on blur. The popover's
  //    close handler reads the row's committed state, and a pointer-down
  //    outside reaches that handler before the input's blur has flushed — so
  //    closing straight after typing commits a row whose value is still empty,
  //    `isFilterValid` drops it, and the grid never hears about the filter.
  //    Measured on 2.2.119: the request simply never goes out. The blur is
  //    therefore its own step, and the trigger's valid-filter count is read
  //    back as proof the value arrived before anything is dismissed.
  //  - the row opens with its column select unset and its operator select
  //    DISABLED; choosing the column enables the operator and auto-selects
  //    `contains`, which is the operator these methods rely on.
  //
  // The settle point is the grid's own dataset-items response carrying the
  // value, never the popover closing: the grid is virtualised and the total
  // only exists in that payload, so counting the DOM would report the
  // viewport rather than the result set.

  /**
   * The Filters trigger beside the loaded-source pill.
   *
   * Public so a spec can assert the control is there at all before driving it —
   * it renders only in dataset-experiment mode.
   */
  datasetGridFiltersTrigger(): Locator {
    return this.page
      .locator('button[aria-haspopup="dialog"]')
      .filter({ has: this.page.locator('svg.lucide-filter') });
  }

  /** The open popover, identified by the column select only it contains. */
  private get datasetGridFiltersPopover(): Locator {
    return this.page
      .getByRole('dialog')
      .filter({ has: this.page.getByTestId('filter-column') });
  }

  /** Open the Filters popover and resolve once its first row is addressable. */
  private async openDatasetGridFilters(): Promise<Locator> {
    return test.step('open the dataset grid Filters popover', async () => {
      const trigger = this.datasetGridFiltersTrigger();
      await expect(
        trigger,
        'exactly one dataset-grid Filters control — it renders only in dataset mode',
      ).toHaveCount(1);
      await trigger.click();
      const popover = this.datasetGridFiltersPopover;
      await popover.waitFor({ state: 'visible' });
      return popover;
    });
  }


  /**
   * Put one `<column> contains <value>` filter on the dataset grid, and return
   * the row total the grid received.
   *
   * Idempotent in the column: on the first call the select is unset and the
   * column is chosen, and on a later call it already holds `column`, so only
   * the value is retyped. That is what lets a spec move from a filter matching
   * nothing to one matching a row without clearing in between — which would
   * otherwise return to a query key react-query has already cached, and so to a
   * grid that updates with no request to settle on.
   *
   * The returned total comes from the grid's own response, so a caller can
   * assert what the filter MATCHED rather than only that a filter was typed.
   */
  async setDatasetGridFilter(args: {
    datasetId: string;
    column: string;
    value: string;
  }): Promise<number> {
    return test.step(
      `filter the dataset grid where "${args.column}" contains "${args.value}"`,
      async () => {
        const popover = await this.openDatasetGridFilters();

        const columnSelect = popover
          .getByRole('combobox')
          .filter({ has: this.page.getByTestId('filter-column') });
        await expect(columnSelect, 'exactly one column select in the first row').toHaveCount(1);
        if ((await columnSelect.innerText()).trim() !== args.column) {
          await columnSelect.click();
          const option = this.page.getByRole('option', { name: args.column, exact: true });
          await expect(
            option,
            `exactly one "${args.column}" column option — the dataset's own data columns`,
          ).toHaveCount(1);
          await option.click();
        }

        // Asserted rather than assumed: choosing the column is what enables the
        // operator select and auto-selects `contains`, and every value these
        // methods type is written for that operator. A row left on a different
        // one would filter for something else entirely and still answer with a
        // total.
        const operatorSelect = popover
          .getByRole('combobox')
          .filter({ has: this.page.getByTestId('filter-operator') });
        await expect(
          operatorSelect,
          'choosing a column auto-selects the contains operator',
        ).toContainText('contains');

        const valueBox = popover.getByTestId('filter-string-input');
        await expect(valueBox, 'exactly one value box in the first row').toHaveCount(1);
        await valueBox.fill(args.value);

        // Blur the value box so the DebounceInput flushes into the filter row
        // BEFORE the popover is dismissed. Tab rather than a click elsewhere:
        // Radix traps focus inside the content, so it moves focus within the
        // popover and cannot dismiss it by accident.
        await valueBox.press('Tab');
        // Read back from the trigger, which renders the number of rows the
        // popover considers complete — the product's own statement that there
        // is exactly one filter and it is ready to apply, rather than a wait on
        // a timer. On a first application this is what would have caught the
        // swallowed value described above; on a later one the count is already
        // 1, and what it still rules out is a stray second row having been
        // added to the popover.
        await expect(
          this.datasetGridFiltersTrigger(),
          'the Filters control counts exactly one completed row',
        ).toHaveText('1');

        // Matched on the pathname and not on a substring of the whole URL: the
        // page issues several reads carrying the same `filters` param, and only
        // this one answers with a `total`.
        //
        // The query string is decoded as a QUERY STRING — `+` back to a space
        // first, then percent-decoded. Axios' own encoder emits `+` for a
        // space, so a bare `decodeURIComponent` leaves `"value":"seed+input+2"`
        // and a match on the value a caller passed can never fire. Measured on
        // 2.2.119 against a filter value with spaces in it, which is what any
        // value read off a seeded dataset item looks like.
        const settled = this.page.waitForResponse(
          (response) => {
            const url = new URL(response.url());
            if (!url.pathname.endsWith('/items')) return false;
            if (!url.pathname.includes(args.datasetId)) return false;
            if (!response.ok()) return false;
            const query = decodeURIComponent(url.search.replace(/\+/g, ' '));
            return query.includes(`"value":"${args.value}"`);
          },
          { timeout: 30_000 },
        );
        // Commit by clicking the page heading: it is inert, always present, and
        // outside the popover, and `deferOnChange` applies the filter from the
        // close handler.
        await this.page.getByRole('heading', { name: 'Playground', level: 1 }).click();
        await popover.waitFor({ state: 'hidden' });

        const body: unknown = await (await settled).json();
        const total = (body as { total?: unknown }).total;
        if (typeof total !== 'number') {
          throw new Error(
            'PlaygroundPage.setDatasetGridFilter: the filtered dataset-items read answered ' +
              'without a total — cannot tell a narrowed result set from an unfiltered one.',
          );
        }
        return total;
      },
    );
  }

  // ── the loaded source's version ─────────────────────────────────────────
  //
  // The pill's own control is a Radix Select listing the project's datasets (or
  // suites); each row carries a submenu of THAT source's versions, opened by
  // hovering its chevron (`DatasetVersionSelectBox`). Clicking the row itself
  // takes the latest version, so the submenu is the only way to put an earlier
  // version in front of the Playground — and therefore the only way to ask
  // whether the run gate reads the SELECTED version's item count or the
  // source's live one.

  /**
   * The version label the loaded-source pill is showing, e.g. `v2`.
   *
   * Also the settle signal for a freshly loaded source, and the reason the
   * specs wait on it before asserting anything about the gate: the label and
   * the `itemsTotal` the gate reads come out of the SAME
   * `GET /datasets/{id}/versions` response (`useValidatedDatasetVersion`), and
   * the pill commits with the selection, strictly before that response lands.
   * In the window between the two, `itemsTotal` is `undefined` and the gate
   * reads it as "not empty" — so an assertion made before the label is painted
   * is racing a state in which Run is legitimately still enabled.
   */
  loadedSourceVersionLabel(): Locator {
    return this.loadedSourcePill().getByRole('combobox').getByText(/^v\d+$/);
  }

  /**
   * Switch the loaded source to a named version through the pill's picker.
   *
   * The source is reached by SEARCHING for it rather than by position, and the
   * resulting row count is asserted, so a search that matched two sources fails
   * here instead of hovering whichever the DOM happened to order first.
   */
  async selectSourceVersion(args: {
    entityName: string;
    versionName: string;
  }): Promise<void> {
    return test.step(`select version ${args.versionName} of "${args.entityName}"`, async () => {
      await this.loadedSourcePill().getByRole('combobox').click();
      const listbox = this.page.getByRole('listbox');
      await listbox.waitFor({ state: 'visible' });
      await listbox.getByPlaceholder('Search').fill(args.entityName);

      // Hovered, not clicked: the chevron is a `PopoverTrigger asChild` over a
      // bare div whose only handler is `onMouseEnter`, and a click would land on
      // the row instead and select the LATEST version — the state this method
      // exists to leave. One such trigger per listed source that has a version
      // at all, which is why the list is filtered to this one source first.
      const versionSubmenuTrigger = listbox.locator('[aria-haspopup="dialog"]');
      await expect(versionSubmenuTrigger).toHaveCount(1);
      await versionSubmenuTrigger.hover();

      // The submenu is a portalled Popover, so its options are addressed at
      // page scope — and they are the only `role="option"` elements the picker
      // renders, since the source rows themselves are plain divs. Matched on
      // the version-name span EXACTLY, because the option's accessible name
      // also carries the version's change description, and because `v1` as a
      // substring would match `v10` once a source has ten versions.
      const option = this.page
        .getByRole('option')
        .filter({ has: this.page.getByText(args.versionName, { exact: true }) });
      await expect(option).toHaveCount(1);
      await option.click();

      await expect(this.loadedSourceVersionLabel()).toHaveText(args.versionName);
    });
  }

  // ── metrics picker ──────────────────────────────────────────────────────
  //
  // The Metrics control only exists once a DATASET is loaded (test suites do
  // not render it), and it carries no testid: `MetricSelector`'s
  // `PopoverTrigger asChild` wraps a bare `div tabIndex={0}`, so it is neither a
  // `<button>` nor named — `getByRole('button', { name: /Metrics/ })` finds
  // nothing. It does sit inside `playground-loaded-source-pill`, which is
  // stamped, and Radix marks the trigger `aria-haspopup="dialog"`; the pill's
  // only other controls are the dataset-version combobox and the "Clear
  // selection" button, neither of which opens a dialog. That pair is the
  // stablest description available. A `data-testid` on the trigger would be
  // better, but these specs run against a deployed Opik, so an attribute added
  // alongside them would not exist in the version under test.

  /** The Metrics popover trigger, inside the loaded-dataset pill. */
  metricsPickerTrigger(): Locator {
    return this.loadedSourcePill().locator('[aria-haspopup="dialog"]');
  }

  /**
   * The open Metrics popover.
   *
   * Radix renders `PopoverContent` portalled with `role="dialog"`, so it cannot
   * be scoped through the pill. It is identified by the select-all summary row
   * it owns — the only `"<n> of <m> selected"` text on the page — rather than by
   * being "the open dialog", which would also match the add/edit rule dialog
   * this same component mounts.
   *
   * PRECONDITION, because that summary row is conditional: `MetricSelector`
   * renders it only when `filteredRules.length > 0 && toggleableRules.length > 0`.
   * A project with no trace rules, one whose enabled rules are ALL
   * experiment/both-scoped (every rule forced, none toggleable), or a picker
   * whose search box has filtered every row away, all leave the popover open
   * with no summary — and this locator matches nothing, so the readers below
   * time out rather than reporting an empty picker. Every caller today seeds at
   * least one production-scope rule and never searches, which is what keeps that
   * reachable only in theory. A caller that cannot promise both needs a
   * different anchor first.
   */
  metricsPopover(): Locator {
    return this.page
      .getByRole('dialog')
      .filter({ has: this.page.getByText(/^\d+ of \d+ selected$/) });
  }

  /**
   * Open the Metrics popover if it is not already open.
   *
   * **The popover does not stay open.** On 2.2.66 it closes on its own roughly
   * a second after opening, with no interaction at all — measured on staging by
   * polling its presence once a second: present at t=0, gone at t=1 and every
   * second after. It reopens cleanly and keeps its state, and one interaction
   * comfortably fits inside the window, which is why this is usable at all.
   *
   * So every reader and every gesture below reopens first rather than assuming
   * the popover it saw a moment ago is still there. That is a workaround for a
   * product behaviour, not for a flaky locator — and it is deliberately NOT
   * hidden inside a blanket retry of the assertions, which would let a genuine
   * regression in what the picker SHOWS retry itself into passing.
   */
  async ensureMetricsPickerOpen(): Promise<void> {
    if ((await this.metricsPopover().count()) > 0) return;
    await this.metricsPickerTrigger().click();
    await this.metricsPopover().waitFor({ state: 'visible' });
  }

  /**
   * One rule row in the Metrics popover, addressed by the rule's name.
   *
   * Matched anchored and exact: the namespaced rule names in these specs share
   * a long prefix, so a substring filter would match every sibling — and
   * `-pg-picked` would also match `-pg-picked-2`.
   */
  metricRow(ruleName: string): Locator {
    return this.metricsPopover()
      .locator('div')
      .filter({ has: this.page.getByRole('checkbox') })
      .filter({ hasText: new RegExp(`^\\s*${escapeForRegExp(ruleName)}\\s*$`) });
  }

  /**
   * The picker's whole visible state, captured in a single DOM evaluation.
   *
   * One atomic read rather than an assertion per control, because the popover
   * can vanish between two of them (see `ensureMetricsPickerOpen`): a sequence
   * of `expect(locator)` calls would be reading a surface that is disappearing
   * underneath them, and each retry would re-open the popover and re-measure
   * from a different moment. Everything a caller asserts therefore comes from
   * the same instant.
   *
   * Rows are found from their checkboxes rather than by class, and the
   * select-all footer row is excluded by its summary text — it carries a
   * checkbox too, but it is not a rule.
   */
  async readMetricsPicker(): Promise<MetricsPickerState> {
    return test.step('read the Metrics picker state', async () => {
      await this.ensureMetricsPickerOpen();
      return this.metricsPopover().evaluate((root): MetricsPickerState => {
        const SUMMARY = /^\s*\d+ of \d+ selected\s*$/;
        const rules: MetricsPickerState['rules'] = [];
        let summary = '';

        for (const checkbox of Array.from(root.querySelectorAll('[role="checkbox"]'))) {
          // Walk out to the first ancestor that carries text: for an ordinary
          // rule that is the row itself, and for an always-run one it is the
          // row above the tooltip <span> its checkbox is wrapped in.
          let row: Element | null = checkbox;
          while (row && !(row.textContent ?? '').trim()) row = row.parentElement;
          if (!row) continue;

          const text = (row.textContent ?? '').trim();
          if (SUMMARY.test(text)) {
            summary = text;
            continue;
          }
          rules.push({
            name: text,
            checked: checkbox.getAttribute('aria-checked') === 'true',
            locked:
              (checkbox as HTMLButtonElement).disabled === true ||
              checkbox.hasAttribute('data-disabled'),
          });
        }
        return { summary, rules };
      });
    });
  }

  /**
   * Tick or untick a rule, and confirm the picker agrees afterwards.
   *
   * The whole row is the click target — `MetricSelector` puts `onClick` on the
   * row div, not on the checkbox — so clicking the checkbox would depend on the
   * event reaching the row by propagation rather than by contract.
   *
   * Retried because the popover may close mid-gesture, and made IDEMPOTENT so
   * retrying is safe: each attempt re-reads the state first and returns if the
   * rule already holds the wanted value. Without that guard a click that landed
   * but could not be confirmed would be replayed, toggling the rule back.
   *
   * A rule that will not move is a failure, not something to retry away: the
   * `toPass` window is what absorbs the popover closing, and a locked rule
   * simply never reaches the wanted state, so this fails with the last
   * comparison rather than silently continuing.
   */
  async setMetricPicked(ruleName: string, picked: boolean): Promise<void> {
    return test.step(`${picked ? 'pick' : 'unpick'} metric "${ruleName}"`, async () => {
      await expect(async () => {
        const before = await this.readMetricsPicker();
        const current = before.rules.find((rule) => rule.name === ruleName);
        expect(current, `the picker lists exactly one rule named "${ruleName}"`).toBeDefined();
        if (current!.checked === picked) return;

        await this.ensureMetricsPickerOpen();
        await this.metricRow(ruleName).click({ timeout: 2_000 });

        const after = await this.readMetricsPicker();
        expect(
          after.rules.find((rule) => rule.name === ruleName)?.checked,
          `"${ruleName}" is ${picked ? 'ticked' : 'unticked'} after clicking its row`,
        ).toBe(picked);
      }).toPass({ timeout: 45_000, intervals: [250, 500, 1000] });
    });
  }

  /**
   * One-shot helper for provider-sanity tests: pick a model, type a single user
   * prompt, run inline (no dataset/suite), and read the result.
   *
   * Returns the output text and an isError flag. The Playground's inline-run
   * mode writes results to the right side of each variant card.
   */
  async runSimplePromptAndAwaitResponse(args: RunSimplePromptArgs): Promise<RunSimplePromptResult> {
    return test.step('run simple prompt and await response', async () => {
      await this.setModelForVariant(0, args.modelDisplayName);
      await this.fillMessageBody(this.variantMessage(0, 'user'), args.prompt);

      // Use the top-right Run button (playground-run-button testid) for inline runs.
      await this.runButton().click();

      const timeoutMs = args.timeoutMs ?? 60_000;
      // After clicking Run, the variant card shows the model's response inline.
      // Wait for any non-empty text that wasn't there before, scoped to the
      // variant card's bottom half (output area).
      const errorIndicator = this.variantCard(0).getByText(/error|failed/i);
      await expect
        .poll(
          async () => {
            // Detect completion via the "No runs yet" empty state disappearing OR
            // the page rendering an error.
            const noRunsYet = await this.page.getByText('No runs yet').count();
            const errored = (await errorIndicator.count()) > 0;
            return noRunsYet === 0 || errored;
          },
          { timeout: timeoutMs, intervals: [500, 1000, 2000] },
        )
        .toBeTruthy();

      // Grab the rendered response text. The Playground emits output to a region
      // that follows the variant card. We use the variant card root and trim its
      // textContent — this includes the model picker label etc., but a non-empty
      // result string is enough for sanity assertion.
      const cardText = ((await this.variantCard(0).textContent()) ?? '').trim();
      const isError = (await errorIndicator.count()) > 0;
      return { outputText: cardText, isError };
    });
  }

  /** Set the model for a variant — public wrapper for setModelForVariant. */
  async selectModel(index: number, modelDisplayName: string): Promise<void> {
    return test.step(`select model "${modelDisplayName}" for variant ${index}`, async () => {
      await this.setModelForVariant(index, modelDisplayName);
    });
  }

  /**
   * Select a model from a NAMED provider group.
   *
   * `selectModel` takes the first option matching the display name, which is
   * ambiguous for a model two providers both offer under one label: "Gemini 3
   * Flash Preview" is `gemini-3-flash-preview` under Gemini and
   * `vertex_ai/gemini-3-flash-preview` under Vertex AI. Those are the two
   * spellings the generation gate has to strip a prefix off, so they are
   * exactly the pair a spec must be able to tell apart — and taking `.first()`
   * would have it silently assert the same one twice.
   *
   * While the search box holds text the picker renders a flat list grouped by
   * provider, each group labelled, which is what makes the scope available.
   */
  async selectModelFromProvider(
    index: number,
    providerGroup: string,
    modelDisplayName: string,
  ): Promise<void> {
    return test.step(
      `select "${modelDisplayName}" from the ${providerGroup} group for variant ${index}`,
      async () => {
        assertAllowedModelDisplayName(modelDisplayName);
        const listbox = this.page.getByRole('listbox');
        await expect(async () => {
          await this.modelPicker(index).click();
          await expect(listbox).toBeVisible({ timeout: 2_000 });
        }).toPass({ timeout: 15_000 });

        // Re-filtered and re-clicked for the same reason as setModelForVariant:
        // the option list remounts when /llm/models resolves, detaching options
        // mid-click.
        await expect(async () => {
          await listbox.getByPlaceholder('Search model').fill(modelDisplayName);
          // By the group's accessible NAME, which Radix takes from its
          // `SelectLabel`, not by its text: a `hasText` filter for "Gemini"
          // also matches the Vertex AI group, whose options are all called
          // "Gemini …". Exact, so "Gemini" cannot select "Gemini (legacy)".
          const group = listbox.getByRole('group', { name: providerGroup, exact: true });
          // Exactly one group, so a provider label that stopped being unique
          // fails here instead of selecting from whichever matched first.
          await expect(group, `the ${providerGroup} provider group`).toHaveCount(1);
          const option = group.getByRole('option', { name: modelDisplayName, exact: true });
          await expect(option, `"${modelDisplayName}" under ${providerGroup}`).toHaveCount(1);
          await option.click({ timeout: 2_000 });
          await expect(listbox).toBeHidden({ timeout: 2_000 });
        }).toPass({ timeout: 30_000 });
      },
    );
  }

  // --- The model picker's scrolling list (ArrowScrollArea) ---
  //
  // Everything below drives the dropdown ITSELF rather than using it to pick a
  // known model, which is why none of it goes through `setModelForVariant`:
  // that helper types a full model name into "Search model" before clicking, so
  // the list it operates on is always one or two rows long and never scrolls.
  // A spec about the scroll affordance has to filter to MANY options and leave
  // them there.

  /** Open variant `index`'s model picker and resolve once the listbox is up. */
  async openModelPicker(index: number): Promise<void> {
    return test.step(`open the model picker for variant ${index}`, async () => {
      const listbox = this.page.getByRole('listbox');
      // Same first-click-swallowed-as-hover retry as setModelForVariant.
      await expect(async () => {
        await this.modelPicker(index).click();
        await expect(listbox).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 15_000 });
    });
  }

  /**
   * Type `term` into the open picker's "Search model" box and resolve once the
   * list holds exactly `expectedOptions` options.
   *
   * The count is required, not optional. Provider keys are workspace-global and
   * the suite runs `fullyParallel`, so another spec's provider can match the
   * same search term and quietly add its models to this list — which would send
   * "scroll to the bottom and click the last option" into somebody else's
   * group. Pinning the count turns that into an immediate, legible failure
   * instead of a wrong assertion.
   */
  async filterModelOptions(term: string, expectedOptions: number): Promise<void> {
    return test.step(`filter the model list to "${term}"`, async () => {
      const listbox = this.page.getByRole('listbox');
      await listbox.getByPlaceholder('Search model').fill(term);
      await expect(
        listbox.getByRole('option'),
        `"${term}" must match exactly the ${expectedOptions} models this test seeded — ` +
          'a different count means another provider in this workspace also matched',
      ).toHaveCount(expectedOptions);
    });
  }

  /** The scrolling viewport the filtered option list sits in. */
  get modelListViewport(): Locator {
    return this.page.getByTestId('arrow-scroll-viewport');
  }

  /**
   * One of the list's scroll arrows.
   *
   * `ArrowScrollArea` MOUNTS an arrow only while that direction can still
   * scroll, so a caller asserting an arrow is absent wants `toHaveCount(0)`
   * rather than `toBeHidden()` — the two are different claims and only the
   * first is the one this component makes.
   */
  modelListScrollArrow(direction: 'up' | 'down'): Locator {
    return this.page.getByTestId(`scroll-${direction}-arrow`);
  }

  /** How far the option list is currently scrolled, in whole pixels. */
  async modelListScrollTop(): Promise<number> {
    return Math.round(await this.modelListViewport.evaluate((v) => v.scrollTop));
  }

  /** Whether the option list is longer than the box showing it. */
  async modelListOverflows(): Promise<boolean> {
    return this.modelListViewport.evaluate((v) => v.scrollHeight > v.clientHeight);
  }

  /** The visible height of the option list's box, in pixels. */
  async modelListViewportHeight(): Promise<number> {
    return this.modelListViewport.evaluate((v) => v.clientHeight);
  }

  /**
   * Press a scroll arrow once and resolve when the list has stopped moving.
   *
   * The arrow scrolls with `behavior: "smooth"`, so the scrollTop right after
   * the click is mid-animation — polled to a settled value rather than read
   * once, and polled rather than slept on.
   */
  async clickModelListScrollArrow(direction: 'up' | 'down'): Promise<number> {
    return test.step(`click the ${direction} scroll arrow`, async () => {
      await this.modelListScrollArrow(direction).click();
      let settled = -1;
      await expect
        .poll(
          async () => {
            const previous = settled;
            settled = await this.modelListScrollTop();
            return previous === settled;
          },
          {
            timeout: 10_000,
            intervals: [100, 150, 250],
            message: 'the smooth scroll started by the arrow must come to rest',
          },
        )
        .toBe(true);
      return settled;
    });
  }

  /**
   * Scroll the option list to one end with the wheel, as a user would, and
   * resolve once it is there.
   *
   * A wheel rather than assigning `scrollTop`: the arrows are driven by the
   * viewport's own `onScroll`, and a programmatic assignment would be a weaker
   * reproduction of the gesture whose effect on them is the thing under test.
   * The delta is deliberately larger than the list so the browser clamps it at
   * the end, which is what makes one gesture enough however long the list is.
   */
  async scrollModelListTo(edge: 'top' | 'bottom'): Promise<void> {
    return test.step(`scroll the model list to the ${edge}`, async () => {
      const overscroll = await this.modelListViewport.evaluate((v) => v.scrollHeight + 200);
      await this.modelListViewport.hover();
      await this.page.mouse.wheel(0, edge === 'bottom' ? overscroll : -overscroll);
      await expect
        .poll(
          async () =>
            this.modelListViewport.evaluate(
              (v, atBottom) =>
                atBottom
                  ? Math.ceil(v.scrollTop) >= v.scrollHeight - v.clientHeight
                  : v.scrollTop === 0,
              edge === 'bottom',
            ),
          { timeout: 10_000, message: `the option list must come to rest at its ${edge}` },
        )
        .toBe(true);
    });
  }

  /**
   * Click one option of the open, filtered list by its exact label, and resolve
   * once the popover has closed.
   *
   * Scoped to `providerGroup` for the same reason `selectModelFromProvider` is:
   * while the search box holds text the list is flat and grouped by provider,
   * and a workspace-global provider seeded by a parallel spec can offer a model
   * under the same label.
   */
  async chooseModelOption(providerGroup: string, modelDisplayName: string): Promise<void> {
    return test.step(`click the option "${modelDisplayName}"`, async () => {
      assertAllowedModelDisplayName(modelDisplayName);
      const listbox = this.page.getByRole('listbox');
      const group = listbox.getByRole('group', { name: providerGroup, exact: true });
      await expect(group, `the ${providerGroup} provider group`).toHaveCount(1);
      const option = group.getByRole('option', { name: modelDisplayName, exact: true });
      await expect(option, `"${modelDisplayName}" under ${providerGroup}`).toHaveCount(1);
      await option.click();
      await expect(
        listbox,
        'clicking an option must close the picker — a click the scroll arrow ' +
          'swallowed instead would leave it open',
      ).toBeHidden();
    });
  }

  /** The model name variant `index`'s picker currently displays. */
  async selectedModelLabel(index: number): Promise<string> {
    return ((await this.modelPicker(index).textContent()) ?? '').trim();
  }

  /**
   * A named control's label inside the open model-parameters panel.
   *
   * For the controls that are not sliders — "Reasoning effort" and "Thinking
   * level" are a Radix `Select` whose trigger carries no id (their `<Label
   * htmlFor>` points at nothing, so `getByLabel` cannot reach them) — the
   * label text is the available handle. Exact, so "Max output tokens" cannot
   * be satisfied by a longer label.
   */
  modelParameterLabel(label: string): Locator {
    return this.modelParametersPanel().getByText(label, { exact: true });
  }

  /**
   * The Reasoning effort control's trigger inside the open model-parameters
   * panel.
   *
   * The control is a Radix `Select` whose `SelectTrigger` carries no id and no
   * testid, and whose `<Label htmlFor="reasoningEffort">` points at nothing —
   * so `getByLabel` cannot reach it. Its role is the handle: the OpenAI panel
   * mounts exactly one combobox (every other control is a `SliderInputControl`
   * number input), which is why callers assert `toHaveCount(1)` before reading
   * it rather than taking `.first()`. The FE should grow a
   * `data-testid="reasoning-effort-select"` here and this should move to it.
   *
   * Shares {@link effortSelectTrigger} with the Anthropic reader below, since
   * either panel mounts exactly one of these.
   */
  reasoningEffortTrigger(): Locator {
    return this.effortSelectTrigger();
  }

  /**
   * The Anthropic effort control's trigger inside the open model-parameters
   * panel — the same single combobox as {@link reasoningEffortTrigger}, since a
   * panel serves one provider at a time.
   *
   * Addressed by role rather than by its label even though `SelectBox` does put
   * the id on the trigger (so `getByLabel('Thinking effort')` resolves): the
   * label itself is under test. `AnthropicModelConfigs` renders "Effort" instead
   * of "Thinking effort" for a model declaring `effortWithoutThinking`, because
   * there the level changes answer length and tool use without turning thinking
   * on — so a label-based locator would silently stop matching on exactly the
   * model whose labelling is the assertion. Read the label with
   * {@link modelParameterLabel} and the value through here.
   */
  thinkingEffortTrigger(): Locator {
    return this.effortSelectTrigger();
  }

  /**
   * The effort the panel currently DISPLAYS.
   *
   * Which is not always the effort that is stored: `resolveEffort` masks a
   * stored value the selected model and pipeline mode do not offer, so a
   * prompt holding `max` on a Chat Completions key reads the model's default
   * ("Medium" on gpt-5.6-sol) here. That gap
   * is the subject of opik#8682, not an artefact of this reader.
   */
  async readReasoningEffort(): Promise<string> {
    return test.step('read the displayed reasoning effort', async () => this.readEffortValue());
  }

  /**
   * The Anthropic effort the panel currently DISPLAYS.
   *
   * Which is not always what the config holds: `resolveEffort` falls back to
   * the model's default for a stored level the selected model does not offer,
   * which is exactly what a model switch has to be read through — the panel is
   * the only place the reconciled level is visible before a Run.
   */
  async readThinkingEffort(): Promise<string> {
    return test.step('read the displayed thinking effort', async () => this.readEffortValue());
  }

  /**
   * Every effort the control OFFERS, in the order the panel lists them.
   *
   * The list is the assertion in its own right — `getOpenAIReasoningEffortOptions`
   * appends the Responses-API-only values to the base set, so "Max is absent"
   * and "the other five are still there" are different claims and a caller
   * wants to make both at once. The options render in a portal, so they are
   * read from the page's listbox rather than from inside the panel.
   */
  async reasoningEffortOptions(): Promise<string[]> {
    return test.step('read the offered reasoning efforts', async () => this.readEffortOptions());
  }

  /**
   * Every Anthropic effort the control OFFERS, in the order the panel lists them.
   *
   * The whole list is the assertion, not just "Off is there":
   * `getAnthropicThinkingEffortOptions` prepends Off only for a model that
   * thinks solely when asked, and each model declares its own level set — so
   * "Off was added" and "the levels behind it are unchanged" are separate
   * claims a caller wants to make in one go. A build that lost the Off choice
   * and one that lost the whole control look identical through a single
   * negative.
   */
  async thinkingEffortOptions(): Promise<string[]> {
    return test.step('read the offered thinking efforts', async () => this.readEffortOptions());
  }

  /** Pick a reasoning effort by its rendered label, e.g. `Max`. */
  async selectReasoningEffort(label: string): Promise<void> {
    return test.step(`select reasoning effort "${label}"`, async () => {
      await this.pickEffort(label);
    });
  }

  /** Pick an Anthropic thinking effort by its rendered label, e.g. `Off`. */
  async selectThinkingEffort(label: string): Promise<void> {
    return test.step(`select thinking effort "${label}"`, async () => {
      await this.pickEffort(label);
      // The trigger, not the config: a click Radix swallowed mid-remount would
      // otherwise leave the caller asserting the next step against the level it
      // meant to leave behind. Exact, so picking "High" cannot be satisfied by
      // a trigger still reading "xHigh".
      await expect(this.thinkingEffortTrigger()).toHaveText(label);
    });
  }

  /**
   * The note that replaces the sampling controls while Claude can think.
   *
   * Its WORDING is load-bearing, which is why this returns the element rather
   * than a boolean: the panel names the parameters the surface can actually
   * store, so the playground's two-parameter sentence and the judge rule's
   * temperature-only one are different strings, and a surface rendering the
   * other one is telling the user to go and set a control that is not there.
   */
  samplingOffNote(): Locator {
    return this.modelParametersPanel().getByText(/(?:are|is) off while Claude can think/);
  }

  /** Every slider control mounted in the open panel, by its control id. */
  async mountedModelParameterIds(): Promise<string[]> {
    return test.step('read the controls mounted in the model-parameters panel', async () => {
      const ids = await this.modelParametersPanel()
        .locator('input[data-testid$="-input"]')
        .evaluateAll((els) =>
          els.map((e) => (e.getAttribute('data-testid') ?? '').replace(/-input$/, '')),
        );
      return ids;
    });
  }

  /**
   * Click Run (free mode) and wait for the "No runs yet" placeholder to disappear.
   * The prompt content must already be loaded — this does NOT fill a message body.
   */
  async runFreeMode(timeoutMs = 120_000): Promise<void> {
    return test.step('run prompt (free mode)', async () => {
      await this.runButton().click();
      const errorText = this.page.getByText(/error|failed/i);
      await expect
        .poll(
          async () => {
            const noRunsYet = await this.page.getByText('No runs yet').count();
            const errored = (await errorText.count()) > 0;
            return noRunsYet === 0 || errored;
          },
          { timeout: timeoutMs, intervals: [500, 1000, 2000] },
        )
        .toBeTruthy();
    });
  }

  /**
   * Open the prompt library menu in the first variant card, hover the named prompt
   * to reveal the version submenu, and click the specified version label (e.g. "v1").
   *
   * Two things here are worked around rather than waited on, and neither is a
   * product defect — a human moving a mouse continuously hits neither:
   *
   * 1. The row list REMOUNTS when the search result lands, so a pointer already
   *    parked over the row raises no fresh `pointerenter` and the Radix
   *    `HoverCard` (`openDelay: 120`) never opens. The pointer is moved away and
   *    the row hovered again until the submenu is really up.
   * 2. The HoverCard content ANIMATES in, and a real click has to walk the
   *    pointer off the trigger to reach it — so Playwright's actionability check
   *    never settles and `click()` times out. The click is dispatched on the
   *    version row instead.
   */
  private async navigateLibraryMenuToVersion(promptName: string, versionLabel: string): Promise<void> {
    const menu = this.page.getByTestId('prompt-library-menu');
    await menu.waitFor({ state: 'visible' });

    await menu.getByPlaceholder('Search').fill(promptName);

    const promptRow = menu.getByRole('button').filter({ hasText: promptName }).first();
    await promptRow.waitFor({ state: 'visible' });

    const versionSubmenu = this.page.getByTestId('prompt-versions-submenu');

    const versionRow = versionSubmenu
      .getByRole('button')
      .filter({ has: this.page.getByText(versionLabel, { exact: true }) });

    // The hover, the count check and the click are retried as a GROUP, and the
    // search above is not: `PromptsSelectBox` mounts the menu with
    // `refetchOnMount`, so the list can re-render under the cursor and detach
    // the version submenu between opening it and clicking into it. Re-hovering
    // re-opens it against the row that is mounted now; re-filling the search
    // box would reset the list and lose the hover that opens the submenu at
    // all. The mouse is parked first so each re-hover is a real pointer move.
    await expect(async () => {
      await this.page.mouse.move(0, 0);
      await promptRow.hover();
      await expect(versionSubmenu).toBeVisible({ timeout: 2_000 });
      // Exactly one, not `.first()`: an ambiguous match would silently load a
      // version the caller did not ask for.
      await expect(versionRow, `version "${versionLabel}" of "${promptName}"`).toHaveCount(1, {
        timeout: 2_000,
      });
      await versionRow.dispatchEvent('click');
    }).toPass({ timeout: 20_000 });
  }

  async loadPromptVersionFromLibrary(promptName: string, versionLabel: string): Promise<void> {
    return test.step(`load prompt "${promptName}" version "${versionLabel}" from library`, async () => {
      // The button is in a div that only expands on group-hover; hover the card first.
      await this.variantCard(0).hover();
      await this.variantCard(0).getByTestId('load-prompt-button').click();

      await this.navigateLibraryMenuToVersion(promptName, versionLabel);
    });
  }

  /**
   * Open the text-prompt library menu in the User message row of variant 0,
   * hover the named prompt to reveal the version submenu, and click the specified
   * version label (e.g. "v1"). The button lives inside the message-row actions
   * area which is hidden until the row is hovered.
   */
  async loadTextPromptVersionFromLibrary(promptName: string, versionLabel: string): Promise<void> {
    return test.step(`load text prompt "${promptName}" version "${versionLabel}" from message-row library`, async () => {
      const messageRow = this.variantMessage(0, 'user');
      await messageRow.hover();
      await messageRow.getByTestId('load-text-prompt-button').click();

      await this.navigateLibraryMenuToVersion(promptName, versionLabel);
    });
  }

  /** Assert the LoadedPromptDisplay in variant 0 shows the given prompt name and version label. */
  async waitForLoadedPromptVersion(promptName: string, versionLabel: string): Promise<void> {
    return test.step(`wait for prompt "${promptName}" at version "${versionLabel}" to be loaded`, async () => {
      const card = this.variantCard(0);
      // For text prompts the loaded-prompt chip is inside the message-row actions
      // area which is only visible on hover (invisible group-hover:visible).
      // Hovering the User message row reveals it without affecting chat-prompt cards.
      await this.variantMessage(0, 'user').hover();
      await expect(card.getByText(promptName)).toBeVisible();
      await expect(card.getByText(versionLabel, { exact: true })).toBeVisible();
    });
  }

  // ── message rows addressed by index ─────────────────────────────────────
  //
  // `variantMessage` above addresses a row by `data-role`, which is right for a
  // card whose roles are known and distinct. It cannot be used by a spec that
  // CHANGES a row's role: the Playground's default card opens with a System row
  // and a User row, so switching the User row to System leaves two rows
  // answering to `[data-role="system"]` and the locator becomes ambiguous — it
  // resolves to the wrong one rather than failing. Index is stable across a role
  // change because the role dropdown does not reorder the card.

  /** Variant 0's message rows, in render order. */
  messageRows(variantIndex = 0): Locator {
    return this.variantCard(variantIndex).getByTestId('playground-message-row');
  }

  /** One message row of a variant card, by position rather than by role. */
  messageRowAt(messageIndex: number, variantIndex = 0): Locator {
    return this.messageRows(variantIndex).nth(messageIndex);
  }

  /** The role label a row's own dropdown trigger displays, e.g. "System". */
  async messageRoleAt(messageIndex: number, variantIndex = 0): Promise<string> {
    return test.step(`read the role of message ${messageIndex}`, async () => {
      const row = this.messageRowAt(messageIndex, variantIndex);
      // `data-role` carries the store's value and the trigger carries what the
      // user reads; the trigger is the one asserted, so a card that kept the
      // attribute while rendering another label fails rather than passes.
      return (await row.locator('button').first().innerText()).trim();
    });
  }

  // ── persisted store (PLAYGROUND_STATE) ──────────────────────────────────
  //
  // Read and written directly because this is where the behaviour under test
  // lives. `appliedChatPromptVersionId` is the record of WHICH library version
  // was last applied to the editors, it is a field of the persisted prompt, and
  // nothing renders it — so a spec about which version a remount re-applies
  // cannot observe it any other way. The blob is also the only way to express
  // "state written by a build that predates the marker", which is a real user's
  // localStorage after an upgrade and is unreachable through the UI by
  // construction.

  /**
   * Arm a wait for the Playground's own fetch of one prompt version, and for
   * the render that follows it.
   *
   * ARM IT BEFORE the navigation or reload it covers, and await the returned
   * promise after. This is the signal a "the template was NOT re-applied"
   * assertion needs: the re-apply happens the moment this response lands, so a
   * spec that asserted before it did would go green on a build that clobbers
   * the editors a tick later — the exact regression these specs exist for.
   *
   * `/prompts/versions/{id}` is `usePromptVersionById`'s request, which the
   * variant card issues on every mount (the query carries no `staleTime`, so
   * coming back through the SPA refetches as surely as a reload does).
   */
  waitForPromptVersionFetch(versionId: string): Promise<void> {
    return this.page
      .waitForResponse(
        (response) =>
          response.url().includes(`/prompts/versions/${versionId}`) && response.ok(),
        { timeout: 30_000 },
      )
      .then(() => this.settle());
  }

  /**
   * The single persisted Playground prompt.
   *
   * Exactly one, asserted rather than indexed: every caller here drives one
   * variant card, and a second prompt in the map would mean the read is
   * reporting a card the assertions never touched.
   */
  async readStoredPrompt(): Promise<StoredChatPrompt> {
    return test.step('read the persisted Playground prompt', async () => {
      const state = await this.readStoredState();
      const prompts = Object.values(state.promptMap ?? {});
      expect(prompts, 'the persisted Playground holds exactly one prompt').toHaveLength(1);
      return prompts[0];
    });
  }

  /**
   * Change a message row's role through the dropdown a user would use.
   *
   * Resolves only once the trigger shows the new role, so a caller reading the
   * row's content "after the switch" cannot read it before the switch landed.
   * The dropdown is a Radix checkbox menu, so the item carries the
   * `menuitemcheckbox` role rather than `option`.
   */
  async setMessageRole(
    messageIndex: number,
    role: 'System' | 'Assistant' | 'User',
    variantIndex = 0,
  ): Promise<void> {
    return test.step(`switch message ${messageIndex} to ${role}`, async () => {
      const row = this.messageRowAt(messageIndex, variantIndex);
      await row.locator('button').first().click();
      await this.page.getByRole('menuitemcheckbox', { name: role, exact: true }).click();
      await expect(
        row.locator('button').first(),
        `message ${messageIndex} is now a ${role} message`,
      ).toHaveText(role);
    });
  }

  /**
   * The store's one-shot migration flag, `appliedChatPromptVersionsBackfilled`.
   *
   * `boolean | undefined` and never defaulted: absent is precisely the pre-fix
   * state the backfill keys off, so collapsing it into `false` would erase the
   * difference a hydration assertion exists to check.
   */
  async readStoredBackfillFlag(): Promise<boolean | undefined> {
    return test.step('read the persisted applied-version backfill flag', async () => {
      return (await this.readStoredState()).appliedChatPromptVersionsBackfilled;
    });
  }

  /**
   * Hover a message row so the strip holding its load button, its save button
   * and its loaded-prompt chip is on screen.
   *
   * That strip is `invisible group-hover:visible [.group:focus-within_&]:visible`,
   * so none of those three is VISIBLE until the row is hovered or focused —
   * which makes this a precondition of every visibility assertion about them,
   * not a convenience. Typing into the row's editor focuses it and has the same
   * effect, so a spec that has just edited the message does not need this.
   */
  async hoverMessageRow(messageIndex: number, variantIndex = 0): Promise<void> {
    return test.step(`hover message ${messageIndex}`, async () => {
      await this.messageRowAt(messageIndex, variantIndex).hover();
    });
  }

  /**
   * Assert a message row's chip shows the given prompt at the given version.
   *
   * The row-scoped counterpart of `waitForLoadedPromptVersion`, which addresses
   * variant 0's User row specifically and so cannot see a load into a System or
   * Assistant row.
   *
   * Two races make this a poll rather than two `toBeVisible()` calls, and both
   * have been seen on staging.
   *
   * **The hover is re-applied on every attempt.** The strip holding the chip is
   * `invisible group-hover:visible`, so it is on screen only while the cursor is
   * over the row — and applying a loaded prompt REBUILDS the message rows as
   * fresh DOM nodes. A single `hover()` is one synthetic mouse move: once the
   * node under the cursor has been replaced nothing re-fires it, so the strip
   * goes hidden again and a plain `toBeVisible()` waits out its whole timeout
   * watching a hidden element it can never un-hide.
   *
   * **The store has to have the content, not just the chip the name.** The chip
   * and the persisted messages are written by different effects and the chip
   * wins, so settling on the chip alone hands the caller a row whose stored
   * content is still its PRE-LOAD value — empty, on the default card. Every
   * caller then reads `storedMessages()` and compares, so that is a wrong
   * answer rather than a slow one.
   *
   * The store condition is deliberately only "the row has content at all",
   * which is weaker than any caller's claim: whether that content is the exact
   * string, or the exact array with its media parts intact, is what the
   * assertions at the call sites are for. Waiting on the full claim would make
   * them restate this wait and assert nothing of their own.
   */
  async waitForLoadedPromptVersionInMessage(
    messageIndex: number,
    promptName: string,
    versionLabel: string,
    variantIndex = 0,
  ): Promise<void> {
    return test.step(
      `wait for "${promptName}" ${versionLabel} to be loaded into message ${messageIndex}`,
      async () => {
        const row = this.messageRowAt(messageIndex, variantIndex);
        const chip = this.loadedPromptChip(messageIndex, promptName, variantIndex);
        const version = row.getByText(versionLabel, { exact: true });
        await expect
          .poll(
            async () => {
              await this.messageRowAt(messageIndex, variantIndex).hover();
              if (!(await chip.isVisible()) || !(await version.isVisible())) return false;
              const content = (await this.readStoredMessages())?.[messageIndex]?.content;
              return Array.isArray(content)
                ? content.length > 0
                : typeof content === 'string' && content.length > 0;
            },
            {
              timeout: 30_000,
              intervals: [250, 500, 1_000],
              message:
                `message ${messageIndex} never ended up holding "${promptName}" at ` +
                `${versionLabel} — chip and persisted content both have to land`,
            },
          )
          .toBe(true);
      },
    );
  }

  /**
   * A media tag in a message row, addressed by the URL it displays.
   *
   * By its text and not by the delete button inside it: that button is
   * `hidden` until the tag itself is hovered, which keeps it out of the
   * accessibility tree, so `getByRole('button', { name: 'Delete image' })`
   * finds nothing whether or not the tag is there — the exact failure mode an
   * absence assertion must not have. `MediaTagsList` renders the URL verbatim
   * as the tag's text (the truncation is CSS), so an exact text match addresses
   * one tag and reads as what the user sees.
   */
  mediaTag(messageIndex: number, url: string, variantIndex = 0): Locator {
    return this.messageRowAt(messageIndex, variantIndex).getByText(url, { exact: true });
  }

  /**
   * Open a message row's prompt-library menu and load one version into THAT
   * row — the per-row control, which is what carries the target message's role
   * into the load (opik#8873).
   *
   * The actions strip is `invisible group-hover:visible`, so the row is hovered
   * before the button is addressed, exactly as `loadTextPromptVersionFromLibrary`
   * does for the User row.
   */
  async loadTextPromptVersionIntoMessage(
    messageIndex: number,
    promptName: string,
    versionLabel: string,
    variantIndex = 0,
  ): Promise<void> {
    return test.step(
      `load text prompt "${promptName}" ${versionLabel} into message ${messageIndex}`,
      async () => {
        const row = this.messageRowAt(messageIndex, variantIndex);
        await row.hover();
        await row.getByTestId('load-text-prompt-button').click();

        await this.navigateLibraryMenuToVersion(promptName, versionLabel);
      },
    );
  }

  /**
   * The loaded-prompt chip in a message row — present only once a library
   * prompt is linked to it, since the chip and the load button share the one
   * slot `PromptsSelectBox` renders into.
   *
   * By the prompt's own name, so a chip belonging to another row (or to a load
   * that linked the wrong prompt) is not what gets read.
   */
  loadedPromptChip(messageIndex: number, promptName: string, variantIndex = 0): Locator {
    return this.messageRowAt(messageIndex, variantIndex).getByText(promptName, { exact: true });
  }

  /**
   * The amber dot `LoadedPromptDisplay` puts on the chip when the message no
   * longer matches the version it was loaded from.
   *
   * A class-scoped CSS selector, which is a last resort and deliberate: the dot
   * is a bare decorative `<span>` with no text, no role and no testid, and
   * adding one would mean touching `apps/opik-frontend` from a spec that has to
   * run against a deployed Opik built without it. Scoped to the row, and
   * `saveTooltipText` below asserts the same flag through its own independent
   * text handle — so a renamed utility class shows up as one of the two
   * assertions disagreeing rather than as a silent pass.
   */
  unsavedChangesDot(messageIndex: number, variantIndex = 0): Locator {
    return this.messageRowAt(messageIndex, variantIndex).locator('span.rounded-full.bg-warning');
  }

  /**
   * Hover a row's save-to-library button and read the tooltip it raises.
   *
   * The tooltip is the user-readable half of the same `saveWarning` flag the
   * dot renders: "Save to prompt library" when the message still matches the
   * loaded version, and "This prompt version hasn't been saved" when it does
   * not. Radix portals the content out of the row, so it is read at page scope.
   */
  async saveTooltipText(messageIndex: number, variantIndex = 0): Promise<string> {
    return test.step(`read message ${messageIndex}'s save tooltip`, async () => {
      const row = this.messageRowAt(messageIndex, variantIndex);
      await row.hover();
      const save = row.getByTestId('save-text-prompt-button');
      await expect(save, 'the row offers a save-to-library button').toHaveCount(1);
      await save.hover();
      const tooltip = this.page.getByRole('tooltip');
      await expect(tooltip.first()).toBeVisible({ timeout: 10_000 });
      return (await tooltip.first().innerText()).trim();
    });
  }

  /**
   * Variant 0's messages as the persisted `PLAYGROUND_STATE` holds them —
   * role and content, not just the ids `storedMessageIds` reads.
   *
   * The store is the only place the SHAPE of a message's content is observable.
   * On a system or assistant row the editor renders the text part either way,
   * and the media tags never render there at all (`showMediaActions` gates them
   * on the user role), so "the media was dropped" and "the media is still in
   * the message but invisible" look identical on screen — and the second is
   * precisely the bug opik#8873 fixed. Reading localStorage follows
   * `storedMessageIds` and `LogsPage.readStoredFilters`.
   */
  async storedMessages(): Promise<Array<{ id: string; role: string; content: unknown }>> {
    return test.step('read variant 0\'s messages from PLAYGROUND_STATE', async () => {
      const messages = await this.readStoredMessages();
      // Asserted rather than defaulted to []: an absent store is a different
      // failure from a prompt with no messages, and an empty array would
      // satisfy a "no media in any message" check without reading anything.
      expect(messages, 'PLAYGROUND_STATE holds variant 0 and its messages').not.toBeNull();
      return messages as Array<{ id: string; role: string; content: unknown }>;
    });
  }

  /**
   * The same read as {@link storedMessages}, without the `test.step` or the
   * not-null assertion.
   *
   * Exists so a poll can call it repeatedly: wrapped in a step it would stamp
   * one entry per attempt onto the trace and bury the phase being waited for,
   * and the assertion would fail the poll outright on the first tick before the
   * store exists rather than waiting for it.
   */
  private async readStoredMessages(): Promise<
    Array<{ id: string; role: string; content: unknown }> | null
  > {
    return this.page.evaluate(() => {
      const raw = window.localStorage.getItem('PLAYGROUND_STATE');
      if (!raw) return null;
      const state = (JSON.parse(raw) as { state?: unknown }).state as
        | {
            promptIds?: string[];
            promptMap?: Record<
              string,
              { messages?: Array<{ id?: string; role?: string; content?: unknown }> }
            >;
          }
        | undefined;
      const first = state?.promptIds?.[0];
      if (!first) return null;
      const stored = state?.promptMap?.[first]?.messages;
      if (!stored) return null;
      return stored.map((m) => ({
        id: m.id ?? '',
        role: m.role ?? '',
        content: m.content,
      }));
    });
  }

  /** Type into one message row of variant 0, addressed by position. */
  async fillMessageAt(messageIndex: number, text: string, variantIndex = 0): Promise<void> {
    return test.step(`type into message ${messageIndex}`, async () => {
      await this.fillMessageBody(this.messageRowAt(messageIndex, variantIndex), text);
    });
  }

  /**
   * Overwrite the persisted prompt with `planted`, and prove the write landed.
   *
   * PRECONDITION: call this from a page the Playground is NOT mounted on (the
   * Prompt library, say). The store persists on every change, so a plant made
   * while the Playground is live races the app's own next write.
   *
   * The blob is edited in place rather than built from scratch, so the planted
   * state keeps the exact shape this build writes — a hand-rolled one would
   * drift from it silently and the spec would then be asserting against a
   * fiction. Absent optional fields are DELETED, which is what makes
   * "written before this key existed" expressible at all.
   */
  async plantStoredPrompt(planted: PlantedChatPrompt): Promise<void> {
    return test.step('plant a persisted Playground prompt', async () => {
      const written = await this.page.evaluate(
        ({ key, seed }) => {
          const raw = window.localStorage.getItem(key);
          if (!raw) throw new Error(`${key} is not in localStorage yet`);
          const blob = JSON.parse(raw);
          const map = blob.state?.promptMap ?? {};
          const ids = Object.keys(map);
          if (ids.length !== 1) {
            throw new Error(`${key} holds ${ids.length} prompts, expected exactly 1`);
          }
          const prompt = map[ids[0]];

          prompt.loadedChatPromptId = seed.loadedChatPromptId;
          if (seed.loadedChatPromptVersionId === undefined) {
            delete prompt.loadedChatPromptVersionId;
          } else {
            prompt.loadedChatPromptVersionId = seed.loadedChatPromptVersionId;
          }
          if (seed.appliedChatPromptVersionId === undefined) {
            delete prompt.appliedChatPromptVersionId;
          } else {
            prompt.appliedChatPromptVersionId = seed.appliedChatPromptVersionId;
          }
          prompt.messages = seed.messages.map((message, index) => ({
            ...message,
            id: `planted-${index}`,
          }));

          if (seed.backfilled === undefined) {
            delete blob.state.appliedChatPromptVersionsBackfilled;
          } else {
            blob.state.appliedChatPromptVersionsBackfilled = seed.backfilled;
          }

          window.localStorage.setItem(key, JSON.stringify(blob));
          return JSON.parse(window.localStorage.getItem(key)!);
        },
        { key: PLAYGROUND_STORE_KEY, seed: planted },
      );

      // The plant is the whole precondition of every assertion that follows, so
      // it is read back here rather than taken on trust: a blob that silently
      // failed to change would leave a test that cannot fail.
      const prompt = Object.values(
        written.state.promptMap as Record<string, StoredChatPrompt>,
      )[0];
      expect(prompt.loadedChatPromptId).toBe(planted.loadedChatPromptId);
      expect(prompt.loadedChatPromptVersionId).toBe(planted.loadedChatPromptVersionId);
      expect(prompt.appliedChatPromptVersionId).toBe(planted.appliedChatPromptVersionId);
      expect(prompt.messages.map((m) => m.content)).toEqual(
        planted.messages.map((m) => m.content),
      );
      expect(written.state.appliedChatPromptVersionsBackfilled).toBe(planted.backfilled);
    });
  }

  private async readStoredState(): Promise<{
    promptMap?: Record<string, StoredChatPrompt>;
    appliedChatPromptVersionsBackfilled?: boolean;
  }> {
    const raw = await this.page.evaluate(
      (key) => window.localStorage.getItem(key),
      PLAYGROUND_STORE_KEY,
    );
    expect(raw, `${PLAYGROUND_STORE_KEY} in localStorage`).not.toBeNull();
    return JSON.parse(raw!).state;
  }

  /** Edit the content of the User message in variant 0 directly in the Playground editor. */
  async editUserMessage(newContent: string): Promise<void> {
    return test.step('edit the user message in Playground', async () => {
      const editor = this.variantMessage(0, 'user').locator('.cm-content').first();
      await editor.click();
      await editor.fill(newContent);
    });
  }

  private async submitSaveDialog(promptName?: string): Promise<void> {
    const dialog = this.page.getByRole('dialog', { name: 'Save to prompt library' });
    await dialog.waitFor({ state: 'visible' });
    if (promptName) {
      await dialog.getByLabel('Name').fill(promptName);
    }
    await dialog.getByRole('button', { name: 'Save to library' }).click();
    await dialog.waitFor({ state: 'hidden' });
  }

  /**
   * Click the Save button (disk icon) in the User message row of variant 0 and submit the
   * "Save to prompt library" dialog in "Update existing" mode (text prompts).
   * Assumes a text prompt is already loaded so the dialog defaults to update mode.
   */
  async saveTextPromptToLibrary(): Promise<void> {
    return test.step('save text prompt to library from Playground', async () => {
      const messageRow = this.variantMessage(0, 'user');
      await messageRow.hover();
      await messageRow.getByTestId('save-text-prompt-button').click();
      await this.submitSaveDialog();
    });
  }

  /**
   * Click the Save button in the User message row of variant 0 and submit the
   * "Save to prompt library" dialog as a new text prompt.
   * Fills the given name and clicks "Save to library".
   */
  async saveNewTextPromptToLibrary(promptName: string): Promise<void> {
    return test.step(`save new text prompt "${promptName}" to library from Playground`, async () => {
      const messageRow = this.variantMessage(0, 'user');
      await messageRow.hover();
      await messageRow.getByTestId('save-text-prompt-button').click();
      await this.submitSaveDialog(promptName);
    });
  }

  /**
   * Click the Save button in variant 0 and submit the "Save to prompt library"
   * dialog in "Save as new" mode — for prompts NOT imported from the library.
   * Fills the given name and clicks "Save to library".
   */
  async saveNewChatPromptToLibrary(promptName: string): Promise<void> {
    return test.step(`save new chat prompt "${promptName}" to library from Playground`, async () => {
      await this.variantCard(0).hover();
      await this.page.getByTestId('playground-save-prompt-button').click();
      await this.submitSaveDialog(promptName);
    });
  }

  /**
   * Click the Save button (disk icon) in variant 0 and submit the
   * "Save to prompt library" dialog in "Update existing" mode.
   * Assumes a chat prompt is already loaded so the dialog defaults to update mode.
   */
  async savePromptToLibrary(): Promise<void> {
    return test.step('save prompt to library from Playground', async () => {
      await this.variantCard(0).hover();
      await this.page.getByTestId('playground-save-prompt-button').click();
      await this.submitSaveDialog();
    });
  }

  /**
   * Save variant 0's chat prompt in "Update existing" mode and hand back the
   * version the server answered with.
   *
   * Exists because `POST /v1/private/prompts/versions` answers 200 whether it
   * committed a new version or deduped an unchanged save onto the existing one
   * (opik#8923) — the status says nothing, and `id`/`commit`/`version_number`
   * in the body are the only place the two outcomes differ. A spec about the
   * dedup therefore has to read the response, not just the page afterwards: the
   * page settling on "v3" would also be satisfied by a server that minted v4
   * and a timeline that had not refetched.
   */
  async savePromptToLibraryCapturingVersion(): Promise<SavedPromptVersionResponse> {
    return test.step('save prompt to library and capture the version the server returned', async () => {
      await this.variantCard(0).hover();
      await this.page.getByTestId('playground-save-prompt-button').click();

      // Armed before the submit, not after: the save is one request and its
      // response can land before the dialog has finished animating closed, so a
      // wait registered after `submitSaveDialog` returns can miss it entirely.
      const posted = this.page.waitForResponse(
        (res) =>
          res.url().includes('/v1/private/prompts/versions') &&
          res.request().method() === 'POST',
      );
      await this.submitSaveDialog();
      const response = await posted;

      const status = response.status();
      if (status !== 200) {
        throw new Error(
          `save to library answered ${status}: ${await response.text()}` +
            ' — the save itself failed, so nothing downstream is about the dedup',
        );
      }

      return (await response.json()) as SavedPromptVersionResponse;
    });
  }

  /** Click the "Go to logs" icon button to open the Playground logs sidebar. */
  async openLogsPanel(): Promise<void> {
    return test.step('open Playground logs panel', async () => {
      await this.page.getByTestId('playground-logs-sidebar-button').click();
    });
  }

  /** Set a single model-config option (e.g., temperature, max_tokens) on a variant. */
  async setVariantOption(index: number, optionName: string, value: number | string): Promise<void> {
    return test.step(`set variant ${index} option "${optionName}" to ${value}`, async () => {
      // The options pane is collapsed by default; expand it first. Implementation
      // is exploratory — Phase 4 will refine if discovery reveals a different shape.
      const card = this.variantCard(index);
      // Try to open the options pane via the gear/settings affordance.
      const settingsButton = card.getByRole('button', { name: /settings|options/i });
      if ((await settingsButton.count()) > 0) {
        await settingsButton.first().click();
      }
      // Fill the named input. Provider-sanity tests use a small whitelist of
      // option names that map to spinbutton inputs.
      const input = card.getByRole('spinbutton', { name: new RegExp(`^${optionName}$`, 'i') });
      if ((await input.count()) > 0) {
        await input.first().fill(String(value));
      }
    });
  }

  /**
   * Whether the model picker offers a model, by display name. Leaves the picker
   * closed either way.
   *
   * The option list comes from the deployment's own model registry and from the
   * provider keys configured on the workspace, so a model this suite names may
   * simply not be there. Probing lets a spec skip on that rather than spend
   * `setModelForVariant`'s retry budget failing to click an option that will
   * never appear.
   */
  async isModelOffered(index: number, modelDisplayName: string): Promise<boolean> {
    return test.step(`check whether "${modelDisplayName}" is offered`, async () => {
      const listbox = this.page.getByRole('listbox');
      await expect(async () => {
        await this.modelPicker(index).click();
        await expect(listbox).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 15_000 });

      await listbox.getByPlaceholder('Search model').fill(modelDisplayName);
      const offered = await listbox
        .getByRole('option', { name: modelDisplayName, exact: true })
        .first()
        .waitFor({ state: 'visible', timeout: 5_000 })
        .then(() => true)
        .catch(() => false);

      await this.page.keyboard.press('Escape');
      await expect(listbox).toBeHidden();
      return offered;
    });
  }

  /** Open a variant's model-parameters popover and wait for it to render. */
  async openModelParameters(index: number): Promise<void> {
    return test.step(`open model parameters for variant ${index}`, async () => {
      await this.modelParametersTrigger(index).click();
      await this.modelParametersPanel().waitFor({ state: 'visible' });
    });
  }

  /**
   * Close the model-parameters popover, and wait until it is really gone.
   *
   * Escape is pressed until the panel actually goes, not once: the dropdown
   * swallows the first Escape while focus is still on the menu CONTAINER, and
   * only a second press closes it. A caller that has already clicked an
   * interactive child (moving focus into the panel) gets out on the first
   * press, which is why this went unnoticed — but a spec that only reads the
   * panel never moves focus and would hang on a single press.
   */
  async closeModelParameters(): Promise<void> {
    return test.step('close model parameters', async () => {
      const panel = this.modelParametersPanel();
      await expect
        .poll(
          async () => {
            if (!(await panel.isVisible().catch(() => false))) return false;
            await this.page.keyboard.press('Escape');
            return panel.isVisible().catch(() => false);
          },
          { message: 'the model-parameters panel closes', intervals: [100, 250, 500, 1000] },
        )
        .toBe(false);
    });
  }

  /**
   * The open model-parameters popover.
   *
   * One is open at a time — it is a `DropdownMenu`, and Radix unmounts the
   * content of a closed one — so this needs no variant scoping.
   */
  modelParametersPanel(): Locator {
    return this.page.getByRole('menu');
  }

  /**
   * The Sampling choice's two options. Anthropic takes Temperature or Top P and
   * never both, so which one carries `data-state="on"` is the panel's claim
   * about what the request will contain.
   */
  samplingOption(label: 'Temperature' | 'Top P'): Locator {
    return this.modelParametersPanel().getByRole('radio', { name: label, exact: true });
  }

  /** Every Sampling option currently selected — asserted to be exactly one. */
  selectedSamplingOptions(): Locator {
    return this.modelParametersPanel().locator('[role="radio"][data-state="on"]');
  }

  /**
   * The number input of a named slider control, e.g. `temperature` or `topP`.
   *
   * Presence is the assertion, not just the value: the panel renders the live
   * half of the sampling pair and unmounts the other, so a control that is
   * merely dimmed — or two that are both mounted — is the regression.
   */
  sliderInput(controlId: string): Locator {
    return this.page.getByTestId(`${controlId}-input`);
  }

  /**
   * The THUMB of a named slider control, which carries the control's live range.
   *
   * `SliderInputControl` puts the control id on the Slider root (`<id>-slider`)
   * and Radix renders the thumb inside it with `role="slider"`, so the root is
   * the only handle that scopes a range read to one control. Neither carries a
   * testid; one added now would be missing from the deployed versions this
   * suite also runs against — the same trade-off `modelParametersTrigger`
   * carries.
   */
  sliderThumb(controlId: string): Locator {
    return this.modelParametersPanel().locator(`#${controlId}-slider`).getByRole('slider');
  }

  /**
   * The minimum and maximum a named slider control currently offers.
   *
   * Read off the thumb's `aria-value*` attributes: that is what Radix derives
   * from the control's own `min`/`max` props and what assistive technology is
   * told, so a panel rendering a stale range fails here. Asserted unique
   * first — a control mounted twice would otherwise have its range read from
   * whichever the DOM ordered first.
   *
   * Both attributes are asserted present before being converted, rather than
   * defaulted: `Number(null)` is 0, which is a plausible-looking floor and
   * would make a missing attribute read as a real measurement.
   */
  async readSliderRange(controlId: string): Promise<{ min: number; max: number }> {
    return test.step(`read the ${controlId} range`, async () => {
      const thumb = this.sliderThumb(controlId);
      await expect(thumb, `exactly one "${controlId}" slider is mounted`).toHaveCount(1);
      const [min, max] = await Promise.all([
        thumb.getAttribute('aria-valuemin'),
        thumb.getAttribute('aria-valuemax'),
      ]);
      expect(min, `the ${controlId} slider reports a minimum`).not.toBeNull();
      expect(max, `the ${controlId} slider reports a maximum`).not.toBeNull();
      return { min: Number(min), max: Number(max) };
    });
  }

  /**
   * Type a value into a named slider control's number input and commit it.
   *
   * The click is load-bearing and not redundant with `fill()`: it moves the
   * pointer off the gear trigger, whose hover tooltip would otherwise sit above
   * the menu and swallow the Escape that `closeModelParameters` sends.
   * `SliderInputControl` writes through to the config on blur, so the blur is
   * what commits — a value left focused is still only local state.
   */
  async setSliderValue(controlId: string, value: string): Promise<void> {
    return test.step(`set ${controlId} to ${value}`, async () => {
      const input = this.sliderInput(controlId);
      await input.click();
      await input.fill(value);
      await input.blur();
    });
  }

  /**
   * Type a value into a named slider control and commit it to the config.
   *
   * Three steps, each of which the control needs:
   *  - a real click first, not just `fill()`: it moves the pointer off the gear,
   *    whose hover tooltip would otherwise sit above the menu and swallow the
   *    Escape that {@link closeModelParameters} sends;
   *  - `fill()`;
   *  - a blur, because `SliderInputControl` writes through `onChange` on blur
   *    (or Enter/Tab/Escape) only, so a value left focused is displayed but not
   *    stored, and the next Run would carry the old one.
   *
   * Asserted back off the control afterwards: a value the control clamped to
   * its own min/max is silently not the one the caller asked for, and a spec
   * comparing the request against "the value I set" would then be comparing it
   * against a number that was never in the config.
   *
   * Distinct from {@link setSliderValue}, which deliberately does NOT read the
   * value back: the max-output-tokens specs set values ABOVE the cap and BELOW
   * the floor precisely to assert the control clamps them, so a read-back
   * assertion there would fail on the behaviour under test.
   */
  async setModelParameterSlider(controlId: string, value: string): Promise<void> {
    return test.step(`set ${controlId} to ${value}`, async () => {
      const input = this.sliderInput(controlId);
      await input.click();
      await input.fill(value);
      await input.blur();
      await expect(
        input,
        `${controlId} holds ${value} — a clamped or rejected value is not the one under test`,
      ).toHaveValue(value);
    });
  }

  /**
   * The hint line a named slider control renders beneath itself — for Max
   * output tokens, the "No limit" note `getNoOutputLimitHint` returns at 0.
   *
   * `SliderInputControl` renders it as a `<p>` immediately after the Slider
   * root and only when a hint is passed, so its ABSENCE is as much the
   * assertion as its text; hence a locator rather than a text read. Addressed
   * through the slider's id because the hint has no handle of its own, and a
   * panel-wide text lookup would match whichever control happened to show one.
   */
  sliderHint(controlId: string): Locator {
    return this.modelParametersPanel().locator(`#${controlId}-slider ~ p`);
  }

  /** Type a prompt into variant 0's User message row. */
  async fillUserMessage(text: string): Promise<void> {
    return test.step('fill the user message of variant 0', async () => {
      await this.fillMessageBody(this.variantMessage(0, 'user'), text);
    });
  }

  /** Click Run (free mode) without waiting for the completion to come back. */
  async clickRun(): Promise<void> {
    return test.step('click Run', async () => {
      await this.runButton().click();
    });
  }

  /**
   * Wait until a free-mode run has finished and the playground is idle again.
   *
   * The Run button is the signal: it is swapped for Stop while a run is in
   * flight, so "back in run/re-run mode" is exactly "safe to touch the model
   * picker or the parameters panel again". A spec that drives several models in
   * one page session needs that between cases — the completion POST goes out as
   * the run STARTS, so capturing the body says nothing about the run being
   * over. `waitForRunSettled` is the dataset-mode counterpart and watches the
   * experiment-name editor, which free mode never renders.
   */
  async waitForFreeRunIdle(timeoutMs = 60_000): Promise<void> {
    return test.step('wait for the free-mode run to go idle', async () => {
      await expect(
        this.runButton(),
        'the run finished and the playground is idle again',
      ).toBeVisible({ timeout: timeoutMs });
    });
  }

  // ── private helpers ─────────────────────────────────────────────────────

  /**
   * The one effort `Select` the open model-parameters panel mounts.
   *
   * Shared by the OpenAI reasoning-effort and Anthropic thinking-effort
   * readers: a panel serves a single provider, and every other control it
   * renders is a `SliderInputControl` number input or a sampling radio — so
   * "the panel's only combobox" is the effort dropdown whichever provider is
   * selected. Callers assert `toHaveCount(1)` before reading it rather than
   * taking `.first()`, so a panel that grew a second dropdown fails loudly
   * instead of reading whichever one happened to come first.
   */
  private effortSelectTrigger(): Locator {
    return this.modelParametersPanel().getByRole('combobox');
  }

  private async readEffortValue(): Promise<string> {
    const trigger = this.effortSelectTrigger();
    await expect(trigger, 'the panel mounts exactly one effort control').toHaveCount(1);
    return ((await trigger.textContent()) ?? '').trim();
  }

  /**
   * Open the effort dropdown, read every option, close it again.
   *
   * The options render in a portal, so they are read from the page's listbox
   * rather than from inside the panel.
   */
  private async readEffortOptions(): Promise<string[]> {
    const listbox = await this.openEffortListbox();
    const options = await listbox.getByRole('option').allTextContents();
    await this.page.keyboard.press('Escape');
    await expect(listbox).toBeHidden();
    return options.map((o) => o.trim());
  }

  private async pickEffort(label: string): Promise<void> {
    const listbox = await this.openEffortListbox();
    const option = listbox.getByRole('option', { name: label, exact: true });
    // Exactly one, not `.first()`: "High" and "xHigh" are both offered, and
    // an ambiguous match would silently store the wrong effort.
    await expect(option, `"${label}" is offered`).toHaveCount(1);
    await option.click();
    await expect(listbox).toBeHidden();
  }

  private async openEffortListbox(): Promise<Locator> {
    const trigger = this.effortSelectTrigger();
    await expect(trigger, 'the panel mounts exactly one effort control').toHaveCount(1);
    const listbox = this.page.getByRole('listbox');
    // Retried: the panel remounts when /llm/models and the provider-key query
    // resolve, which detaches the trigger mid-click.
    await expect(async () => {
      await trigger.click();
      await expect(listbox).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });
    return listbox;
  }

  /**
   * The gear button that opens a variant's model parameters.
   *
   * Anchored to the model picker rather than addressed directly: the trigger
   * carries no testid and no accessible name (its tooltip is a Radix
   * `TooltipContent`, not an `aria-label`), and the variant card holds other
   * `aria-haspopup="menu"` buttons — every message row's role selector is one.
   * "The menu button immediately after the model picker" is the one stable
   * description available. A `data-testid` on `PromptModelConfigs`' trigger
   * would be better, but these specs run against a deployed Opik, where an
   * attribute added alongside them would not exist in the version under test.
   */
  private modelParametersTrigger(index: number): Locator {
    return this.variantCard(index).locator(
      'button:has(> [data-testid="select-a-llm-model"]) + button[aria-haspopup="menu"]',
    );
  }

  private runExperimentTriggerButton(): Locator {
    return this.page
      .getByTestId('playground-run-button')
      .and(this.page.locator('[data-mode="experiment-trigger"]'));
  }

  private runButton(): Locator {
    // The actual run button is whichever is currently rendered: experiment-trigger,
    // run, or re-run. They all share the playground-run-button testid; this method
    // returns the live "run" or "re-run" variant.
    return this.page
      .getByTestId('playground-run-button')
      .and(this.page.locator('[data-mode="run"], [data-mode="re-run"]'));
  }

  /**
   * The Playground's own page scroller. Row virtualization measures the table's offset
   * inside this element, so scrolling for virtualization assertions must drive it rather
   * than the window.
   */
  scrollContainer(): Locator {
    return this.page.getByTestId('playground-scroll-container');
  }

  /**
   * The output grid is two side-by-side `StickyScrollTable`s — dataset variables on the
   * left, prompt outputs on the right — each split into a sticky header half and a
   * scrollable body half. Both bodies render the same rows, so virtualization assertions
   * must name one surface rather than querying the grid as a whole.
   */
  variablesPanel(half: 'header' | 'body'): Locator {
    return this.page.getByTestId(`playground-variables-table-${half}`);
  }

  outputsPanel(half: 'header' | 'body'): Locator {
    return this.page.getByTestId(`playground-outputs-table-${half}`);
  }

  /** Scroll the Playground page body to a ratio of its scrollable height (0 = top, 1 = bottom). */
  async scrollResultsTo(ratio: number): Promise<void> {
    return test.step(`scroll results to ${ratio} of the page height`, async () => {
      await this.scrollContainer().evaluate((el, r) => {
        el.scrollTop = (el.scrollHeight - el.clientHeight) * r;
      }, ratio);
      await this.settle();
    });
  }

  /**
   * Row ids currently mounted in the outputs body. The grid does not set `getRowId`, so
   * these are TanStack's positional ids within the page, not dataset item ids — enough to
   * tell one mounted window from another, which is all the virtualization assertions need.
   * Callers should not assume a dataset ordering: the grid renders items newest-first.
   */
  async mountedRowIds(): Promise<string[]> {
    return test.step('read mounted row ids', async () => {
      return this.outputsPanel('body')
        .locator('tbody:not(.comet-table-body-loading-overlay) tr[data-row-id]')
        .evaluateAll((rows) =>
          rows.map((r) => r.getAttribute('data-row-id')).filter((v): v is string => Boolean(v)),
        );
    });
  }

  /**
   * Whether a gap sits between the top of the scroller's viewport and the first mounted row,
   * once the grid itself has been scrolled past. That is what a stale table offset looks
   * like: the virtual window is positioned from the wrong origin, so the rows it renders
   * land below where the scroll position says they should.
   */
  async hasBlankBandAboveRows(): Promise<boolean> {
    return test.step('check for a blank band above the mounted rows', async () => {
      const viewportTop = await this.scrollContainer().evaluate(
        (el) => el.getBoundingClientRect().top,
      );

      return this.outputsPanel('body').evaluate((body, top) => {
        const wrapper = body.querySelector('[data-table-wrapper]');
        const firstRow = body.querySelector('tbody tr[data-row-id]');
        if (!(wrapper instanceof HTMLElement) || !(firstRow instanceof HTMLElement)) return false;

        // Only meaningful once the grid's own top has scrolled above the viewport.
        if (wrapper.getBoundingClientRect().top >= top) return false;

        return firstRow.getBoundingClientRect().top > top + 1;
      }, viewportTop);
    });
  }

  /**
   * Drive a horizontal scroll on one panel's body half and report what it actually reached.
   * Returns the achieved `scrollLeft`, so a caller can fail loudly when the panel is too
   * narrow to overflow instead of silently comparing two zeroes.
   */
  async scrollPanelHorizontallyTo(
    panel: 'variables' | 'outputs',
    offset: number,
  ): Promise<number> {
    return test.step(`scroll the ${panel} panel horizontally to ${offset}px`, async () => {
      const body = panel === 'variables' ? this.variablesPanel('body') : this.outputsPanel('body');
      const reached = await body.evaluate((el, x) => {
        el.scrollLeft = x;
        return el.scrollLeft;
      }, offset);
      await this.settle();
      return reached;
    });
  }

  async panelBodyScrollLeft(panel: 'variables' | 'outputs'): Promise<number> {
    return test.step(`read the ${panel} panel body scroll offset`, async () => {
      const body = panel === 'variables' ? this.variablesPanel('body') : this.outputsPanel('body');
      return body.evaluate((el) => el.scrollLeft);
    });
  }

  // Compared by position, not scrollLeft: with scroll-driven animations the header
  // never scrolls, it is translated. Cells pair up by column: `data-header-id` is the
  // column id and `data-cell-id` is `<rowId>_<columnId>`.
  async panelColumnDrift(panel: 'variables' | 'outputs'): Promise<number> {
    return test.step(`measure ${panel} panel header/body column drift`, async () => {
      const half = (h: 'header' | 'body') =>
        panel === 'variables' ? this.variablesPanel(h) : this.outputsPanel(h);
      const headerCells = await half('header')
        .locator('th[data-header-id]')
        .evaluateAll((cells) =>
          cells.map((c) => [c.getAttribute('data-header-id') ?? '', c.getBoundingClientRect().left] as const),
        );
      const firstRow = half('body').locator('tr[data-row-id]').first();
      const cellIdPrefix = `${await firstRow.getAttribute('data-row-id')}_`;
      const bodyLefts = await firstRow.locator('td[data-cell-id]').evaluateAll(
        (cells, prefix) =>
          cells.map((c) => {
            const columnId = (c.getAttribute('data-cell-id') ?? '').slice(prefix.length);
            return [columnId, c.getBoundingClientRect().left] as const;
          }),
        cellIdPrefix,
      );
      const columnIds = (cells: ReadonlyArray<readonly [string, number]>) =>
        cells.map(([columnId]) => columnId).sort();
      const headerLefts = new Map(headerCells);

      expect(bodyLefts.length).toBeGreaterThan(0);
      expect(columnIds(bodyLefts)).toEqual(columnIds(headerCells));
      return Math.max(
        ...bodyLefts.map(([columnId, left]) => Math.abs(left - (headerLefts.get(columnId) ?? Number.NaN))),
      );
    });
  }

  async wheelOverPanelHeader(panel: 'variables' | 'outputs', deltaX: number): Promise<void> {
    return test.step(`wheel ${deltaX}px sideways over the ${panel} panel header`, async () => {
      const header = panel === 'variables' ? this.variablesPanel('header') : this.outputsPanel('header');
      await header.hover();
      await this.page.mouse.wheel(deltaX, 0);
      await this.settle();
    });
  }

  /**
   * Choose a "rows per page" value from the results pagination, then wait for the
   * replacement body. Changing the size refetches, and `mountedRowIds()` deliberately
   * ignores the loading tbody, so returning early would let a caller assert against an
   * empty or stale window.
   */
  async setPageSize(size: number): Promise<void> {
    return test.step(`set page size to ${size}`, async () => {
      await this.pageSizeTrigger().click();
      await this.page.getByRole('menuitemcheckbox', { name: String(size), exact: true }).click();

      await expect(this.pageSizeTrigger()).toHaveText(String(size));
      await expect(
        this.outputsPanel('body').locator(
          'tbody:not(.comet-table-body-loading-overlay) tr[data-row-id]',
        ),
      ).not.toHaveCount(0);
      await this.settle();
    });
  }

  /** The current "rows per page" value shown by the pagination trigger. */
  async pageSize(): Promise<number> {
    return test.step('read the current page size', async () => {
      return Number((await this.pageSizeTrigger().innerText()).trim());
    });
  }

  /**
   * Scrollable height of the page scroller. Under virtualization this tracks the row count
   * the virtualizer is sizing for, so it moves when the page size changes even though the
   * mounted window stays the same size.
   */
  async resultsScrollHeight(): Promise<number> {
    return test.step('read the results scroll height', async () => {
      return this.scrollContainer().evaluate((el) => el.scrollHeight);
    });
  }

  private pageSizeTrigger(): Locator {
    return this.resultsTable()
      .locator('..')
      .getByRole('button', { name: /^(10|50|100|200|500|1000)$/ });
  }

  /** Two frames: one for the scroll event to dispatch, one for the virtualizer to re-render. */
  private async settle(): Promise<void> {
    await this.page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
    );
  }

  private resultsTable(): Locator {
    return this.page.getByTestId('playground-results-table');
  }

  // ── dataset-grid output cells ───────────────────────────────────────────

  /**
   * The failure tag a dataset-grid output cell renders when its run failed.
   *
   * `playground-output-error` is the FE's own stability contract on that tag,
   * and it is rendered INSTEAD of the cell's markdown output — the cell body
   * short-circuits to `null` whenever an error is set — so counting these is
   * how a spec distinguishes "the failure was rendered as a failure" from "the
   * failure was rendered as the model's answer".
   */
  outputErrorTags(): Locator {
    return this.outputCells().getByTestId('playground-output-error');
  }

  /**
   * Markdown output blocks inside the dataset-grid output cells.
   *
   * `MarkdownPreview` stamps `comet-markdown` on both branches it can take —
   * the parsed one and the plain-text fallback — so this counts any rendered
   * answer whether or not the text happened to parse as markdown. It carries
   * no testid, and adding one would not help: these specs run against a
   * deployed Opik, where an attribute added alongside them would not exist in
   * the version under test.
   */
  outputMarkdownBlocks(): Locator {
    return this.outputCells().locator('.comet-markdown');
  }

  /**
   * Hover a failure tag and read the tooltip it raises.
   *
   * The tag truncates its message with CSS, so the tooltip is where the whole
   * message is meant to be legible. Radix portals the content out of the cell,
   * so the tooltip is looked up at page scope by role rather than through the
   * tag. `TooltipWrapper` opens on a timer after pointer-enter, so the wait is
   * on the tooltip appearing rather than on a fixed delay.
   */
  async outputErrorTooltipText(index = 0): Promise<string> {
    return test.step(`hover failure tag ${index} and read its tooltip`, async () => {
      await this.outputErrorTags().nth(index).hover();
      const tooltip = this.page.getByRole('tooltip');
      await expect(tooltip.first()).toBeVisible({ timeout: 10_000 });
      return (await tooltip.first().innerText()).trim();
    });
  }

  /**
   * Output cells of the experiment-results table — one per dataset row, per
   * variant. The table is the shared `DataTable` split into sticky-header and
   * scrollable-body halves, so body cells carry `data-cell-id="<rowId>_<colId>"`
   * and the variant columns are `output-<promptId>`.
   */
  private outputCells(): Locator {
    // The `:not(.comet-table-body-loading-overlay)` matters: switching sources keeps the
    // previous dataset's rows on screen (`keepPreviousData`) while the new items load, and
    // those rows are idle, so counting them would report ready for the wrong dataset.
    return this.resultsTable().locator(
      'tbody:not(.comet-table-body-loading-overlay) tr[data-row-id] td[data-cell-id*="_output-"]',
    );
  }

  /** Output cells whose row has not been run yet. */
  private idleOutputCells(): Locator {
    return this.outputCells().filter({ hasText: IDLE_CELL_TEXT });
  }

  private variantCard(index: number): Locator {
    return this.page.locator(
      `[data-testid="playground-variant-card"][data-variant-index="${index}"]`,
    );
  }

  private variantMessage(index: number, role: 'system' | 'user'): Locator {
    return this.variantCard(index).locator(
      `[data-testid="playground-message-row"][data-role="${role}"]`,
    );
  }

  private modelPicker(index: number): Locator {
    return this.page.getByTestId('select-a-llm-model').nth(index);
  }

  private async setModelForVariant(index: number, modelDisplayName: string): Promise<void> {
    // The choke point: selectModel, configureVariant and
    // runSimplePromptAndAwaitResponse all land here, so guarding the public
    // wrapper alone would leave the two paths the daily specs actually use
    // unchecked.
    assertAllowedModelDisplayName(modelDisplayName);
    const listbox = this.page.getByRole('listbox');
    // The trigger occasionally swallows the first click as a hover (surfacing a
    // tooltip instead of opening the popover), so retry the click until the
    // listbox actually opens rather than firing once and waiting.
    await expect(async () => {
      await this.modelPicker(index).click();
      await expect(listbox).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });

    // The option list remounts when /llm/models or /provider-keys resolves, and
    // the suite configures a provider immediately before opening the Playground —
    // so the dropdown routinely opens inside that refetch window and options are
    // detached mid-click. Re-filter and re-click until the popover actually
    // closes, which is the only reliable signal the selection registered.
    await expect(async () => {
      await listbox.getByPlaceholder('Search model').fill(modelDisplayName);
      const option = listbox.getByRole('option', { name: modelDisplayName, exact: true });
      await expect(option.first()).toBeVisible({ timeout: 2_000 });
      await option.first().click({ timeout: 2_000 });
      await expect(listbox).toBeHidden({ timeout: 2_000 });
    }).toPass({ timeout: 30_000 });
  }

  private async fillMessageBody(messageRow: Locator, text: string): Promise<void> {
    // CodeMirror editor: contenteditable div under `.cm-content`. Playwright's
    // fill() on the role=textbox works for simple strings; pressSequentially is
    // safer for multi-line / templated input.
    const editor = messageRow.locator('.cm-content').first();
    await editor.click();
    await editor.fill(text);
  }
}
