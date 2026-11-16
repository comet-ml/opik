import { test, expect, type Page, type Locator } from '@playwright/test';

/** One trace-to-suite write, as `useAddTracesToDatasetMutation` sends it. */
export interface FromTracesRequest {
  traceIds: string[];
  /**
   * The `execution_policy` the request carried, or `null` when it carried none.
   *
   * The distinction is the subject of opik#9482: the dialog must send NOTHING
   * when the user left the suite's criteria alone, so the item inherits. A
   * policy equal to the suite's would store an override that merely looks like
   * inheritance today and stops tracking the suite the moment it is edited.
   */
  executionPolicy: { runs_per_item: number; pass_threshold: number } | null;
}

/**
 * The "Add to → Test suite" dialog opened from the Logs table
 * (`AddToTestSuiteDialog`).
 *
 * A separate class from `AddToDatasetDialogPage` even though both wrap
 * `DatasetPickerSection`: this one's subject is the Evaluation criteria section
 * that only it renders, and its submit button is named for the suite rather
 * than for an item count.
 */
export class AddToTestSuiteDialogPage {
  constructor(private readonly page: Page) {}

  /**
   * The dialog itself, filtered by its title.
   *
   * The Logs toolbar mounts three sibling dialogs from one dropdown — Test
   * suite, Dataset and Annotation queue. Only the open one is portalled, but
   * naming the title keeps a mis-click on the dropdown from silently driving
   * the wrong dialog, which is the same reason `AddToDatasetDialogPage` does it.
   */
  get root(): Locator {
    return this.page.getByRole('dialog').filter({ hasText: 'Add to test suite' });
  }

  /**
   * The suite picker's trigger, addressed by the name it currently shows.
   *
   * `LoadableSelectBox` renders an unlabelled button whose accessible name is
   * the selection (or the "Select a test suite" placeholder), so the name is
   * the only stable handle — there is no testid and no associated `<label>`.
   */
  private picker(name: string): Locator {
    return this.root.getByRole('button', { name, exact: false });
  }

  /**
   * Pick a suite by name, and wait for its criteria to have loaded.
   *
   * The wait is not cosmetic. `AddToTestSuiteDialog` renders a `<Loader>` in
   * place of `EvaluationCriteriaSection` while `useDatasetVersionsList` is
   * pending, and the policy inputs do not exist until it resolves — so a read
   * taken too early sees no inputs at all, which is indistinguishable from the
   * regression where the section renders with the wrong numbers.
   */
  async selectSuite(name: string): Promise<void> {
    return test.step(`Pick the test suite "${name}"`, async () => {
      const selected = this.picker(name);
      const placeholder = this.picker('Select a test suite');
      // The dialog may already hold the suite. `useAddToDatasetForm` restores
      // the last dataset used, so a second open in the same browser context can
      // arrive pre-selected — and then the "Select a test suite" placeholder
      // never exists to click, which is how this timed out under four workers
      // while passing in a single worker: whether the restore beats the click
      // is a race. Both states are legitimate, so the END state is what is
      // waited for and only the transition is conditional.
      await expect(
        selected.or(placeholder),
        'the suite picker has rendered, either empty or already holding a selection',
      ).toBeVisible();
      if (!(await selected.isVisible())) {
        await placeholder.click();
        await this.page.getByRole('option', { name, exact: false }).click();
      }
      await expect(selected, 'the picker shows the chosen suite').toBeVisible();
      await this.runsPerItemInput.waitFor({ state: 'visible' });
    });
  }

  /**
   * The "Runs for this item" input of `EvaluationCriteriaSection`.
   *
   * Reached through its `<Label>`'s text rather than by role or testid. The
   * section's two number inputs carry no id, no `name` and no testid, and their
   * labels are not associated with them (`<Label>` without `htmlFor`), so
   * `getByLabel` cannot see them and `getByRole('spinbutton')` alone would
   * match both. The label text is the only thing that tells them apart.
   *
   * A `data-testid` on each input would be the right fix and belongs in the
   * frontend — it is not added here because this spec ships on the QA branch,
   * which must not touch anything outside `tests_end_to_end/`. Hopping one
   * level from the label to its wrapping `div` is structural, but it is
   * structure keyed to a VISIBLE STRING rather than to a position among
   * siblings, so a reordered or re-skinned section does not break it.
   */
  get runsPerItemInput(): Locator {
    return this.criteriaInput('Runs for this item');
  }

  /** The "Pass threshold" input of the same section. */
  get passThresholdInput(): Locator {
    return this.criteriaInput('Pass threshold');
  }

  private criteriaInput(labelText: string): Locator {
    return this.root
      .getByText(labelText, { exact: true })
      .locator('..')
      .getByRole('spinbutton');
  }

  /**
   * Type a new "Runs for this item" value and let the field commit it.
   *
   * `fill` then `blur`: the inputs are controlled through a committing wrapper
   * (`onChange` holds a display value, `onBlur` settles it), and the threshold's
   * clamp is applied as part of that settle. Reading the threshold without
   * leaving the runs field would catch it mid-edit.
   */
  async setRunsPerItem(runs: number): Promise<void> {
    return test.step(`Set runs for this item to ${runs}`, async () => {
      await this.runsPerItemInput.fill(String(runs));
      await this.runsPerItemInput.blur();
    });
  }

  /** The current values of both inputs, as the numbers they show. */
  async readCriteria(): Promise<{ runsPerItem: string; passThreshold: string }> {
    return test.step('Read the pass criteria the dialog shows', async () => ({
      runsPerItem: await this.runsPerItemInput.inputValue(),
      passThreshold: await this.passThresholdInput.inputValue(),
    }));
  }

  /**
   * Submit, and report the `from-traces` request the dialog sent.
   *
   * The REQUEST rather than only the stored result, because "sent no
   * `execution_policy`" is the precise claim — a server that defaulted an
   * absent policy to the suite's would make a stored-state-only assertion pass
   * over a dialog that had sent the wrong thing. The listener is attached
   * before the click so a fast response cannot be missed.
   */
  async submit(): Promise<FromTracesRequest> {
    return test.step('Submit the Add to test suite dialog', async () => {
      const posted = this.page.waitForRequest(
        (req) =>
          req.method() === 'POST' && /\/datasets\/[^/]+\/items\/from-traces$/.test(new URL(req.url()).pathname),
      );
      await this.root.getByRole('button', { name: 'Add to test suite', exact: true }).click();
      const request = await posted;
      const body = request.postDataJSON() as {
        trace_ids?: string[];
        execution_policy?: { runs_per_item: number; pass_threshold: number };
      };
      await expect(this.root, 'the dialog closes on submit').toBeHidden();
      return {
        traceIds: body.trace_ids ?? [],
        // `null` for an absent key, never a default: see `FromTracesRequest`.
        executionPolicy: body.execution_policy ?? null,
      };
    });
  }
}
