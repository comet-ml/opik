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
 * Complements playground-run-error.spec.ts, which covers the other half of the same
 * feature: that a failed run is *logged* as an errored trace. This one covers what the
 * Playground itself puts on screen, on both output surfaces — the single-prompt panel
 * (`PlaygroundPromptOutput`) and the dataset output cell (`PlaygroundOutputCell`), which
 * gate their content on separate `hasOutput` expressions and so can regress apart.
 *
 * Both tests provoke the failure client-side, with a `{{variable}}` the item data does not
 * define: `transformMessageIntoProviderMessage` throws before a request is written, so
 * neither test depends on a provider key, on a reachable model, or on what an LLM answers.
 * The unreachable custom provider is seeded only so the model picker has something to
 * select — nothing is ever sent to it.
 */
const MISSING_VARIABLE = 'missing_column';
const EXPECTED_FAILURE = `Run failed: ${MISSING_VARIABLE} not defined`;

test.describe('Playground — a failed run is not rendered as output', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'A failed free-mode run renders the failure tag and no answer',
    { tag: ['@cap:playground.compose-run-prompt'] },
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
        await playground.waitForOutputErrors(1);
        await expect(playground.outputErrorTags()).toHaveText([EXPECTED_FAILURE]);
      });

      await test.step('Nothing is rendered as the model answer', async () => {
        await expect(playground.renderedAnswers()).toHaveCount(0);
        // The panel left the empty state too — a failure that silently reverted to
        // "No runs yet" would satisfy "no answer rendered" just as well.
        await expect(playground.noRunsYetPlaceholders()).toHaveCount(0);
      });

      await test.step('The failure is not decorated with completion stats', async () => {
        // Scoped to this client-side failure on purpose. The throw lands before the proxy
        // call, so no usage is ever recorded. A proxy-level failure DOES still report a
        // duration — pre-existing, and untouched by OPIK-8468 — so this is not a claim
        // about failed runs in general.
        await expect(playground.durationChips()).toHaveCount(0);
        await expect(playground.tokenChips()).toHaveCount(0);
      });
    },
  );

  test(
    'Every dataset output cell shows the failure tag instead of an answer',
    { tag: ['@cap:playground.run-against-dataset'] },
    async ({ page, project, dataset, providerKeys, backendClient, testNamespace }) => {
      test.setTimeout(180_000);

      const expectedRows = dataset.items.length;

      await test.step('Confirm the seeded items really lack the column the prompt will ask for', async () => {
        // Without this the test cannot discriminate: a dataset that seeded no items, or one
        // that happened to carry the column, would fail or pass for the wrong reason and the
        // UI assertions below would read as coverage either way.
        const items = await backendClient.listDatasetItemsWithData(dataset.id);
        expect(items).toHaveLength(expectedRows);
        for (const item of items) {
          expect(Object.keys(item.data)).not.toContain(MISSING_VARIABLE);
        }
      });

      const modelId = await test.step('Seed an unreachable custom provider', async () => {
        return providerKeys.createUnreachable({
          providerName: `${testNamespace}-unreachable`,
          modelName: `${testNamespace}-dead-model`,
        });
      });

      const playground = new PlaygroundPage(page, project.id);

      await test.step('Load the dataset and run a prompt against a column it does not have', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: `Summarise {{${MISSING_VARIABLE}}}`,
          modelDisplayName: modelId.split('/').pop(),
        });
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
        await playground.waitForRunReady({ expectedRows });
        await playground.clickReRun();
      });

      await test.step('Every row reports the same failure', async () => {
        await playground.waitForOutputErrors(expectedRows);
        // Asserted as a list rather than per-cell: this pins the count AND every cell's
        // text, so a run that failed one row and left the others behind cannot pass.
        await expect(playground.resultsOutputErrorTags()).toHaveText(
          Array.from({ length: expectedRows }, () => EXPECTED_FAILURE),
        );
      });

      await test.step('No cell renders an answer, and none reverts to the empty state', async () => {
        await expect(playground.resultsRenderedAnswers()).toHaveCount(0);
        await expect(playground.resultsNoRunsYetPlaceholders()).toHaveCount(0);
      });
    },
  );
});
