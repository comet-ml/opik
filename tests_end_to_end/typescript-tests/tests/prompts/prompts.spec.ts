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
      test.info().annotations.push({
        type: 'description',
        description: `Tests that prompts created via the SDK are properly visible and accessible in both the UI and SDK interfaces with correct content.

Steps:
1. Create a prompt via SDK (handled by fixture)
2. Verify the prompt is retrievable via SDK with correct name and content
3. Navigate to the prompts page in the UI
4. Verify the prompt appears in the UI library

This test ensures proper synchronization between UI and backend after SDK-based prompt creation.`
      });

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
      test.info().annotations.push({
        type: 'description',
        description: `Tests that prompts can be deleted through the UI, and the deletion is properly reflected in the SDK.

Steps:
1. Create a prompt via SDK (handled by fixture)
2. Navigate to the prompts page
3. Delete the prompt using the UI delete action
4. Reload the page and verify the prompt no longer appears in the UI
5. Verify the prompt returns 404 when fetched via SDK

This test ensures UI deletions propagate correctly to the backend and SDK.`
      });

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
      test.info().annotations.push({
        type: 'description',
        description: `Tests that prompts can be updated via SDK and version history is properly maintained in both UI and SDK.

Steps:
1. Create a prompt via SDK (handled by fixture)
2. Navigate to the prompt details page
3. Update the prompt content via SDK
4. Verify both original and updated versions appear in the Commits tab
5. Verify the most recent commit shows the updated text
6. Verify SDK returns the updated content by default
7. Verify the original version is still accessible via commit ID

This test ensures prompt versioning works correctly when updates are made via SDK.`
      });

      await testPromptUpdate(page, helperClient, createPrompt, 'sdk');
    });

    test('Prompts can be updated via UI and version history is maintained @happypaths @fullregression @prompts', async ({ page, helperClient, createPrompt }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that prompts can be updated via UI and version history is properly maintained in both UI and SDK.

Steps:
1. Create a prompt via SDK (handled by fixture)
2. Navigate to the prompt details page
3. Update the prompt content via UI
4. Verify both original and updated versions appear in the Commits tab
5. Verify the most recent commit shows the updated text
6. Verify SDK returns the updated content by default
7. Verify the original version is still accessible via commit ID

This test ensures prompt versioning works correctly when updates are made via UI.`
      });

      await testPromptUpdate(page, helperClient, createPrompt, 'ui');
    });
  });
});
