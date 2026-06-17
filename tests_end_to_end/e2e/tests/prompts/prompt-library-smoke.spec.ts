import { test, expect } from '../../fixtures/prompt.fixture';
import { PromptsPage } from '@e2e/pom/prompts.page';

test.describe('Prompt Library — smoke', { tag: ['@t1-smoke', '@prompts'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test('SDK-seeded text prompt appears in library, edit creates new version, old version is preserved', async ({
    project,
    textPrompt,
    page,
  }) => {
    const prompts = new PromptsPage(page);
    const updatedTemplate = 'You are an expert assistant. Respond concisely to: {{question}}';

    await test.step('Navigate to Prompts Library', async () => {
      await prompts.goto(project.id);
      await prompts.waitForReady();
    });

    await test.step('Text prompt row is visible in the list', async () => {
      await expect(prompts.promptRow(textPrompt.name)).toBeVisible();
    });

    const detail = await prompts.openPromptByName(textPrompt.name);

    await test.step('Open text prompt and verify content', async () => {
      await detail.waitForReady();
      await expect(detail.promptNameHeading()).toHaveText(textPrompt.name);
      await expect(detail.textContent()).toContainText('You are a helpful assistant');
    });

    await test.step('Initial version is v1', async () => {
      await expect(detail.activeVersionLabel()).toHaveText('v1');
    });

    await test.step('Edit the prompt and save as new version', async () => {
      await detail.editTextPrompt(updatedTemplate);
    });

    await test.step('v2 is active and shows the updated content', async () => {
      await expect(detail.activeVersionLabel()).toHaveText('v2');
      await expect(detail.textContent()).toContainText('You are an expert assistant');
    });

    await test.step('Select v1 from version history and verify original content is restored', async () => {
      await detail.selectVersion('v1');
      await expect(detail.activeVersionLabel()).toHaveText('v1');
      await expect(detail.textContent()).toContainText('You are a helpful assistant');
    });
  });

  test('SDK-seeded chat prompt appears in library, edit creates new version, old version is preserved', async ({
    project,
    chatPrompt,
    page,
  }) => {
    const prompts = new PromptsPage(page);
    const updatedMessage = 'Provide a concise expert answer to: {{question}}';

    await test.step('Navigate to Prompts Library', async () => {
      await prompts.goto(project.id);
      await prompts.waitForReady();
    });

    await test.step('Chat prompt row is visible in the list', async () => {
      await expect(prompts.promptRow(chatPrompt.name)).toBeVisible();
    });

    const detail = await prompts.openPromptByName(chatPrompt.name);

    await test.step('Open chat prompt and verify messages', async () => {
      await detail.waitForReady();
      await expect(detail.promptNameHeading()).toHaveText(chatPrompt.name);
      await expect(detail.chatMessages()).toBeVisible();
      await expect(detail.chatMessages()).toContainText('You are a helpful assistant.');
    });

    await test.step('Initial version is v1', async () => {
      await expect(detail.activeVersionLabel()).toHaveText('v1');
    });

    await test.step('Edit the first chat message and save as new version', async () => {
      await detail.editChatFirstMessage(updatedMessage);
    });

    await test.step('v2 is active and shows the updated message content', async () => {
      await expect(detail.activeVersionLabel()).toHaveText('v2');
      await expect(detail.chatMessages()).toContainText('Provide a concise expert answer');
    });

    await test.step('Select v1 from version history and verify original messages are restored', async () => {
      await detail.selectVersion('v1');
      await expect(detail.activeVersionLabel()).toHaveText('v1');
      await expect(detail.chatMessages()).toContainText('You are a helpful assistant.');
    });
  });

  test('UI-created text prompt shows content, edit creates new version, old version is preserved', async ({
    project,
    page,
    registerPromptCleanup,
    testNamespace,
  }) => {
    const promptName = `${testNamespace}-ui-text`;
    const template = 'You are a helpful assistant. Answer: {{question}}';
    const updatedTemplate = 'You are an expert assistant. Respond concisely to: {{question}}';
    const prompts = new PromptsPage(page);

    await test.step('Navigate to empty Prompts Library', async () => {
      await prompts.goto(project.id);
      await prompts.waitForReady();
    });

    const detail = await prompts.createTextPromptViaUI(promptName, template);
    const urlParts = new URL(page.url()).pathname.split('/').filter(Boolean);
    const promptsIdx = urlParts.lastIndexOf('prompts');
    const promptId = promptsIdx >= 0 ? (urlParts[promptsIdx + 1] ?? '') : '';
    if (promptId) registerPromptCleanup(promptId, promptName);

    await test.step('Verify detail page shows correct content', async () => {
      await detail.waitForReady();
      await expect(detail.promptNameHeading()).toHaveText(promptName);
      await expect(detail.textContent()).toContainText('You are a helpful assistant');
    });

    await test.step('Initial version is v1', async () => {
      await expect(detail.activeVersionLabel()).toHaveText('v1');
    });

    await test.step('Edit the prompt and save as new version', async () => {
      await detail.editTextPrompt(updatedTemplate);
    });

    await test.step('v2 is active and shows the updated content', async () => {
      await expect(detail.activeVersionLabel()).toHaveText('v2');
      await expect(detail.textContent()).toContainText('You are an expert assistant');
    });

    await test.step('Select v1 from version history and verify original content is restored', async () => {
      await detail.selectVersion('v1');
      await expect(detail.activeVersionLabel()).toHaveText('v1');
      await expect(detail.textContent()).toContainText('You are a helpful assistant');
    });
  });

  test('UI-created chat prompt shows messages, edit creates new version, old version is preserved', async ({
    project,
    page,
    registerPromptCleanup,
    testNamespace,
  }) => {
    const promptName = `${testNamespace}-ui-chat`;
    const messageContent = 'Answer the following: {{question}}';
    const updatedMessage = 'Provide a concise expert answer to: {{question}}';
    const prompts = new PromptsPage(page);

    await test.step('Navigate to empty Prompts Library', async () => {
      await prompts.goto(project.id);
      await prompts.waitForReady();
    });

    const detail = await prompts.createChatPromptViaUI(promptName, messageContent);
    const urlParts = new URL(page.url()).pathname.split('/').filter(Boolean);
    const promptsIdx = urlParts.lastIndexOf('prompts');
    const promptId = promptsIdx >= 0 ? (urlParts[promptsIdx + 1] ?? '') : '';
    if (promptId) registerPromptCleanup(promptId, promptName);

    await test.step('Verify detail page shows chat messages', async () => {
      await detail.waitForReady();
      await expect(detail.promptNameHeading()).toHaveText(promptName);
      await expect(detail.chatMessages()).toBeVisible();
      await expect(detail.chatMessages()).toContainText('Answer the following');
    });

    await test.step('Initial version is v1', async () => {
      await expect(detail.activeVersionLabel()).toHaveText('v1');
    });

    await test.step('Edit the first chat message and save as new version', async () => {
      await detail.editChatFirstMessage(updatedMessage);
    });

    await test.step('v2 is active and shows the updated message content', async () => {
      await expect(detail.activeVersionLabel()).toHaveText('v2');
      await expect(detail.chatMessages()).toContainText('Provide a concise expert answer');
    });

    await test.step('Select v1 from version history and verify original messages are restored', async () => {
      await detail.selectVersion('v1');
      await expect(detail.activeVersionLabel()).toHaveText('v1');
      await expect(detail.chatMessages()).toContainText('Answer the following');
    });
  });
});
