import type { Page, Locator } from '@playwright/test';
import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TraceLogsSidebarPage } from './trace-logs-sidebar.page';
import { PromptLibraryMenu } from './prompt-library-menu';
import { assertAllowedModelDisplayName } from '@e2e/core/llm-model-policy';

export type OptimizerName = 'GEPA optimizer' | 'Hierarchical Reflective';

/** Escape a literal so it can be embedded in a RegExp. Trial labels are plain
 *  today ("Baseline", "Trial #3"), but a POM helper that takes arbitrary text
 *  should not change meaning if that text ever grows a metacharacter. */
const escapeForRegExp = (literal: string): string =>
  literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export interface StudioRunConfig {
  /** Name of a dataset already associated with the project (shown in the picker). */
  datasetName: string;
  /** User-message prompt content; keep the dataset variable, e.g. `{{text}}`. */
  prompt: string;
  /**
   * System-message content — the instruction the optimizer rewrites. Required
   * because the form seeds a system card and rejects submit while it is empty;
   * see `setSystemPrompt`.
   */
  systemPrompt: string;
  /** Model display name as shown in the picker (e.g. "Claude Haiku 4.5"). */
  modelDisplayName: string;
  /** Equals-metric reference key — the dataset field holding the gold label. */
  referenceKey: string;
  /** Optional optimizer; defaults to the form's GEPA. */
  optimizer?: OptimizerName;
}

/**
 * Drives the v2 Optimization Studio: the new-run form
 * (`/projects/{id}/optimizations/new`) and the read side of the run-detail page
 * (`/projects/{id}/optimizations/{optimizationId}`), including the Trials tab
 * and the Best-trial-configuration panel.
 *
 * Only the shared LLM message editor carries data-testids; everything else is
 * addressed by role/label/text because this POM must run against the deployed
 * image as it renders, without adding product testids. Selectors are kept
 * tolerant of version drift where the UI has renamed things (e.g. the dataset
 * picker is "Select (an) item source" on newer builds, "Select a dataset" on
 * 2.1.x).
 */
export class OptimizationStudioPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  async gotoNew(): Promise<void> {
    return test.step('Open the Optimization Studio new-run panel', async () => {
      const env = loadEnvConfig();
      // The new-run flow is a side panel over the runs list
      // (`/optimizations?new=true`). `/optimizations/new` is kept as a redirect
      // to it, so navigating the legacy path also covers that back-compat route.
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/optimizations/new`,
      );
      await this.newRunPanel().waitFor();
    });
  }

  /** Assert the form's core sections render with the expected defaults. */
  async assertFormRenders(opts: { optimizer?: string; metric?: string } = {}): Promise<void> {
    return test.step('Assert the studio form renders its sections', async () => {
      // A new run seeds two message cards — instructions in the system message
      // (the only role the run makes optimizable) and template variables in the
      // user message. Assert both, so a regression back to a single seeded card
      // fails here rather than surfacing as a stuck submit later.
      await expect(this.promptEditor('system')).toBeVisible();
      await expect(this.promptEditor('user')).toBeVisible();
      await expect(this.modelCombobox()).toBeVisible();
      await expect(this.datasetPickerButton()).toBeVisible();
      await expect(
        this.page.locator('[role="combobox"]', { hasText: opts.optimizer ?? 'GEPA optimizer' }),
      ).toBeVisible();
      await expect(this.page.getByRole('combobox', { name: 'Metric' })).toContainText(
        opts.metric ?? 'Equals',
      );
      await expect(this.optimizeButton()).toBeDisabled();
    });
  }

  async selectDataset(name: string): Promise<void> {
    return test.step(`Select dataset "${name}"`, async () => {
      await this.datasetPickerButton().click();
      const dialog = this.page.getByRole('dialog');
      // Scope to the dialog: the run panel behind it also renders a
      // `search-input`, so an unscoped testid matches two elements.
      await dialog.getByTestId('search-input').fill(name);
      await dialog.getByText(name, { exact: true }).click();
      await expect(this.page.getByRole('button', { name })).toBeVisible();
    });
  }

  async setUserPrompt(content: string): Promise<void> {
    return test.step('Type the user-message prompt', async () => {
      await this.fillMessage('user', content);
    });
  }

  /**
   * Fill the system message. The form seeds an empty system card alongside the
   * user one and its schema requires every message to be non-empty, so a run
   * that leaves this blank never submits — the Optimize button goes enabled
   * (its disabled gate doesn't cover message content) but validation rejects
   * the submit, so the panel simply stays open.
   */
  async setSystemPrompt(content: string): Promise<void> {
    return test.step('Type the system-message prompt', async () => {
      await this.fillMessage('system', content);
    });
  }

  private async fillMessage(role: 'system' | 'user', content: string): Promise<void> {
    const editor = this.promptEditor(role);
    await editor.click();
    await editor.fill(content);
    await expect(editor).toContainText(content);
  }

  // ── the composer's shared message-row prompt library ────────────────────
  //
  // The new-run composer is the ONLY surface outside the Playground that mounts
  // `LLMPromptMessages` with `hidePromptActions` false, so it is the only other
  // place the per-row Load/Save strip — and the dirty check opik#8759 moved into
  // `useMessageTextPrompt` — renders at all. (`PromptTemplateEditor`,
  // `NewBlueprintFieldEditor`, `AgentRunnerPromptCard` and `BlueprintValuePrompt`
  // all pass `hidePromptActions`, which defaults to true.) Everything in this
  // group is scoped to `newRunPanel()` for that reason: the identical testids
  // exist on the Playground's own rows.

  /** One of the composer's message rows, by the role it carries. */
  composerMessageRow(role: 'system' | 'user'): Locator {
    return this.newRunPanel().locator(
      `[data-testid="playground-message-row"][data-role="${role}"]`,
    );
  }

  /**
   * The text one composer row currently holds.
   *
   * Read from the CodeMirror content node with its placeholder stripped, the
   * same way `PlaygroundPage.messageBodies` does: an empty editor renders "Type
   * your message" as a child element, which `innerText` would hand back as the
   * row's body.
   */
  async composerMessageBody(role: 'system' | 'user'): Promise<string> {
    return test.step(`Read the composer's ${role} message body`, async () => {
      return this.promptEditor(role)
        .first()
        .evaluate((editor) => {
          const clone = editor.cloneNode(true) as HTMLElement;
          clone.querySelectorAll('.cm-placeholder').forEach((node) => node.remove());
          return (clone.textContent ?? '').trim();
        });
    });
  }

  /**
   * REPLACE the text of one composer row, and settle once it really holds the
   * new text.
   *
   * Select-all then type, rather than `fillMessage`'s `fill()`, and the
   * difference is not cosmetic: `fill()` on a CodeMirror editor that already
   * holds text sometimes lands as an insertion at the cursor instead of a
   * replacement — measured here on staging, where one run of three came back
   * holding the old body followed by the new one. Replacing a selection is the
   * one gesture that is a replacement whatever the editor makes of synthetic
   * input events. `fillMessage` is left alone because its callers all type into
   * an EMPTY row, where the two are the same thing.
   *
   * Resolving on the body rather than on the keystrokes is what makes the dirty
   * check assertable: `hasUnsavedChanges` is recomputed from the message's
   * content, so a read taken before the edit has propagated reports the state
   * before the edit.
   */
  async editComposerMessage(role: 'system' | 'user', content: string): Promise<void> {
    return test.step(`Edit the composer's ${role} message`, async () => {
      const editor = this.promptEditor(role).first();
      await editor.click();
      await editor.press('ControlOrMeta+A');
      await this.page.keyboard.type(content);
      await expect
        .poll(async () => this.composerMessageBody(role), { timeout: 15_000 })
        .toBe(content);
    });
  }

  /**
   * Load one named version of a text prompt into a composer row, through the
   * row's own library door.
   *
   * The popover is the shared `PromptLibraryMenu`, driven from
   * `pom/prompt-library-menu.ts` — the same driver `PlaygroundPage` uses, so
   * the hover/retry workarounds the Radix hovercard needs live in one place.
   * The row is hovered first because the strip holding its Load button is
   * `invisible group-hover:visible`.
   *
   * No type chooser is involved: only `PlaygroundPrompt.tsx` passes
   * `chooseTemplateStructure`, so this menu opens straight on the list.
   */
  async loadTextPromptVersionIntoComposer(
    role: 'system' | 'user',
    promptName: string,
    versionLabel: string,
  ): Promise<void> {
    return test.step(
      `Load text prompt "${promptName}" ${versionLabel} into the composer's ${role} message`,
      async () => {
        const row = this.composerMessageRow(role);
        await row.hover();
        await row.getByTestId('load-text-prompt-button').click();

        const menu = new PromptLibraryMenu(this.page);
        await menu.waitForOpen();
        await menu.selectVersion(promptName, versionLabel);
      },
    );
  }

  /**
   * The chip `LoadedPromptDisplay` puts on a row once a library prompt is
   * linked to it, addressed by that prompt's name.
   */
  composerLoadedPromptChip(role: 'system' | 'user', promptName: string): Locator {
    return this.composerMessageRow(role).getByText(promptName, { exact: true });
  }

  /**
   * Settle on the row really showing that prompt's chip.
   *
   * The hover is re-applied on every attempt, for the reason
   * `PlaygroundPage.waitForLoadedPromptVersionInMessage` records: the strip
   * holding the chip is `invisible group-hover:visible`, so it is on screen only
   * while the cursor is over the row — and applying a loaded prompt REBUILDS
   * the row as fresh DOM. A single `hover()` is one synthetic mouse move, so
   * once the node under the cursor has been replaced nothing re-fires it and a
   * plain `toBeVisible()` waits out its whole timeout watching an element it
   * can never un-hide.
   */
  async expectComposerPromptChip(role: 'system' | 'user', promptName: string): Promise<void> {
    return test.step(`Assert the composer's ${role} row is linked to "${promptName}"`, async () => {
      const chip = this.composerLoadedPromptChip(role, promptName);
      await expect
        .poll(
          async () => {
            await this.composerMessageRow(role).hover();
            return chip.isVisible();
          },
          {
            timeout: 30_000,
            intervals: [250, 500, 1_000],
            message: `the ${role} row never showed a chip for "${promptName}"`,
          },
        )
        .toBe(true);
    });
  }

  /**
   * The amber dot the chip grows when the row no longer matches the version it
   * was loaded from.
   *
   * The same class-scoped selector, and the same last-resort reasoning, as
   * `PlaygroundPage.unsavedChangesDot`: the dot is a bare decorative `<span>`
   * with no text, no role and no testid, and these specs run against a deployed
   * Opik. `composerSaveTooltipText` asserts the same `saveWarning` flag through
   * an independent text handle, so a renamed utility class surfaces as the two
   * disagreeing rather than as a silent pass.
   */
  composerUnsavedChangesDot(role: 'system' | 'user'): Locator {
    return this.composerMessageRow(role).locator('span.rounded-full.bg-warning');
  }

  /**
   * Hover a composer row's save-to-library button and read its tooltip.
   *
   * The user-readable half of the same flag the dot renders: "Save as text
   * prompt" while the row still matches its loaded version, "This prompt
   * version hasn't been saved" once it does not. Radix portals the content out
   * of the row, so it is read at page scope.
   */
  async composerSaveTooltipText(role: 'system' | 'user'): Promise<string> {
    return test.step(`Read the composer's ${role} row save tooltip`, async () => {
      const row = this.composerMessageRow(role);
      await row.hover();
      const save = row.getByTestId('save-text-prompt-button');
      await expect(save, 'the row offers a save-to-library button').toHaveCount(1);
      await save.hover();
      const tooltip = this.page.getByRole('tooltip');
      await expect(tooltip.first()).toBeVisible({ timeout: 10_000 });
      return (await tooltip.first().innerText()).trim();
    });
  }

  async selectModel(displayName: string): Promise<void> {
    assertAllowedModelDisplayName(displayName);
    return test.step(`Select model "${displayName}"`, async () => {
      const search = this.page.getByRole('textbox', { name: 'Search model' });
      // The trigger occasionally swallows the first click as a hover, so retry
      // until the search box (i.e. the open popover) actually appears.
      await expect(async () => {
        await this.modelCombobox().click();
        await expect(search).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 15_000 });

      // The option list remounts when the model/provider-key queries resolve, so
      // an option can detach between resolving and being clicked. Re-filter and
      // re-click until the combobox reflects the selection.
      await expect(async () => {
        await search.fill(displayName);
        const option = this.page.getByRole('option', { name: displayName });
        await expect(option.first()).toBeVisible({ timeout: 2_000 });
        await option.first().click({ timeout: 2_000 });
        await expect(this.modelCombobox()).toContainText(displayName, { timeout: 2_000 });
      }).toPass({ timeout: 30_000 });
    });
  }

  /**
   * As {@link selectModel}, but reaching the picker through its own testid
   * rather than through `modelCombobox`'s text filter.
   *
   * `modelCombobox()` identifies the picker by the provider name it renders —
   * Anthropic, OpenAI, Gemini, openrouter, free — which is the only way to tell
   * it apart from the form's other comboboxes once a model is chosen. A CUSTOM
   * provider renders its own user-defined name instead, so that filter matches
   * nothing and `selectModel` cannot drive a model the test itself seeded. That
   * matters for any spec that wants a model nothing can bill: the custom
   * provider's base URL refuses every connection, which is the only way to put
   * a selectable model on the form without exposing the run to spend.
   *
   * `modelPickerTrigger()` is anchored on the `select-a-llm-model` testid, so
   * it holds whatever the picker shows. The retry shape is `selectModel`'s, and
   * for the same reasons: the trigger can swallow the first click as a hover,
   * and the option list remounts when the provider-key and registry queries
   * resolve.
   *
   * The model-policy guard is deliberately NOT bypassed — a name the run did
   * not register as unbilled still has to be on the allowlist.
   */
  async selectModelByName(displayName: string): Promise<void> {
    assertAllowedModelDisplayName(displayName);
    return test.step(`Select model "${displayName}" through the picker trigger`, async () => {
      const search = this.page.getByRole('textbox', { name: 'Search model' });
      await expect(async () => {
        await this.modelPickerTrigger().click();
        await expect(search).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 15_000 });

      await expect(async () => {
        await search.fill(displayName);
        const option = this.page.getByRole('option', { name: displayName, exact: true });
        await expect(option).toHaveCount(1, { timeout: 2_000 });
        await option.click({ timeout: 2_000 });
        await expect(this.modelPickerTrigger()).toHaveText(displayName, { timeout: 2_000 });
      }).toPass({ timeout: 30_000 });
    });
  }

  /**
   * Type a value into one slider's numeric input and commit it.
   *
   * `SliderInputControl` commits on blur or on Enter/Tab/Escape, not on change,
   * and inside a Radix menu Tab moves no focus while Escape unmounts the menu
   * without a blur — so Enter is the only key that reliably lands the typed
   * value. Clamped by the control itself: a value outside [min, max] is
   * replaced with the bound, which is why the caller is handed back what the
   * input ENDED UP holding rather than what it asked for. A spec must assert
   * against the returned value, or a silently clamped nudge would make it look
   * as though the form had sent something it never held.
   *
   * `id` is the control id `PromptModelConfigs` passes (`temperature`,
   * `maxCompletionTokens`, `topP`, …); the input's testid is `<id>-input`.
   * Leaves the panel OPEN, so a caller can set several and read them back.
   */
  async setModelParameter(id: string, value: number): Promise<string> {
    return test.step(`Set the "${id}" model parameter to ${value}`, async () => {
      const panel = this.modelParametersPanel();
      if (!(await panel.isVisible().catch(() => false))) {
        await this.modelParametersTrigger().click();
        await panel.waitFor({ state: 'visible' });
      }
      const input = panel.getByTestId(`${id}-input`);
      await expect(
        input,
        `the parameters panel mounts exactly one "${id}" control`,
      ).toHaveCount(1);
      await input.fill(String(value));
      await input.press('Enter');
      return input.inputValue();
    });
  }

  /**
   * Replace the Extra body parameters editor's contents with `json`.
   *
   * This is the control behind `custom_parameters` — the one model parameter
   * whose VALUE the backend inspects, since
   * `StudioModelParameters.DESTINATION_KEYS` refuses an inner key that could
   * pick a host, a credential or a fallback model. A CodeMirror
   * `contenteditable`, so it is filled the way the message editors are, then
   * blurred: `useJsonInput` commits on blur, and a value left uncommitted would
   * never reach the payload.
   *
   * Leaves the panel open. Returns nothing: what the editor accepted is only
   * observable in the submitted request, which is where the caller asserts it.
   */
  async setExtraBodyParameters(json: string): Promise<void> {
    return test.step('Fill the Extra body parameters editor', async () => {
      const panel = this.modelParametersPanel();
      if (!(await panel.isVisible().catch(() => false))) {
        await this.modelParametersTrigger().click();
        await panel.waitFor({ state: 'visible' });
      }
      const editor = panel.locator('#custom_parameters .cm-content').first();
      await editor.click();
      await editor.fill(json);
      // Commit by blurring onto the panel's own label rather than pressing
      // Escape, which unmounts the Radix menu without a blur and so drops the
      // typed value — the same trap `SliderInputControl` documents.
      await panel.getByText('Extra body parameters', { exact: false }).first().click();
      await expect(
        panel.getByText('Invalid JSON', { exact: false }),
        'the editor accepted the JSON — an invalid value is never saved',
      ).toHaveCount(0);
    });
  }

  /** Close the model-parameters popover, if it is open. Safe to call blind. */
  async dismissModelParameters(): Promise<void> {
    return test.step('Close the model parameters popover', async () => {
      if (await this.modelParametersPanel().isVisible().catch(() => false)) {
        await this.closeModelParameters();
      }
    });
  }

  async selectOptimizer(optimizer: OptimizerName): Promise<void> {
    return test.step(`Select optimizer "${optimizer}"`, async () => {
      const combo = this.page
        .locator('[role="combobox"]')
        .filter({ hasText: /GEPA optimizer|Hierarchical Reflective/ });
      await combo.click();
      await this.page.getByRole('option', { name: optimizer }).click();
      await expect(combo).toContainText(optimizer);
    });
  }

  async setReferenceKey(key: string): Promise<void> {
    return test.step(`Set Equals reference key to "${key}"`, async () => {
      await this.page.locator('input#reference_key').fill(key);
    });
  }

  /** Click "Optimize prompt" and return the created optimization's id from the URL. */
  async startOptimization(): Promise<string> {
    return test.step('Start the optimization run', async () => {
      await expect(this.optimizeButton()).toBeEnabled();
      await this.optimizeButton().click();
      // The button's disabled gate doesn't cover message content, so an empty
      // message card lets the click through and validation silently rejects the
      // submit — the panel just stays open. Surface that as the actual cause
      // instead of an unexplained navigation timeout.
      try {
        await this.page.waitForURL(/\/optimizations\/[0-9a-f-]+$/);
      } catch (error) {
        const validationError = this.newRunPanel().getByText('Message is required').first();
        if (await validationError.isVisible().catch(() => false)) {
          throw new Error(
            'Submit was rejected: a message card is empty ("Message is required"). ' +
              'The form seeds system + user messages and requires content in both.',
          );
        }
        throw error;
      }
      const match = this.page.url().match(/\/optimizations\/([0-9a-f-]+)$/);
      if (!match) {
        throw new Error(`Could not extract optimization id from URL: ${this.page.url()}`);
      }
      return match[1];
    });
  }

  /** Configure and launch a run in one call; returns the optimization id. */
  async configureAndStart(config: StudioRunConfig): Promise<string> {
    return test.step('Configure and start an optimization run', async () => {
      await this.selectDataset(config.datasetName);
      await this.setSystemPrompt(config.systemPrompt);
      await this.setUserPrompt(config.prompt);
      await this.selectModel(config.modelDisplayName);
      if (config.optimizer && config.optimizer !== 'GEPA optimizer') {
        await this.selectOptimizer(config.optimizer);
      }
      await this.setReferenceKey(config.referenceKey);
      return this.startOptimization();
    });
  }

  /**
   * Open the new-run panel seeded from an existing run — the "rerun" flow.
   *
   * The panel is query-param driven off the runs list (`?new=true&rerun=<id>`),
   * not a route of its own, so this navigates the list and lets
   * `OptimizationsPage` mount the sidebar. Waiting on the model picker rather
   * than only on the panel is load-bearing: the seeded model resolves after the
   * provider-keys AND registry queries settle (`providersReady` in
   * NewRunSidebar), and until then the form holds no model at all — so a spec
   * that read the panel the moment it appeared would read a blank picker and a
   * missing replacement notice.
   */
  async gotoRerun(optimizationId: string): Promise<void> {
    return test.step(`Open the new-run panel as a rerun of ${optimizationId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/optimizations` +
          `?new=true&rerun=${optimizationId}`,
      );
      await this.newRunPanel().waitFor();
      await this.waitForModelResolved();
    });
  }

  /**
   * Open the new-run panel with nothing seeded — a blank run.
   *
   * `gotoNew()` reaches the same panel through the legacy `/optimizations/new`
   * redirect; this one goes at the query param directly, which is the shape a
   * rerun uses. A spec comparing a rerun against a blank form wants the two to
   * differ in the `rerun` param and nothing else.
   */
  async gotoNewBlank(): Promise<void> {
    return test.step('Open the new-run panel with nothing seeded', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/optimizations?new=true`,
      );
      await this.newRunPanel().waitFor();
      await this.waitForModelResolved();
    });
  }

  /**
   * Wait until the form has resolved a prompt model, and return its label.
   *
   * The placeholder is the signal: `PromptModelSelect` renders "Select an LLM
   * model" until a model is set, so a non-placeholder trigger means
   * `providersReady` has turned true and `convertOptimizationStudioToFormData`
   * has seeded a real one.
   */
  async waitForModelResolved(): Promise<string> {
    return test.step('Wait for the form to resolve a prompt model', async () => {
      const picker = this.modelPickerTrigger();
      await expect(
        picker,
        'the form resolves a prompt model once the provider keys and the registry answer',
      ).not.toHaveText('Select an LLM model');
      return ((await picker.textContent()) ?? '').trim();
    });
  }

  /**
   * The prompt model the form currently shows.
   *
   * In the compact picker this is the model OPTION'S LABEL — the same string
   * `NewRunSidebar` puts in the replacement notice as `replacementLabel` — so a
   * spec can hold the notice and the picker to each other exactly.
   */
  async readPromptModel(): Promise<string> {
    return test.step('Read the prompt model the form shows', async () => {
      return ((await this.modelPickerTrigger().textContent()) ?? '').trim();
    });
  }

  /**
   * The notice shown when the saved run's model cannot be served here.
   *
   * Text, not a testid: this panel carries none, and the notice is a plain
   * `<span>`. Anchored on the opening clause rather than the whole sentence so
   * the spec can assert the rest — which model replaced it, and whether its
   * settings were inherited — itself.
   */
  savedModelReplacementNotice(): Locator {
    return this.newRunPanel().getByText(/isn't available here/);
  }

  /**
   * The warning that the ALGORITHM model's provider has no key. Distinct copy
   * from the prompt model's ("this provider"), which is the pair a spec must
   * tell apart.
   */
  optimizerKeyWarning(): Locator {
    return this.newRunPanel().getByText(/API key for the algorithm model's provider/);
  }

  /** Is "Optimize prompt" offered as runnable? */
  async expectOptimizeEnabled(): Promise<void> {
    return test.step('Assert Optimize prompt is enabled', async () => {
      await expect(this.optimizeButton()).toBeEnabled();
    });
  }

  /**
   * Open the prompt model's parameters popover and read every slider it mounts,
   * by control id, then close it again.
   *
   * Returned as a map rather than asserted here because the useful assertion is
   * a COMPARISON — a rerun's panel against a blank form's on the same model —
   * and the set of mounted controls is itself part of what must match. Reading
   * only the two parameters a spec happens to care about would let a build that
   * dropped half the panel pass.
   *
   * `PromptModelConfigs` is the same component the playground mounts, so the
   * ids and the `-input` testids are the playground's.
   */
  async readModelParameters(): Promise<Record<string, string>> {
    return test.step('Read the prompt model’s parameters panel', async () => {
      await this.modelParametersTrigger().click();
      const panel = this.modelParametersPanel();
      await panel.waitFor({ state: 'visible' });

      const entries = await panel
        .locator('input[data-testid$="-input"]')
        .evaluateAll((els) =>
          els.map((e) => [
            (e.getAttribute('data-testid') ?? '').replace(/-input$/, ''),
            (e as HTMLInputElement).value,
          ]),
        );
      expect(
        entries.length,
        'the parameters panel mounts at least one slider — an empty read would make ' +
          'any comparison against it vacuous',
      ).toBeGreaterThan(0);

      await this.closeModelParameters();
      return Object.fromEntries(entries);
    });
  }

  /**
   * Close the model-parameters popover by toggling its own trigger.
   *
   * NOT with Escape, which is how `PlaygroundPage` closes the identical
   * dropdown: here the panel lives inside a `ResizableSidePanel`, and the
   * keypress bubbles past the closed dropdown to dismiss the whole new-run
   * sidebar — so every assertion after the read found an empty runs list
   * instead of the form. Clicking the trigger closes only the menu it opened.
   */
  private async closeModelParameters(): Promise<void> {
    const panel = this.modelParametersPanel();
    // `force`, because a modal Radix dropdown sets `pointer-events: none` on
    // everything behind its content — so the trigger fails Playwright's
    // actionability check even though a pointerdown at that point is exactly
    // what dismisses the menu.
    await expect(async () => {
      await this.modelParametersTrigger().click({ force: true, timeout: 2_000 });
      await expect(panel).toBeHidden({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });
    await expect(
      this.newRunPanel(),
      'closing the parameters popover must not dismiss the new-run sidebar',
    ).toBeVisible();
  }

  /**
   * Open the project's Optimization runs list — the page the run rows and
   * their cost columns render on.
   */
  async gotoList(): Promise<void> {
    return test.step('Open the Optimization runs list', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/optimizations`,
      );
    });
  }

  /**
   * One run's row in the runs list, addressed by its id.
   *
   * `DataTable` stamps `data-row-id="<optimizationId>"`, which survives a
   * rename, a re-sort and a column reorder alike — unlike matching the Name
   * cell, which would also have to survive one run's name being a prefix of
   * another's.
   */
  runRow(optimizationId: string): Locator {
    return this.runsTable().locator(`tbody tr[data-row-id="${optimizationId}"]`);
  }

  /**
   * The "Optimization cost" cell of a run's row, as rendered.
   *
   * Addressed by COLUMN, not by position: the column's id is `opt_cost` (kept
   * from before the header was renamed, so saved column layouts survive), and
   * `DataTable` stamps every body cell `data-cell-id="<rowId>_<columnId>"`.
   * A positional selector would read a different column the moment a user
   * reorders them, which this table's Columns picker allows and persists.
   */
  optimizationCostCell(optimizationId: string): Locator {
    return this.runRow(optimizationId).locator('td[data-cell-id$="_opt_cost"]');
  }

  /**
   * Wait until the runs list has rendered exactly one row for a given run.
   *
   * `toHaveCount(1)` rather than `.first()`: an ambiguous match must fail
   * loudly instead of silently reading some other run's numbers.
   */
  async waitForRunRow(optimizationId: string, timeoutMs = 30_000): Promise<void> {
    return test.step(`wait for run ${optimizationId} to be listed`, async () => {
      await expect(
        this.runRow(optimizationId),
        'exactly one row for the seeded run',
      ).toHaveCount(1, { timeout: timeoutMs });
    });
  }

  /**
   * The Optimization runs empty state.
   *
   * Two components render it — `OptimizationsEmptyState` when the workspace
   * may use the Studio, `PageEmptyState` when it may not — and both render the
   * same string as an `h2`, so the heading role matches whichever the
   * deployment shows. Matched as a heading rather than as text because the
   * illustration's `alt` carries the same words.
   */
  emptyStateHeading(): Locator {
    return this.page.getByRole('heading', { name: 'No optimization runs yet' });
  }

  async gotoDetail(optimizationId: string): Promise<void> {
    return test.step('Open the optimization detail page', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/optimizations/${optimizationId}`,
      );
    });
  }

  /** Wait for the detail header status tag to read the given status (case-insensitive). */
  async expectStatus(
    status: 'completed' | 'running' | 'error',
    opts: { timeout?: number } = {},
  ): Promise<void> {
    return test.step(`Expect detail-page status "${status}"`, async () => {
      await expect(
        this.page.getByText(new RegExp(`^${status}$`, 'i')).first(),
        `optimization detail should show status "${status}"`,
      ).toBeVisible({ timeout: opts.timeout ?? 15_000 });
    });
  }

  /** Open the Trials tab and wait for its table to render. */
  async openTrialsTab(): Promise<void> {
    return test.step('Open the Trials tab', async () => {
      await this.page.getByRole('tab', { name: 'Trials' }).click();
      await this.trialsTable().waitFor({ state: 'visible' });
    });
  }

  async trialRowCount(): Promise<number> {
    return test.step('Count trial rows', async () => {
      return this.trialsTable().locator('tbody tr').count();
    });
  }

  /** Assert at least one trial row carries the "Best" status tag. */
  async expectBestTrial(): Promise<void> {
    return test.step('Expect a Best trial row', async () => {
      await expect(
        this.trialsTable().getByText('Best', { exact: true }).first(),
      ).toBeVisible();
    });
  }

  /**
   * Open a trial row by the label its "Trial" column renders — "Baseline" for
   * the run's step-0 candidate, "Trial #N" for the numbered ones. Addressed by
   * label rather than row index because the table's sort order is a product
   * decision this POM should not encode.
   *
   * Returns the trial side panel's locator so callers can scope to it.
   */
  async openTrialByLabel(label: string): Promise<Locator> {
    return test.step(`Open trial "${label}"`, async () => {
      // Match the Trial cell EXACTLY, and identify that cell by COLUMN rather
      // than by position. Two failure modes to avoid:
      //   * a substring match over the row's text makes "Trial #1" also select
      //     "Trial #10", and collides with the label appearing in another cell;
      //   * a positional match (`td:first-child`) inspects the wrong cell once a
      //     user reorders columns — the Trial column is offered in the column
      //     selector and is not pinned, so it does move.
      // `DataTable` stamps each cell `data-cell-id="<rowId>_<columnId>"`, and the
      // Trial column's id is `COLUMN_NAME_ID` ("name"), so a `_name` suffix
      // match follows the column wherever it sits. `TrialNumberCell` renders the
      // label as that cell's full text, so the anchored match is exact.
      const row = this.trialsTable()
        .locator('tbody tr[data-row-id]')
        .filter({
          has: this.page.locator('td[data-cell-id$="_name"]').filter({
            hasText: new RegExp(`^\\s*${escapeForRegExp(label)}\\s*$`),
          }),
        });
      await expect(row, `exactly one trial row labelled "${label}"`).toHaveCount(1);
      await row.click();
      const panel = this.trialSidebar();
      await expect(panel, `trial side panel for "${label}"`).toBeVisible();
      return panel;
    });
  }

  /**
   * Click "Logs" in the open trial side panel and hand back the shared logs
   * overlay. It is a clickable `Tag`, not a button, so it is addressed by text
   * within the panel rather than by role.
   */
  async openTrialLogs(): Promise<TraceLogsSidebarPage> {
    return test.step('Open the trial\'s Logs overlay', async () => {
      await this.trialSidebar().getByText('Logs', { exact: true }).first().click();
      const overlay = new TraceLogsSidebarPage(this.page);
      await overlay.waitForReady();
      return overlay;
    });
  }

  /** The trial side panel. `ResizableSidePanel` renders its `panelId` as a
   *  data-testid, which is the one purpose-built hook on it. */
  private trialSidebar(): Locator {
    return this.page.locator('[data-testid="optimization-trial-sidebar"]');
  }

  /**
   * Assert the run's configuration shows the given algorithm + metric. These
   * render as pills in the detail page's header, which sits outside the tab
   * structure — so scope to `main`, not to the Overview tabpanel.
   */
  async expectBestTrialConfig(opts: { algorithm: string; metric: string }): Promise<void> {
    return test.step('Assert the run configuration pills', async () => {
      const header = this.page.locator('main');
      await expect(header).toContainText(opts.algorithm);
      await expect(header).toContainText(opts.metric);
    });
  }

  /**
   * The new-run side panel. `ResizableSidePanel` renders its `panelId` as a
   * data-testid, so this is the one purpose-built hook on the panel — the
   * header is a plain span ("New optimization run"), not a heading.
   */
  private newRunPanel(): Locator {
    return this.page.locator('[data-testid="new-optimization-run-sidebar"]');
  }

  /**
   * The message editor for a given role. The form seeds one card per message
   * (system + user), and each card renders its own `playground-message-editor`,
   * so an unscoped testid matches every editor on the page. Scope to the panel
   * and to the card's `data-role` — the row testid plus `data-role` is the
   * stable per-message hook, and the same pattern is used by the prompts POMs.
   */
  private promptEditor(role: 'system' | 'user'): Locator {
    return this.newRunPanel()
      .locator(`[data-testid="playground-message-row"][data-role="${role}"]`)
      .getByTestId('playground-message-editor')
      .locator('.cm-content');
  }

  private optimizeButton(): Locator {
    return this.page.getByRole('button', { name: 'Optimize prompt' });
  }

  /**
   * The dataset/source picker button. Newer builds label it "Select an item
   * source" (briefly "Select item source"); 2.1.x labels it "Select a
   * dataset". Match any, or the button that has become the selected dataset
   * name.
   */
  private datasetPickerButton(): Locator {
    return this.page.getByRole('button', { name: /Select (an )?item source|Select a dataset/ });
  }

  /**
   * The prompt model picker's trigger, as the element whose text IS the chosen
   * model's label.
   *
   * Anchored on the `SelectValue`'s own testid rather than on `modelCombobox`'s
   * text filter: that filter has to match a provider name or the placeholder,
   * so it cannot be used to READ what the picker currently shows without
   * assuming the answer. The ancestor button is the trigger Radix gives the
   * combobox role.
   */
  private modelPickerTrigger(): Locator {
    return this.newRunPanel().locator(
      'button[role="combobox"]:has(> [data-testid="select-a-llm-model"])',
    );
  }

  /**
   * The gear that opens the prompt model's parameters.
   *
   * Anchored to the model picker rather than addressed directly, for the same
   * reason as `PlaygroundPage.modelParametersTrigger`: the trigger carries no
   * testid and no accessible name (its tooltip is a Radix `TooltipContent`, not
   * an `aria-label`), and the panel holds other `aria-haspopup="menu"` buttons —
   * every message row's role selector is one. "The menu button immediately
   * after the model picker" is the one stable description available.
   *
   * Note it is absent, not disabled, when the selected model has nothing to set
   * (`PromptModelConfigs` returns null) — so a caller gets a loud "not found"
   * rather than a panel that silently reads empty.
   */
  private modelParametersTrigger(): Locator {
    // By its ICON rather than as the picker's adjacent sibling. The gear is no
    // longer adjacent to the combobox: `OptimizationsNewPromptSection` wraps the
    // picker in a `FormControl` and renders `PromptModelConfigs` as a sibling of
    // THAT, so `combobox + button[aria-haspopup="menu"]` matches nothing — which
    // presented as the gear being absent (measured on staging 2.2.116: one
    // combobox, three `aria-haspopup="menu"` buttons in the panel, and zero
    // adjacent matches).
    //
    // `svg.lucide-settings2` is the same handle `OnlineEvaluationPage`'s
    // `judgeModelParametersTrigger` uses for the identical component, and it is
    // what separates the gear from the panel's other menu buttons (every message
    // row's role selector is one).
    //
    // Scoped to the prompt model's own `FormItem` rather than to the panel: the
    // panel renders a SECOND Settings2 gear, `AlgorithmConfigs`, beside the
    // Algorithm picker, so panel-wide the icon is ambiguous (strict mode
    // violation, two matches, measured on a local 2.2.117 build). The prompt
    // gear is the one sharing a parent with the `PromptModelSelect` trigger —
    // `OptimizationsNewPromptSection` renders them as the two children of one
    // `FormItem`, and `select-a-llm-model` is a testid only that select emits.
    return this.newRunPanel()
      .locator('div:has(> button[role="combobox"]:has([data-testid="select-a-llm-model"]))')
      .locator('button:has(svg.lucide-settings2)');
  }

  /**
   * The open model-parameters popover. One is open at a time — it is a
   * `DropdownMenu`, and Radix unmounts a closed one's content.
   */
  private modelParametersPanel(): Locator {
    return this.page.getByRole('menu');
  }

  private modelCombobox(): Locator {
    // The model picker is the only combobox on the form that renders a provider
    // name; the others show "GEPA optimizer" / "Equals". With no provider key
    // configured it renders its "Select an LLM model" placeholder instead, so
    // match that too — the form must still be assertable in that state.
    return this.page
      .locator('[role="combobox"]')
      .filter({
        hasText:
          /Anthropic|OpenAI|Gemini|openrouter|free|gpt-|claude|GPT|Claude|Select an LLM model/i,
      });
  }

  private trialsTable(): Locator {
    return this.page.locator('main table');
  }

  /**
   * The runs list's table. Same anchor as `trialsTable` — the runs list has
   * one table in `main` — but named separately because they are different
   * surfaces and a future page change is free to move one without the other.
   */
  private runsTable(): Locator {
    return this.page.locator('main table');
  }
}
