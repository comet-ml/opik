import { test, expect } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';

/** Provider group label as `PromptModelSelect` renders it in the picker. */
export type ModelProviderLabel = 'Gemini' | 'Vertex AI';

export interface ProviderScopedModel {
  provider: ModelProviderLabel;
  /** Model label as shown in the picker, e.g. `Gemini 2.5 Pro`. */
  model: string;
}

/**
 * Pick a model through its provider's submenu in `PromptModelSelect`.
 *
 * The search box the rest of the suite uses cannot express this: it flattens
 * every configured provider into one list, and several providers publish the
 * same model label ("Gemini 2.5 Pro" exists under both Gemini and Vertex AI),
 * so a search-and-click would silently pick whichever came first. Hovering the
 * provider row opens that provider's submenu, which is also the gesture a user
 * makes when they mean a specific provider's copy of a model.
 *
 * Retried as a unit because the submenu is hover-driven and its options
 * animate in — an option can be resolved but not yet stable when the click
 * fires. The listbox closing is the only reliable signal the pick registered.
 */
export async function selectModelFromProvider(
  page: Page,
  trigger: Locator,
  target: ProviderScopedModel,
): Promise<void> {
  return test.step(`select ${target.provider} model "${target.model}"`, async () => {
    const listbox = page.getByRole('listbox');

    await expect(async () => {
      await trigger.click();
      await expect(listbox).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });

    await expect(async () => {
      await listbox.getByText(target.provider, { exact: true }).first().hover();
      const option = page.getByRole('option', { name: target.model, exact: true });
      // Exactly one: two matches would mean the submenu did not scope the
      // list and we would be about to click an arbitrary provider's model.
      await expect(option).toHaveCount(1, { timeout: 2_000 });
      await option.click({ timeout: 2_000 });
      await expect(listbox).toBeHidden({ timeout: 2_000 });
    }).toPass({ timeout: 30_000 });

    // Confirm the trigger now names this model rather than merely that the
    // popover closed. Anchored at the end, and to a word boundary at the
    // start, so "Gemini 2.5 Pro" cannot be satisfied by "Gemini 2.5 Pro
    // Preview 05-06". The prefix varies by host: the Playground renders the
    // picker `compact` (model label only) while the rule dialog renders
    // "<provider> <model>". Provider scoping is already guaranteed above —
    // only the hovered provider's submenu contributes options.
    await expect(trigger).toHaveText(new RegExp(`(^|\\s)${escapeRegExp(target.model)}$`));
  });
}

function escapeRegExp(literal: string): string {
  return literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
