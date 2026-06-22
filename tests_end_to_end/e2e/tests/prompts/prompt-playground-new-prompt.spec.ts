import { test, expect } from '../../fixtures/prompt.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PromptsPage } from '@e2e/pom/prompts.page';

const PROMPT_VARIANTS = [
  {
    promptType: 'chat' as const,
    title: 'type text in Playground and save creates new prompt in library with correct content',
    nameSuffix: 'playground-new-prompt',
    promptText: 'Describe the water cycle in one sentence.',
  },
  {
    promptType: 'text' as const,
    title: 'type text in Playground message row and save creates new text prompt in library with correct content',
    nameSuffix: 'playground-new-text-prompt',
    promptText: 'Explain the concept of recursion in simple terms.',
  },
];

test.describe(
  'Playground → save new prompt to library',
  { tag: ['@t1-smoke', '@prompts', '@playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    for (const { promptType, title, nameSuffix, promptText } of PROMPT_VARIANTS) {
      test(title, async ({ project, page, registerPromptCleanup, testNamespace }) => {
        test.setTimeout(60_000);

        const promptName = `${testNamespace}-${nameSuffix}`;
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Navigate to Playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await test.step('Enter text in the message area', async () => {
          await playground.editFirstMessage(promptText);
        });

        await test.step('Save prompt to library as a new prompt', async () => {
          if (promptType === 'chat') {
            await playground.saveNewChatPromptToLibrary(promptName);
          } else {
            await playground.saveNewTextPromptToLibrary(promptName);
          }
        });

        const prompts = new PromptsPage(page);

        await test.step('Navigate to Prompt Library', async () => {
          await prompts.goto(project.id);
          await prompts.waitForReady();
        });

        await test.step('New prompt row is visible in the library', async () => {
          await expect(prompts.promptRow(promptName)).toBeVisible();
        });

        const promptId = await prompts.promptRow(promptName).getAttribute('data-row-id');
        if (promptId) registerPromptCleanup(promptId, promptName);

        const detail = await prompts.openPromptByName(promptName);

        await test.step('Prompt detail shows the saved text content', async () => {
          await detail.waitForReady();
          await expect(detail.promptNameHeading()).toHaveText(promptName);
          if (promptType === 'chat') {
            await expect(detail.chatMessages()).toContainText(promptText);
          } else {
            await expect(detail.textContent()).toContainText(promptText);
          }
        });
      });
    }
  },
);
