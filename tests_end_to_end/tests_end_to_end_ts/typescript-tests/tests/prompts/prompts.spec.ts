import { test, expect } from '../../fixtures/feedback-experiments-prompts.fixture';
import type { Page } from '@playwright/test';
import { PromptsPage, PromptDetailsPage } from '../../page-objects/prompts.page';

test.describe('Prompts CRUD Tests', () => {
  test.describe('Prompt creation and visibility', () => {
    test('Prompts created via SDK are visible in both UI and SDK with correct content @sanity @happypaths @fullregression @prompts', async ({
      page,
      helperClient,
      createPrompt,
    }) => {
      await test.step('Verify prompt is retrievable via SDK with correct content', async () => {
        const promptSdk = await helperClient.getPrompt(createPrompt.name);
        expect(promptSdk.name).toBe(createPrompt.name);
        expect(promptSdk.prompt).toBe(createPrompt.prompt);
      });

      await test.step('Verify prompt is visible in UI', async () => {
        const promptsPage = new PromptsPage(page);
        await promptsPage.goto();
        await promptsPage.checkPromptExists(createPrompt.name);
      });
    });
  });

  test.describe('Prompt deletion', () => {
    test('Prompts can be deleted via UI and deletion is reflected in SDK @fullregression @prompts', async ({
      page,
      helperClient,
      createPrompt,
    }) => {
      await test.step('Delete prompt via UI', async () => {
        const promptsPage = new PromptsPage(page);
        await promptsPage.goto();
        await promptsPage.deletePrompt(createPrompt.name);
      });

      await test.step('Verify prompt is not visible in UI after reload', async () => {
        const promptsPage = new PromptsPage(page);
        await page.reload();
        await promptsPage.checkPromptNotExists(createPrompt.name);
      });

      await test.step('Verify prompt returns 404 when fetched via SDK', async () => {
        try {
          const result = await helperClient.getPrompt(createPrompt.name);
          if (result) {
            throw new Error('Prompt should not exist');
          }
        } catch (error) {
          expect(String(error)).toContain('404');
        }
      });
    });
  });

  test.describe('Prompt updates', () => {
    const testPromptUpdate = async (
      page: Page,
      helperClient: any,
      createPrompt: any,
      updateMethod: 'ui' | 'sdk'
    ) => {
      const updateText = 'This is an updated prompt version';
      const promptsPage = new PromptsPage(page);
      const promptDetailsPage = new PromptDetailsPage(page);
      let versions: Record<string, string>;

      await test.step('Navigate to prompt details page', async () => {
        await promptsPage.goto();
        await promptsPage.clickPrompt(createPrompt.name);
      });

      await test.step(`Update prompt content via ${updateMethod.toUpperCase()}`, async () => {
        if (updateMethod === 'sdk') {
          await helperClient.updatePrompt(createPrompt.name, updateText);
          await page.reload();
        } else {
          await promptDetailsPage.editPrompt(updateText);
        }
      });

      await test.step('Verify both original and updated versions exist in UI', async () => {
        versions = await promptDetailsPage.getAllCommitVersions();
        expect(Object.keys(versions)).toContain(createPrompt.prompt);
        expect(Object.keys(versions)).toContain(updateText);
      });

      await test.step('Verify most recent version shows updated content', async () => {
        await promptDetailsPage.clickMostRecentCommit();
        const currentText = await promptDetailsPage.getSelectedCommitPrompt();
        expect(currentText).toBe(updateText);
      });

      await test.step('Verify SDK returns updated content by default', async () => {
        const promptUpdate = await helperClient.getPrompt(createPrompt.name);
        expect(promptUpdate.prompt).toBe(updateText);
      });

      await test.step('Verify original version is still accessible via commit ID', async () => {
        const originalCommitId = versions[createPrompt.prompt];
        const originalVersion = await helperClient.getPrompt(
          createPrompt.name,
          originalCommitId
        );
        expect(originalVersion.prompt).toBe(createPrompt.prompt);
      });
    };

    test('Prompts can be updated via SDK and version history is maintained @happypaths @fullregression @prompts', async ({ page, helperClient, createPrompt }) => {
      await testPromptUpdate(page, helperClient, createPrompt, 'sdk');
    });

    test('Prompts can be updated via UI and version history is maintained @happypaths @fullregression @prompts', async ({ page, helperClient, createPrompt }) => {
      await testPromptUpdate(page, helperClient, createPrompt, 'ui');
    });
  });
});
