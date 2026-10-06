import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * OPIK-8468: a playground run that fails must read as a failure, not as the model's answer.
 * Before that change `processCombination` wrote `error.message` into the output value, so
 * the failure was painted through `MarkdownPreview` exactly like a completion — a user
 * reading the panel could not tell "the provider is unreachable" from something the model
 * had said. It now renders `PlaygroundOutputError`, a red "Run failed: <message>" tag, and
 * renders no answer at all.
 *
 * This covers the FREE-MODE surface only — the single-prompt `PlaygroundPromptOutput`
 * panel. The other two halves of the same feature already have specs:
 * `playground-run-error.spec.ts` covers that a failed run is *logged* as an errored trace,
 * and `playground-dataset-run-failure.spec.ts` covers the dataset grid's output cell.
 * The panel and the cell are different components gating on separate `hasOutput`
 * expressions, so they can regress apart — which is why this exists as well as, not
 * instead of, the grid spec.
 *
 * The failure is provoked client-side, with a `{{variable}}` no item data defines:
 * `transformMessageIntoProviderMessage` throws before a request is written, so the test
 * depends on no provider key, no reachable model, and nothing an LLM says. The unreachable
 * custom provider is seeded only so the model picker has something to select — nothing is
 * ever sent to it.
 */
const MISSING_VARIABLE = 'missing_column';
const EXPECTED_FAILURE = `Run failed: ${MISSING_VARIABLE} not defined`;

test.describe('Playground — a failed run is not rendered as output', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'A failed free-mode run renders the failure tag and no answer',
    { tag: ['@cap:playground.run-error-info'] },
    async ({ page, project, providerKeys, testNamespace }) => {
      // The failure itself is instant — it is thrown client-side before any request — but
      // the setup ahead of it is not: a project, a REST-seeded provider key and a cold
      // Playground load. Every other spec in this directory budgets 120s or more for that
      // same preamble; the 90s default is the odd one out, and the one that would fail here
      // for a reason that has nothing to do with OPIK-8468.
      test.setTimeout(120_000);

      const modelId = await test.step('Seed an unreachable custom provider', async () => {
        return providerKeys.createUnreachable({
          providerName: `${testNamespace}-unreachable`,
          modelName: `${testNamespace}-dead-model`,
        });
      });

      const playground = new PlaygroundPage(page, project.id);

      await test.step('Compose a prompt referencing an undefined variable, and run it', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: `Summarise {{${MISSING_VARIABLE}}}`,
          modelDisplayName: modelId.split('/').pop(),
        });
        await playground.clickRun();
      });

      await test.step('The run reports as a failure, naming the variable', async () => {
        await playground.waitForPromptOutputErrors(1);
        await expect(
          playground.promptOutputErrorTags(),
          'the panel names itself as a failure and says which variable was missing',
        ).toHaveText([EXPECTED_FAILURE]);
      });

      await test.step('Nothing is rendered as the model answer', async () => {
        await expect(
          playground.promptOutputMarkdownBlocks(),
          'a failed run renders no markdown answer',
        ).toHaveCount(0);
        // The panel left the empty state too — a failure that silently reverted to
        // "No runs yet" would satisfy "no answer rendered" just as well.
        await expect(
          playground.noRunsYetPlaceholders(),
          'a failed run is not the same as one that never happened',
        ).toHaveCount(0);
      });

      await test.step('The failure is not decorated with completion stats', async () => {
        // Scoped to this client-side failure on purpose. The throw lands before the proxy
        // call, so no usage is ever recorded. A proxy-level failure DOES still report a
        // duration — pre-existing, and untouched by OPIK-8468 — so this is not a claim
        // about failed runs in general.
        await expect(
          playground.durationChips(),
          'a run that never reached the proxy has no duration to show',
        ).toHaveCount(0);
        await expect(
          playground.tokenChips(),
          'a run that never reached the proxy spent no tokens',
        ).toHaveCount(0);
      });
    },
  );
});
