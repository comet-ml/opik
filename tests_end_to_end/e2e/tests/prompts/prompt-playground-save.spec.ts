import { test, expect } from '../../fixtures/prompt.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PromptsPage } from '@e2e/pom/prompts.page';

const PROMPT_VARIANTS = [
  {
    promptType: 'text' as const,
    title: 'edit text prompt v1 in Playground and save creates new version in prompt library',
    promptNameSuffix: 'playground-save-text',
    editedContent: 'Describe the water cycle in one sentence.',
  },
  {
    promptType: 'chat' as const,
    title: 'edit chat prompt v1 in Playground and save creates new version in prompt library',
    promptNameSuffix: 'playground-save',
    editedContent: 'Explain quantum computing in simple terms.',
  },
];

test.describe(
  'Playground → save prompt to library',
  { tag: ['@t2-cuj', '@prompts', '@playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    for (const { promptType, title, promptNameSuffix, editedContent } of PROMPT_VARIANTS) {
      test(
        title,
        async ({ project, page, sdkClient, registerPromptCleanup, testNamespace }) => {
          test.setTimeout(90_000);

          const promptName = `${testNamespace}-${promptNameSuffix}`;
          const v1Content = 'Say hello in exactly one word.';
          const v2Content = 'Say goodbye in exactly one word.';
          const v3Content = 'What is the capital of France?';

          await test.step(`Create 3 versions of the ${promptType} prompt via SDK`, async () => {
            for (const [i, content] of [v1Content, v2Content, v3Content].entries()) {
              const result =
                promptType === 'chat'
                  ? await sdkClient.python.createChatPrompt({
                      name: promptName,
                      messages: [{ role: 'user', content }],
                      project_name: project.name,
                    })
                  : await sdkClient.python.createTextPrompt({
                      name: promptName,
                      prompt: content,
                      project_name: project.name,
                    });
              if (i === 0) registerPromptCleanup(result.id, promptName);
            }
          });

          const playground = new PlaygroundPage(page, project.id);

          await test.step('Pre-initialize Playground (set lastActiveProjectId)', async () => {
            await playground.goto();
            await playground.waitForReady();
          });

          if (promptType === 'chat') {
            await playground.loadPromptVersionFromLibrary(promptName, 'v1');
          } else {
            await playground.loadTextPromptVersionFromLibrary(promptName, 'v1');
          }

          await test.step(`Verify Playground shows ${promptType} prompt at v1`, async () => {
            await playground.waitForReady();
            await playground.waitForLoadedPromptVersion(promptName, 'v1');
          });

          await test.step('Edit the first message in the Playground', async () => {
            await playground.editFirstMessage(editedContent);
          });

          await test.step('Save the prompt back to the library (update existing)', async () => {
            if (promptType === 'chat') {
              await playground.savePromptToLibrary();
            } else {
              await playground.saveTextPromptToLibrary();
            }
          });

          const prompts = new PromptsPage(page);

          await test.step('Navigate to Prompts Library', async () => {
            await prompts.goto(project.id);
            await prompts.waitForReady();
          });

          const detail = await prompts.openPromptByName(promptName);

          await test.step('Verify new version (v4) is active and contains edited content', async () => {
            await detail.waitForReady();
            await expect(detail.activeVersionLabel()).toHaveText('v4');
            if (promptType === 'chat') {
              await expect(detail.chatMessages()).toContainText(editedContent);
            } else {
              await expect(detail.textContent()).toContainText(editedContent);
            }
          });
        },
      );
    }
  },
);
