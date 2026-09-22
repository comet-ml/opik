import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * Resetting the Playground leaves exactly ONE empty default prompt (OPIK-8313).
 *
 * The fix was to a same-flush race between clearing the prompt list and
 * re-seeding it from `generateDefaultPrompt`. Both ways it could lose produce a
 * Playground nobody can use: zero cards, so there is nothing to type into, or
 * two, so the next run silently costs twice what the user asked for. "Exactly
 * one" is therefore the assertion, at every point the race could be lost —
 * on a fresh page, after the list has grown, twice in a row, across a reload,
 * and out of dataset mode.
 *
 * Nothing in the estate drives this control today: the only mention of
 * `resetPlayground` is a comment in `prompt-playground-traces.spec.ts`
 * explaining how that spec routes around it.
 *
 * Deterministic by construction — no model is ever selected and no run is ever
 * started, so there is no LLM call, no provider key and no wall-clock
 * dependence anywhere in here.
 */

/** What an untouched default prompt card contains: one message, with no body. */
const ONE_EMPTY_MESSAGE = [''];

test.describe('Playground — reset', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'reset leaves exactly one empty prompt, and stays that way',
    { tag: ['@cap:playground.compose-run-prompt'] },
    async ({ page, project }) => {
      const playground = new PlaygroundPage(page, project.id);

      await test.step('Open the Playground', async () => {
        await playground.goto();
        await playground.waitForReady();
      });

      await test.step('Reset on a fresh page leaves one empty prompt', async () => {
        await playground.resetPlayground();
        await expect(playground.variantCards()).toHaveCount(1);
        expect(await playground.messageBodies()).toEqual(ONE_EMPTY_MESSAGE);
      });

      await test.step('Grow the prompt list: type a prompt and duplicate it', async () => {
        await playground.fillFirstMessage('Summarise the following in one sentence.');
        await playground.duplicateLastVariant();
        // Assert the state the next step resets FROM. Without this, a reset
        // that did nothing at all would pass the assertions below, because the
        // page would already be sitting at one empty card.
        await expect(playground.variantCards()).toHaveCount(2);
        expect(
          (await playground.messageBodies()).filter((body) => body !== ''),
          'both cards carry the typed prompt before the reset',
        ).toHaveLength(2);
      });

      await test.step('Reset collapses it back to one empty prompt', async () => {
        await playground.resetPlayground();
        await expect(playground.variantCards()).toHaveCount(1);
        expect(await playground.messageBodies()).toEqual(ONE_EMPTY_MESSAGE);
      });

      await test.step('Resetting again is idempotent', async () => {
        await playground.resetPlayground();
        await expect(playground.variantCards()).toHaveCount(1);
        expect(await playground.messageBodies()).toEqual(ONE_EMPTY_MESSAGE);
      });

      await test.step('A reload finds nothing stale in the persisted store', async () => {
        await page.reload();
        await playground.waitForReady();
        await expect(playground.variantCards()).toHaveCount(1);
        expect(await playground.messageBodies()).toEqual(ONE_EMPTY_MESSAGE);
      });
    },
  );

  test(
    'reset from dataset mode clears the source and returns an editable prompt',
    { tag: ['@cap:playground.compose-run-prompt'] },
    async ({ page, project, dataset }) => {
      const playground = new PlaygroundPage(page, project.id);
      const typed = 'Answer using {{input}}.';

      await test.step('Load a dataset into the Playground', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.fillFirstMessage(typed);
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({
          mode: 'dataset',
          entityName: dataset.name,
        });
        // The pill naming the dataset is the state this test resets FROM; the
        // entry control is gone precisely because a source is loaded.
        await expect(playground.loadedSourcePill()).toContainText(dataset.name);
        await expect(playground.runExperimentEntryControl()).toHaveCount(0);
      });

      await test.step('Reset clears the loaded source', async () => {
        await playground.resetPlayground();
        await expect(
          playground.loadedSourcePill(),
          'the dataset pill is gone after a reset',
        ).toHaveCount(0);
        await expect(
          playground.runExperimentEntryControl(),
          'the Run experiment entry control is back, so the Playground is in free mode again',
        ).toBeVisible();
      });

      await test.step('Exactly one empty prompt remains', async () => {
        await expect(playground.variantCards()).toHaveCount(1);
        expect(await playground.messageBodies()).toEqual(ONE_EMPTY_MESSAGE);
      });

      await test.step('The message editor still accepts input', async () => {
        // A card that renders but cannot be typed into is the same outage as no
        // card at all, and the count assertion above cannot tell them apart.
        const retyped = 'A fresh prompt after the reset.';
        await playground.fillFirstMessage(retyped);
        expect(await playground.messageBodies()).toEqual([retyped]);
      });
    },
  );
});
