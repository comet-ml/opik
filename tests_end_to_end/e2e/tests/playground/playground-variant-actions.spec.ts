import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * Two models from two unreachable providers, so the copy's model is observable:
 * variant B picks the second one last, so a duplicate that fell back to the
 * last-picked model instead of copying its source would show the wrong one.
 */
const MODEL_A = 'variant-a-model';
const MODEL_B = 'variant-b-model';

test.describe('Playground — variant actions', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'any variant can be duplicated in place, and Add variant adds a blank one',
    { tag: ['@cap:playground.compose-run-prompt'] },
    async ({ page, project, providerKeys, testNamespace }) => {
      const playground = new PlaygroundPage(page, project.id);

      await test.step('Seed two selectable providers that refuse every connection', async () => {
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-a`,
          modelName: MODEL_A,
        });
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-b`,
          modelName: MODEL_B,
        });
      });

      await test.step('Open the Playground with variant A configured', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: 'Prompt A',
          modelDisplayName: MODEL_A,
        });
      });

      await test.step('Add variant adds one blank variant at the end', async () => {
        await playground.addBlankVariant();
        await expect(playground.variantCards()).toHaveCount(2);
        expect(await playground.messageBodies()).toEqual(['Prompt A', '']);
      });

      await test.step('Configure variant B with the other model', async () => {
        await playground.configureVariant(1, {
          userPrompt: 'Prompt B',
          modelDisplayName: MODEL_B,
        });
      });

      await test.step('Duplicating the first variant puts its copy right after it', async () => {
        await playground.duplicateVariant(0);
        await expect(playground.variantCards()).toHaveCount(3);
        expect(await playground.messageBodies()).toEqual(['Prompt A', 'Prompt A', 'Prompt B']);
        await expect(playground.variantModelPicker(1)).toContainText(MODEL_A);
        await expect(playground.variantModelPicker(2)).toContainText(MODEL_B);
      });

      await test.step('Every variant offers Duplicate', async () => {
        for (const index of [0, 1, 2]) {
          await expect(playground.duplicateVariantButton(index)).toHaveCount(1);
        }
      });

      await test.step('Editing the copy leaves its source alone', async () => {
        await playground.configureVariant(1, { userPrompt: 'Prompt A edited' });
        expect(await playground.messageBodies()).toEqual([
          'Prompt A',
          'Prompt A edited',
          'Prompt B',
        ]);
      });
    },
  );
});
