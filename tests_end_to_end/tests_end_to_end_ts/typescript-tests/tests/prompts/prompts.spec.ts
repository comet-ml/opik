import { test, expect } from '../../fixtures/feedback-experiments-prompts.fixture';
import type { Page } from '@playwright/test';
import { PromptsPage, PromptDetailsPage } from '../../page-objects/prompts.page';

test.describe('Prompts CRUD Tests', () => {
  test.describe('Prompt creation and visibility', () => {
    test('should verify prompt visibility in UI and SDK @sanity @regression @prompts', async ({
      page,
      helperClient,
      createPrompt,
    }) => {
      const promptSdk = await helperClient.getPrompt(createPrompt.name);
      expect(promptSdk.name).toBe(createPrompt.name);
      expect(promptSdk.prompt).toBe(createPrompt.prompt);

      const promptsPage = new PromptsPage(page);
      await promptsPage.goto();
      await promptsPage.checkPromptExists(createPrompt.name);
    });
  });

  test.describe('Prompt deletion', () => {
    test('should delete prompt via UI @regression @prompts', async ({
      page,
      helperClient,
      createPrompt,
    }) => {
      const promptsPage = new PromptsPage(page);
      await promptsPage.goto();
      await promptsPage.deletePrompt(createPrompt.name);

      await page.reload();
      await promptsPage.checkPromptNotExists(createPrompt.name);

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

      await promptsPage.goto();
      await promptsPage.clickPrompt(createPrompt.name);

      if (updateMethod === 'sdk') {
        await helperClient.updatePrompt(createPrompt.name, updateText);
        await page.reload();
      } else {
        await promptDetailsPage.editPrompt(updateText);
      }

      const versions = await promptDetailsPage.getAllCommitVersions();
      expect(Object.keys(versions)).toContain(createPrompt.prompt);
      expect(Object.keys(versions)).toContain(updateText);

      await promptDetailsPage.clickMostRecentCommit();
      const currentText = await promptDetailsPage.getSelectedCommitPrompt();
      expect(currentText).toBe(updateText);

      const promptUpdate = await helperClient.getPrompt(createPrompt.name);
      expect(promptUpdate.prompt).toBe(updateText);

      const originalCommitId = versions[createPrompt.prompt];
      const originalVersion = await helperClient.getPrompt(
        createPrompt.name,
        originalCommitId
      );
      expect(originalVersion.prompt).toBe(createPrompt.prompt);
    };

    test('should update prompt via SDK @regression @prompts', async ({ page, helperClient, createPrompt }) => {
      await testPromptUpdate(page, helperClient, createPrompt, 'sdk');
    });

    test('should update prompt via UI @regression @prompts', async ({ page, helperClient, createPrompt }) => {
      await testPromptUpdate(page, helperClient, createPrompt, 'ui');
    });
  });
});
