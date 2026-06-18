import { test, expect } from '../../fixtures/prompt.fixture';
import { PromptsPage } from '@e2e/pom/prompts.page';
import type { PromptDetailPage } from '@e2e/pom/prompt-detail.page';
import type { Locator } from '@playwright/test';

type VersioningOpts = {
  contentLocator: (d: PromptDetailPage) => Locator;
  initialContent: string;
  updatedContent: string;
  edit: (d: PromptDetailPage, content: string) => Promise<void>;
};

async function runVersioningSteps(detail: PromptDetailPage, opts: VersioningOpts): Promise<void> {
  await test.step('Initial version is v1', async () => {
    await expect(detail.activeVersionLabel()).toHaveText('v1');
  });

  await opts.edit(detail, opts.updatedContent);

  await test.step('v2 is active and shows the updated content', async () => {
    await expect(detail.activeVersionLabel()).toHaveText('v2');
    await expect(opts.contentLocator(detail)).toContainText(opts.updatedContent);
  });

  await test.step('Select v1 from version history and verify original content is restored', async () => {
    await detail.selectVersion('v1');
    await expect(detail.activeVersionLabel()).toHaveText('v1');
    await expect(opts.contentLocator(detail)).toContainText(opts.initialContent);
  });
}

test.describe('Prompt Library — smoke', { tag: ['@t1-smoke', '@prompts'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test('SDK-seeded text prompt appears in library, edit creates new version, old version is preserved', async ({
    project,
    textPrompt,
    page,
  }) => {
    const prompts = new PromptsPage(page);

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

    await runVersioningSteps(detail, {
      contentLocator: (d) => d.textContent(),
      initialContent: 'You are a helpful assistant',
      updatedContent: 'You are an expert assistant. Respond concisely to: {{question}}',
      edit: (d, content) => d.editTextPrompt(content),
    });
  });

  test('SDK-seeded chat prompt appears in library, edit creates new version, old version is preserved', async ({
    project,
    chatPrompt,
    page,
  }) => {
    const prompts = new PromptsPage(page);

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

    await runVersioningSteps(detail, {
      contentLocator: (d) => d.chatMessages(),
      initialContent: 'You are a helpful assistant.',
      updatedContent: 'Provide a concise expert answer to: {{question}}',
      edit: (d, content) => d.editChatFirstMessage(content),
    });
  });

  type UiVariant = {
    label: string;
    nameSuffix: string;
    template: string;
    updatedContent: string;
    create: (prompts: PromptsPage, name: string, content: string) => Promise<PromptDetailPage>;
    versioning: VersioningOpts;
  };

  const UI_VARIANTS: UiVariant[] = [
    {
      label: 'text',
      nameSuffix: 'ui-text',
      template: 'You are a helpful assistant. Answer: {{question}}',
      updatedContent: 'You are an expert assistant. Respond concisely to: {{question}}',
      create: (prompts, name, content) => prompts.createTextPromptViaUI(name, content),
      versioning: {
        contentLocator: (d) => d.textContent(),
        initialContent: 'You are a helpful assistant',
        updatedContent: 'You are an expert assistant. Respond concisely to: {{question}}',
        edit: (d, content) => d.editTextPrompt(content),
      },
    },
    {
      label: 'chat',
      nameSuffix: 'ui-chat',
      template: 'Answer the following: {{question}}',
      updatedContent: 'Provide a concise expert answer to: {{question}}',
      create: (prompts, name, content) => prompts.createChatPromptViaUI(name, content),
      versioning: {
        contentLocator: (d) => d.chatMessages(),
        initialContent: 'Answer the following',
        updatedContent: 'Provide a concise expert answer to: {{question}}',
        edit: (d, content) => d.editChatFirstMessage(content),
      },
    },
  ];

  for (const variant of UI_VARIANTS) {
    test(`UI-created ${variant.label} prompt shows content, edit creates new version, old version is preserved`, async ({
      project,
      page,
      registerPromptCleanup,
      testNamespace,
    }) => {
      const promptName = `${testNamespace}-${variant.nameSuffix}`;
      const prompts = new PromptsPage(page);

      await test.step('Navigate to empty Prompts Library', async () => {
        await prompts.goto(project.id);
        await prompts.waitForReady();
      });

      const detail = await variant.create(prompts, promptName, variant.template);

      const urlParts = new URL(page.url()).pathname.split('/').filter(Boolean);
      const promptsIdx = urlParts.lastIndexOf('prompts');
      const promptId = promptsIdx >= 0 ? (urlParts[promptsIdx + 1] ?? '') : '';
      expect(promptId, 'Expected to extract promptId from URL after prompt creation').toBeTruthy();
      registerPromptCleanup(promptId, promptName);

      await test.step('Verify detail page shows correct content', async () => {
        await detail.waitForReady();
        await expect(detail.promptNameHeading()).toHaveText(promptName);
        await expect(variant.versioning.contentLocator(detail)).toContainText(variant.versioning.initialContent);
      });

      await runVersioningSteps(detail, variant.versioning);
    });
  }
});
