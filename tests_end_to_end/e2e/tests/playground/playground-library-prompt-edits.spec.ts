import {
  test,
  expect,
  V1_MESSAGES,
  V2_MESSAGES,
  DECOY_MESSAGES,
} from '../../fixtures/library-chat-prompt.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { SidebarNav } from '@e2e/pom/sidebar.page';

/**
 * An unsaved edit to a library prompt must survive leaving the Playground
 * (opik#8780).
 *
 * The regression this guards is silent data loss: before the fix, remounting
 * the variant card re-ran the library load and the template landed back on top
 * of whatever the user had typed, with no error and nothing on screen to say it
 * had happened. `appliedChatPromptVersionId` — persisted beside the loaded
 * version — is what now tells a remount that this version has already been
 * applied once.
 *
 * Why neither existing playground/prompt spec catches it:
 * `prompt-version-playground.spec.ts` loads v1 and runs it without editing, and
 * `prompt-playground-save.spec.ts` edits and saves without ever leaving the
 * page — both stay green with the bug in place.
 */
const EDITED_USER_MESSAGE = 'EDITED-UNSAVED-IN-PLAYGROUND';
const SAVED_USER_MESSAGE = 'EDITED-THEN-SAVED-TO-LIBRARY';

test.describe(
  'Playground — unsaved edits to a loaded library prompt',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'keeps an unsaved edit when you leave the Playground and when you reload',
      { tag: ['@cap:playground.keep-unsaved-prompt-edits'] },
      async ({ project, page, libraryChatPrompt, providerKeys, testNamespace }) => {
        const playground = new PlaygroundPage(page, project.id);
        const sidebar = new SidebarNav(page);

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await playground.loadPromptVersionFromLibrary(libraryChatPrompt.name, 'v1');

        await test.step('The library template is applied and recorded as applied', async () => {
          await expect
            .poll(() => playground.messageBodies())
            .toEqual(V1_MESSAGES.map((message) => message.content));
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v1');
          // The pin and the marker must agree, or the next remount has nothing
          // to compare the version it is about to apply against.
          const stored = await playground.readStoredPrompt();
          expect(stored.loadedChatPromptVersionId).toBe(libraryChatPrompt.v1.id);
          expect(stored.appliedChatPromptVersionId).toBe(libraryChatPrompt.v1.id);
        });

        await test.step('Edit the user message and leave it unsaved', async () => {
          await playground.editUserMessage(EDITED_USER_MESSAGE);
          await expect
            .poll(() => playground.messageBodies())
            .toEqual([V1_MESSAGES[0].content, EDITED_USER_MESSAGE]);
        });

        await test.step('Leave for the Prompt library and come back', async () => {
          // Armed before the round trip: the card refetches its version on
          // mount, and a re-apply would land the instant that answer does, so
          // asserting before it would pass on a build that clobbers the edit a
          // tick later.
          const versionFetched = playground.waitForPromptVersionFetch(libraryChatPrompt.v1.id);
          await sidebar.navigateTo('Prompt library');
          await sidebar.navigateTo('Prompt playground');
          await playground.waitForReady();
          await versionFetched;
        });

        await test.step('The edit is still there, under the same version', async () => {
          await expect
            .poll(() => playground.messageBodies())
            .toEqual([V1_MESSAGES[0].content, EDITED_USER_MESSAGE]);
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v1');
          expect((await playground.readStoredPrompt()).appliedChatPromptVersionId).toBe(
            libraryChatPrompt.v1.id,
          );
        });

        await test.step('Reload the page', async () => {
          const versionFetched = playground.waitForPromptVersionFetch(libraryChatPrompt.v1.id);
          await page.reload();
          await playground.waitForReady();
          await versionFetched;
        });

        await test.step('The edit survives the reload too', async () => {
          // A full reload, not just a remount: this is what shows the edit is
          // carried by the persisted store rather than by an in-memory ref that
          // an SPA navigation happens to leave alone.
          await expect
            .poll(() => playground.messageBodies())
            .toEqual([V1_MESSAGES[0].content, EDITED_USER_MESSAGE]);
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v1');
          expect((await playground.readStoredPrompt()).appliedChatPromptVersionId).toBe(
            libraryChatPrompt.v1.id,
          );
        });
      },
    );

    test(
      'does not apply a library version published while the edit is unsaved',
      { tag: ['@cap:playground.keep-unsaved-prompt-edits'] },
      async ({ project, page, libraryChatPrompt, providerKeys, testNamespace }) => {
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await playground.loadPromptVersionFromLibrary(libraryChatPrompt.name, 'v1');

        await test.step('Edit the user message and leave it unsaved', async () => {
          await expect
            .poll(() => playground.messageBodies())
            .toEqual(V1_MESSAGES.map((message) => message.content));
          await playground.editUserMessage(EDITED_USER_MESSAGE);
          await expect
            .poll(() => playground.messageBodies())
            .toEqual([V1_MESSAGES[0].content, EDITED_USER_MESSAGE]);
        });

        const v2 = await test.step('Someone publishes v2 to the library', async () => {
          const version = await libraryChatPrompt.publishVersion(V2_MESSAGES);
          // Proven server-side before the browser is asked anything: a seed
          // that silently failed to commit would leave an assertion about "v2
          // was not applied" that no build could fail.
          expect(version.versionNumber).toBe('v2');
          expect(version.id).not.toBe(libraryChatPrompt.v1.id);
          return version;
        });

        await test.step('Reload the Playground', async () => {
          const versionFetched = playground.waitForPromptVersionFetch(libraryChatPrompt.v1.id);
          await page.reload();
          await playground.waitForReady();
          await versionFetched;
        });

        await test.step("v2's template is not applied, and the card has not moved to it", async () => {
          // Two things protect the edit here and only one of them is new.
          // Loading from the library PINS the card, so the hook only ever
          // fetches v1 and v2 is never a candidate — that was true before
          // opik#8780 too. What the marker adds is that v1's OWN template is no
          // longer re-applied over the edit on remount. The version-specific
          // half of the marker is exercised in
          // playground-prompt-state-hydration.spec.ts.
          await expect
            .poll(() => playground.messageBodies())
            .toEqual([V1_MESSAGES[0].content, EDITED_USER_MESSAGE]);
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v1');
          const stored = await playground.readStoredPrompt();
          expect(stored.loadedChatPromptVersionId).toBe(libraryChatPrompt.v1.id);
          expect(stored.appliedChatPromptVersionId).toBe(libraryChatPrompt.v1.id);
          expect(stored.appliedChatPromptVersionId).not.toBe(v2.id);
        });
      },
    );

    test(
      'replaces the edited messages when another library prompt is deliberately loaded',
      { tag: ['@cap:playground.load-prompt-version'] },
      async ({
        project,
        page,
        libraryChatPrompt,
        decoyChatPrompt,
        providerKeys,
        testNamespace,
      }) => {
        // The control for the two tests above: a guard that kept edits by
        // refusing to load anything at all would satisfy both of them, and
        // would break the Playground. A user who picks a prompt must get it.
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await playground.loadPromptVersionFromLibrary(libraryChatPrompt.name, 'v1');

        await test.step('Edit the user message and leave it unsaved', async () => {
          await expect
            .poll(() => playground.messageBodies())
            .toEqual(V1_MESSAGES.map((message) => message.content));
          await playground.editUserMessage(EDITED_USER_MESSAGE);
          await expect
            .poll(() => playground.messageBodies())
            .toEqual([V1_MESSAGES[0].content, EDITED_USER_MESSAGE]);
        });

        await test.step('Detach the loaded prompt and pick a different one', async () => {
          await playground.detachLoadedPrompt();
          await playground.loadPromptVersionFromLibrary(decoyChatPrompt.name, 'v1');
        });

        await test.step("the decoy's template replaces the edited messages", async () => {
          await expect
            .poll(() => playground.messageBodies())
            .toEqual(DECOY_MESSAGES.map((message) => message.content));
          await playground.waitForLoadedPromptVersion(decoyChatPrompt.name, 'v1');
          const stored = await playground.readStoredPrompt();
          expect(stored.loadedChatPromptId).toBe(decoyChatPrompt.id);
          expect(stored.loadedChatPromptVersionId).toBe(decoyChatPrompt.v1.id);
          expect(stored.appliedChatPromptVersionId).toBe(decoyChatPrompt.v1.id);
        });
      },
    );

    test(
      'renders the saved content, not the old template, after saving the edit and returning',
      { tag: ['@cap:playground.save-prompt-version'] },
      async ({ project, page, libraryChatPrompt, providerKeys, testNamespace }) => {
        // The third path through the same marker. `prompt-playground-save.spec.ts`
        // already covers that saving commits a new version; what it never does
        // is LEAVE the page afterwards, which is where a stale template would
        // be re-applied over content the user has just saved.
        const playground = new PlaygroundPage(page, project.id);
        const sidebar = new SidebarNav(page);
        const savedMessages = [V1_MESSAGES[0].content, SAVED_USER_MESSAGE];

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await playground.loadPromptVersionFromLibrary(libraryChatPrompt.name, 'v1');

        await test.step('Edit the user message', async () => {
          await expect
            .poll(() => playground.messageBodies())
            .toEqual(V1_MESSAGES.map((message) => message.content));
          await playground.editUserMessage(SAVED_USER_MESSAGE);
        });

        await test.step('Save it back to the library as a new version', async () => {
          await playground.savePromptToLibrary();
        });

        const saved = await test.step('The save committed v2', async () => {
          const version = await libraryChatPrompt.versionWithTemplate([
            { role: 'system', content: V1_MESSAGES[0].content },
            { role: 'user', content: SAVED_USER_MESSAGE },
          ]);
          expect(version.versionNumber).toBe('v2');
          return version;
        });

        await test.step('The card moves to v2 and the marker follows it', async () => {
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v2');
          const stored = await playground.readStoredPrompt();
          expect(stored.loadedChatPromptVersionId).toBe(saved.id);
          expect(stored.appliedChatPromptVersionId).toBe(saved.id);
        });

        await test.step('Leave for the Prompt library and come back', async () => {
          const versionFetched = playground.waitForPromptVersionFetch(saved.id);
          await sidebar.navigateTo('Prompt library');
          await sidebar.navigateTo('Prompt playground');
          await playground.waitForReady();
          await versionFetched;
        });

        await test.step('The saved content is what renders', async () => {
          await expect.poll(() => playground.messageBodies()).toEqual(savedMessages);
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v2');
        });

        await test.step('Reload, and it is still the saved content', async () => {
          const versionFetched = playground.waitForPromptVersionFetch(saved.id);
          await page.reload();
          await playground.waitForReady();
          await versionFetched;

          await expect.poll(() => playground.messageBodies()).toEqual(savedMessages);
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v2');
          expect((await playground.readStoredPrompt()).appliedChatPromptVersionId).toBe(saved.id);
        });
      },
    );
  },
);
