import type { Page, Locator } from '@playwright/test';
import { test } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

export class AgentPlaygroundPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  async goto(): Promise<void> {
    return test.step('open the Agent Playground page', async () => {
      const env = loadEnvConfig();
      const url = `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/agent-playground`;
      // Don't wait for networkidle: the page embeds the Ollie assistant iframe,
      // which holds a long-lived streaming connection, so the network never goes
      // idle and the goto hangs to the test timeout. Readiness is asserted by
      // waitForHeading() + the Connected badge instead.
      await this.page.goto(url);
      await this.page.waitForURL(new RegExp('/agent-playground'));
    });
  }

  async waitForHeading(): Promise<void> {
    return test.step('wait for the Agent playground heading', async () => {
      await this.page.getByRole('heading', { name: 'Agent playground', level: 1 }).waitFor();
    });
  }

  connectionBadge(): Locator {
    return this.page.getByText('Connected', { exact: true });
  }

  async isConnected(): Promise<boolean> {
    return (await this.connectionBadge().count()) > 0;
  }

  testInputPanel(): Locator {
    return this.page.getByText('Test input');
  }

  /**
   * The entrypoint param's input control. The "Test input" panel label and the
   * Connected badge render before the runner's introspected form mounts, so wait
   * on the actual field (not the panel label) before driving a run.
   */
  inputField(fieldName: string): Locator {
    return this.page.getByPlaceholder(`Enter ${fieldName}...`);
  }

  async fillInput(fieldName: string, value: string): Promise<void> {
    return test.step(`fill input "${fieldName}"`, async () => {
      await this.inputField(fieldName).fill(value);
    });
  }

  /**
   * The header's Run control.
   *
   * Matched on a leading "Run" rather than an exact name: the button renders
   * the Shift and Enter hotkey badges inside itself, so its accessible name is
   * "Run ⇧ ⏎". Anchored so it cannot also match "Run all" or a future sibling,
   * and `.first()` because the control sits inside a tooltip wrapper that
   * duplicates nothing but has historically been re-nested.
   *
   * Exposed as a locator, not only through `clickRun`, because its DISABLED
   * state is a behaviour in its own right: it is driven straight off the input
   * form's zod validity (`hasAllRequiredParams`), so it is where a blanked
   * required field has to show up.
   */
  runButton(): Locator {
    return this.page.getByRole('button', { name: /^Run/ }).first();
  }

  /**
   * The validation message the input form renders under ONE field.
   *
   * Anchored to the field's own input and read from its immediate parent, which
   * is the box the form renders the control and its error into together. Scoped
   * that way rather than matched page-wide on purpose: a message that surfaced
   * under the WRONG field is exactly how a resolver major mismaps zod issues to
   * form paths, and a page-wide text match would call that a pass.
   *
   * The parent step is a structural hop, which the estate avoids — there is no
   * `data-testid` on the field card and the card carries no role or accessible
   * name of its own. The field card SHOULD grow one (`agent-runner-field-
   * <name>`); it is not added here because this spec ships on the QA branch,
   * which may only touch `tests_end_to_end/`. Anchoring on the input rather
   * than descending from the form keeps it independent of the form's nesting
   * depth, which is the part most likely to move.
   */
  inputFieldError(fieldName: string): Locator {
    return this.inputField(fieldName)
      .locator('..')
      .getByText('This field is required', { exact: true });
  }

  async clickRun(): Promise<void> {
    return test.step('click Run', async () => {
      await this.runButton().click();
    });
  }

  runningIndicator(): Locator {
    return this.page.getByText('Running your agent...');
  }

  resultPanel(): Locator {
    return this.page.locator('span').filter({ hasText: 'Result' });
  }

  resultText(query: string): Locator {
    return this.page.getByText(`Answer for: ${query}`);
  }

  viewTraceButton(): Locator {
    return this.page.getByRole('button', { name: 'View trace' });
  }
}
