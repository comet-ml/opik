import { test, expect } from '@e2e/fixtures/project-prompt-library.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PromptsPage } from '@e2e/pom/prompts.page';

/**
 * Which TYPE the variant header's Save writes to the library (opik#8759).
 *
 * The rule the PR shipped is one line —
 * `savesAsTextPrompt = !selectedChatPromptId && messages.length === 1` — and it
 * has three branches a user can reach. Only the last of them had an assertion
 * before this spec: `prompt-playground-new-prompt.spec.ts` saves the default
 * System+User card and reads the library detail back as chat.
 *
 * ## Why the type and not the content
 *
 * A wrong type is silent at save time AND it is a write. The dialog closes, the
 * row appears in the library, the content is right — and the entry then loads
 * back through the wrong branch later, because `template_structure` is the
 * field `handleLibrarySelect` dispatches on. So the verdict is read from
 * `template_structure` over the API rather than from the badge the library
 * renders: the badge is a second rendering of the same field, while the branch
 * the Playground will take is the field itself.
 *
 * Each case also reads the stored TEMPLATE back, because "the right type" and
 * "the right messages under it" are separable: a text save that wrote the whole
 * two-message array into a one-message template would pass a type assertion.
 *
 * ## The three cases
 *
 *  (a) one message, not library-linked  →  a TEXT prompt
 *  (b) a CHAT-linked card reduced to one message  →  still chat (the
 *      `!selectedChatPromptId` guard; a chat prompt's versions must stay chat)
 *  (c) the default System+User card  →  a chat prompt
 *
 * (a) and (c) differ in exactly one gesture — whether the empty System row was
 * removed — which is what makes the pair evidence about the rule rather than
 * about two unrelated saves.
 *
 * NOT covered here: saving a card of three or more messages (the same chat
 * branch as (c), reached the same way), and what the dialog's "save as new"
 * versus "update" modes do to a prompt of the OTHER type, which the library's
 * own specs own.
 */
test.describe(
  'Playground → the variant header Save writes the prompt type the card implies',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'a one-message card saves as a TEXT prompt that reopens as text',
      { tag: ['@cap:playground.save-prompt-structure'] },
      async ({
        page,
        project,
        backendClient,
        providerKeys,
        registerPromptCleanup,
        testNamespace,
      }) => {
        test.setTimeout(120_000);

        const promptName = `${testNamespace}-saved-text`;
        const body = 'Explain the water cycle in exactly one sentence.';
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the setup dialog stays shut', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground and type into the User message', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.editUserMessage(body);
        });

        await test.step('Remove the empty System row so the card holds one message', async () => {
          await expect(playground.messageRows()).toHaveCount(2);
          await playground.removeMessageAt(0);
          expect(await playground.messageRoleAt(0), 'the surviving row is the User one').toBe(
            'User',
          );
          expect(await playground.variantMessageBodies()).toEqual([body]);
        });

        await test.step('The header Save advertises the text branch', async () => {
          expect(await playground.headerSaveTooltipText()).toBe('Save as text prompt');
        });

        const stored = await test.step('The library stored it as a text prompt', async () => {
          await playground.saveNewPromptFromHeader(promptName);

          const prompt = await backendClient.getPromptByName(promptName, project.id);
          registerPromptCleanup(prompt.id, prompt.name);
          expect(prompt.templateStructure, 'template_structure of the saved prompt').toBe('text');
          expect(prompt.versionCount, 'one commit, not two').toBe(1);
          return prompt;
        });

        await test.step('…holding the single message, under its own role', async () => {
          const versions = await backendClient.listPromptVersions(stored.id);
          expect(versions).toHaveLength(1);
          expect(JSON.parse(versions[0].template)).toEqual([{ role: 'user', content: body }]);
        });

        await test.step('…and nothing else was written to the library', async () => {
          // The whole answer, not just that this prompt is in it: a save that
          // took both branches would leave a second prompt beside this one, and
          // a by-name lookup cannot see that. Workspace-wide by run prefix, so
          // a prompt written outside the project is caught too.
          const mine = await backendClient.listPromptsWithPrefix(testNamespace);
          expect(mine.map((p) => p.name)).toEqual([promptName]);
        });

        await test.step('It reopens as text in the library detail', async () => {
          const prompts = new PromptsPage(page);
          await prompts.goto(project.id);
          await prompts.waitForReady();
          const detail = await prompts.openPromptByName(promptName);
          await detail.waitForReady();
          await expect(detail.promptNameHeading()).toHaveText(promptName);
          await expect(detail.textContent()).toContainText(body);
          await expect(detail.chatMessages(), 'no chat message block').toHaveCount(0);
        });
      },
    );

    test(
      'a CHAT-linked card reduced to one message still saves as chat',
      { tag: ['@cap:playground.save-prompt-structure'] },
      async ({ page, project, backendClient, promptLibrary, providerKeys, testNamespace }) => {
        test.setTimeout(150_000);

        const seeded = [
          { role: 'system' as const, content: 'You are a terse assistant.' },
          { role: 'user' as const, content: 'Name one ocean.' },
        ];
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the setup dialog stays shut', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        const chatPrompt = await test.step('Seed one chat prompt — the only thing in this library', async () =>
          promptLibrary.seedChatPrompt({ suffix: 'linked-chat', messages: seeded }));

        await test.step('Load it through the variant header', async () => {
          await playground.goto();
          await playground.waitForReady();
          const menu = await playground.openHeaderLibraryMenu();
          // One type in this project's library, so the menu must open straight
          // on the list. Asserted rather than tolerated: if the chooser
          // appeared here the selection below would silently be a different
          // gesture from the one this case is about.
          await menu.expectOpensOnList();
          await menu.selectPrompt(chatPrompt.name);

          await expect
            .poll(async () => playground.variantMessageBodies(), { timeout: 30_000 })
            .toEqual(seeded.map((m) => m.content));
        });

        await test.step('Reduce the linked card to a single message', async () => {
          await playground.removeMessageAt(0);
          expect(await playground.variantMessageBodies()).toEqual([seeded[1].content]);
        });

        await test.step('The save commits a second version of the SAME chat prompt', async () => {
          await playground.savePromptToLibrary();

          await expect
            .poll(
              async () =>
                (await backendClient.getPromptByName(chatPrompt.name, project.id)).versionCount,
              {
                timeout: 30_000,
                message: 'the save committed a second version of the chat prompt',
              },
            )
            .toBe(2);

          const after = await backendClient.getPromptByName(chatPrompt.name, project.id);
          expect(after.id, 'committed onto the seeded prompt, not a new one').toBe(chatPrompt.id);
          expect(after.templateStructure, 'the type after a one-message save').toBe('chat');
        });

        await test.step('…holding the one remaining message, still as a chat template', async () => {
          const versions = await backendClient.listPromptVersions(chatPrompt.id);
          expect(versions).toHaveLength(2);
          // Newest first, so [0] is the commit the save just wrote.
          expect(JSON.parse(versions[0].template)).toEqual([
            { role: 'user', content: seeded[1].content },
          ]);
        });

        await test.step('…and no stray text prompt appeared beside it', async () => {
          const mine = await backendClient.listPromptsWithPrefix(testNamespace);
          expect(mine.map((p) => p.name)).toEqual([chatPrompt.name]);
        });
      },
    );

    test(
      'the default System+User card saves as a chat prompt',
      { tag: ['@cap:playground.save-prompt-structure'] },
      async ({
        page,
        project,
        backendClient,
        providerKeys,
        registerPromptCleanup,
        testNamespace,
      }) => {
        test.setTimeout(120_000);

        const promptName = `${testNamespace}-saved-chat`;
        const body = 'Explain recursion in simple terms.';
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the setup dialog stays shut', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground and type into the User message', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.editUserMessage(body);
          await expect(playground.messageRows(), 'the default card holds two rows').toHaveCount(2);
        });

        await test.step('The header Save advertises the chat branch', async () => {
          expect(await playground.headerSaveTooltipText()).toBe('Save as chat prompt');
        });

        await test.step('The library stored it as a chat prompt holding BOTH messages', async () => {
          await playground.saveNewPromptFromHeader(promptName);

          const prompt = await backendClient.getPromptByName(promptName, project.id);
          registerPromptCleanup(prompt.id, prompt.name);
          expect(prompt.templateStructure).toBe('chat');
          expect(prompt.versionCount).toBe(1);

          const versions = await backendClient.listPromptVersions(prompt.id);
          expect(versions).toHaveLength(1);
          // The System row is empty and is kept anyway: the whole point of the
          // chat branch is that the card's shape is preserved, so a template
          // that silently dropped it would be the text branch by another name.
          expect(JSON.parse(versions[0].template)).toEqual([
            { role: 'system', content: '' },
            { role: 'user', content: body },
          ]);
        });

        await test.step('…and nothing else was written to the library', async () => {
          const mine = await backendClient.listPromptsWithPrefix(testNamespace);
          expect(mine.map((p) => p.name)).toEqual([promptName]);
        });
      },
    );
  },
);
