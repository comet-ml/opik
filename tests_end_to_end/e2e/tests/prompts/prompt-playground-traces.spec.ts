import { test, expect } from '../../fixtures/prompt.fixture';
import { PromptsPage } from '@e2e/pom/prompts.page';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PlaygroundLogsSidebarPage } from '@e2e/pom/playground-logs-sidebar.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';
import type { PromptDetailPage } from '@e2e/pom/prompt-detail.page';
import type { Locator } from '@playwright/test';

type PromptVariant = {
  label: string;
  nameSuffix: string;
  create: (prompts: PromptsPage, name: string, content: string) => Promise<PromptDetailPage>;
  detailLocator: (detail: PromptDetailPage) => Locator;
};

const PROMPT_VARIANTS: PromptVariant[] = [
  {
    label: 'chat',
    nameSuffix: 'chat-playground',
    create: (prompts, name, content) => prompts.createChatPromptViaUI(name, content),
    detailLocator: (detail) => detail.chatMessages(),
  },
  {
    label: 'text',
    nameSuffix: 'text-playground',
    create: (prompts, name, content) => prompts.createTextPromptViaUI(name, content),
    detailLocator: (detail) => detail.textContent(),
  },
];

test.describe('Prompt → Playground → Traces', { tag: ['@t2-cuj', '@prompts', '@playground'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  for (const variant of PROMPT_VARIANTS) {
    test(`create ${variant.label} prompt, load in Playground, run, verify trace and Prompts tab`, async ({
      project,
      page,
      registerPromptCleanup,
      testNamespace,
    }) => {
      test.setTimeout(90_000);

      const promptName = `${testNamespace}-${variant.nameSuffix}`;
      const messageContent = 'Say hello in exactly one word.';

      const modelDisplayName = await test.step('Ensure a model is available', async () => {
        return ensureModelAvailable(page);
      });

      const playground = new PlaygroundPage(page, project.id);

      // Visit the playground once so `lastActiveProjectId` is initialised in the
      // Zustand PlaygroundStore. Without this, the store's resetPlayground()
      // effect fires on first mount (null !== projectId) and clears the prompt
      // map that `useLoadPlayground` set just before navigating here.
      await test.step('Initialize Playground lastActiveProjectId', async () => {
        await playground.goto();
        await playground.waitForReady();
      });

      const prompts = new PromptsPage(page);

      await test.step('Navigate to Prompts Library', async () => {
        await prompts.goto(project.id);
        await prompts.waitForReady();
      });

      const detail = await variant.create(prompts, promptName, messageContent);

      // Register cleanup before any navigation so teardown still runs on failure
      const urlParts = new URL(page.url()).pathname.split('/').filter(Boolean);
      const promptsIdx = urlParts.lastIndexOf('prompts');
      const promptId = promptsIdx >= 0 ? (urlParts[promptsIdx + 1] ?? '') : '';
      expect(promptId, 'Expected to extract promptId from URL after prompt creation').toBeTruthy();
      registerPromptCleanup(promptId, promptName);

      await test.step('Verify prompt detail page', async () => {
        await detail.waitForReady();
        await expect(detail.promptNameHeading()).toHaveText(promptName);
        await expect(variant.detailLocator(detail)).toContainText(messageContent);
      });

      await detail.loadInPlayground();

      await playground.waitForReady();

      await test.step('Select model and run prompt', async () => {
        await playground.selectModel(0, modelDisplayName);
        // Set up listener BEFORE clicking Run so we don't miss the batch response.
        const traceLogged = page.waitForResponse(
          (r) => r.url().includes('/traces/batch') && r.ok(),
          { timeout: 60_000 },
        );
        await playground.runFreeMode(60_000);
        // Wait for the trace batch POST to return — trace is now committed to the backend.
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

      await test.step('Verify trace detail panel shows the prompt message', async () => {
        await expect(sidebar.traceDetailPanel().getByText(messageContent).first()).toBeVisible();
      });

      await test.step('Open Prompts tab and verify prompt is linked', async () => {
        await sidebar.clickPromptsTab();
        await expect(sidebar.traceDetailPanel().getByText(promptName).first()).toBeVisible();
        await expect(sidebar.traceDetailPanel().getByText(messageContent).first()).toBeVisible();
      });
    });
  }
});
