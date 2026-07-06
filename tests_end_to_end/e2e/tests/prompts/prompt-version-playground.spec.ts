import { test, expect } from '../../fixtures/prompt.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PlaygroundLogsSidebarPage } from '@e2e/pom/playground-logs-sidebar.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';

const PROMPT_VARIANTS = [
  {
    promptType: 'chat' as const,
    title: 'create 3 chat prompt versions, load v1 in Playground, verify trace shows v1',
  },
  {
    promptType: 'text' as const,
    title: 'create 3 text prompt versions, load v1 via Playground dropdown, verify trace shows v1',
  },
];

test.describe(
  'Prompt versioning → Playground → Trace verification',
  { tag: ['@t2-cuj', '@prompts', '@playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    for (const { promptType, title } of PROMPT_VARIANTS) {
      test(title, async ({ project, page, sdkClient, registerPromptCleanup, testNamespace }) => {
        test.setTimeout(120_000);

        const promptName = `${testNamespace}-versioned-${promptType}`;
        const v1Content = 'Say hello in exactly one word.';
        const v2Content = 'Say goodbye in exactly one word.';
        const v3Content = 'What is the capital of France?';

        const modelDisplayName = await test.step('Ensure a model is available', () =>
          ensureModelAvailable(page),
        );

        await test.step(`Create 3 versions of the ${promptType} prompt via SDK`, async () => {
          const contents = [v1Content, v2Content, v3Content];
          for (const [i, content] of contents.entries()) {
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

        await test.step('Verify Playground shows the prompt loaded at v1', async () => {
          await playground.waitForReady();
          await playground.waitForLoadedPromptVersion(promptName, 'v1');
        });

        await test.step('Select model and run the prompt', async () => {
          await playground.selectModel(0, modelDisplayName);
          const traceLogged = page.waitForResponse(
            (r) => r.url().includes('/traces/batch') && r.ok(),
            { timeout: 60_000 },
          );
          await playground.runFreeMode(60_000);
          await traceLogged;
        });

        const sidebar = new PlaygroundLogsSidebarPage(page);

        await test.step('Open Playground logs sidebar', async () => {
          await playground.openLogsPanel();
          await sidebar.waitForOpen();
        });

        await test.step('Verify trace row appears in the sidebar', async () => {
          await sidebar.waitForTraceRow(15_000);
          await expect(sidebar.firstTraceRow()).toBeVisible();
        });

        await sidebar.openFirstTrace();

        await test.step('Open Prompts tab and verify v1 is linked', async () => {
          await sidebar.clickPromptsTab();
          const panel = sidebar.traceDetailPanel();
          await expect(panel.getByText(promptName).first()).toBeVisible();
          await expect(panel.getByText(v1Content).first()).toBeVisible();
          await expect(panel.getByText('v1').first()).toBeVisible();
        });
      });
    }
  },
);
