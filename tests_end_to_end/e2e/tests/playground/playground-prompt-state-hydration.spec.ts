import {
  test,
  expect,
  V1_MESSAGES,
  V2_MESSAGES,
} from '../../fixtures/library-chat-prompt.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PromptsPage } from '@e2e/pom/prompts.page';

/**
 * What the Playground does with a `PLAYGROUND_STATE` it did not write itself
 * (opik#8780).
 *
 * The store's `merge` hook is a one-shot migration over real users'
 * localStorage, and it is the half of the fix no e2e spec covers: a regression
 * here is invisible until someone upgrades and loses an edit they had left on
 * screen. The blob is planted rather than built up through the UI because
 * "state written by a build that predates `appliedChatPromptVersionId`" is, by
 * construction, not reachable from this build's UI — and because planting it
 * makes the scenario fully deterministic.
 *
 * Each case plants from the blob this build has just written, so the planted
 * state keeps the real shape rather than a hand-rolled approximation of it.
 */
const EDITED_BEFORE_UPGRADE = 'EDITED-BEFORE-THE-MARKER-EXISTED';

const contents = (messages: Array<{ content: string }>): string[] =>
  messages.map((message) => message.content);

test.describe(
  'Playground — hydrating stored prompt state',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'keeps an edit stored before the applied-version marker existed, and backfills the marker',
      { tag: ['@cap:playground.keep-unsaved-prompt-edits'] },
      async ({ project, page, libraryChatPrompt, providerKeys, testNamespace }) => {
        const playground = new PlaygroundPage(page, project.id);
        const prompts = new PromptsPage(page);

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground once, so a real stored blob exists', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await test.step('Plant the state a pre-upgrade user would be carrying', async () => {
          // Planted from the Prompt library, where the Playground store is not
          // mounted: it persists on every change, so a plant made while the
          // Playground is live races the app's own next write.
          await prompts.goto(project.id);
          await prompts.waitForReady();
          await playground.plantStoredPrompt({
            loadedChatPromptId: libraryChatPrompt.id,
            loadedChatPromptVersionId: libraryChatPrompt.v1.id,
            messages: [
              { role: 'system', content: V1_MESSAGES[0].content },
              { role: 'user', content: EDITED_BEFORE_UPGRADE },
            ],
            // Neither key existed before the fix.
            appliedChatPromptVersionId: undefined,
            backfilled: undefined,
          });
        });

        await test.step('Open the Playground on the planted state', async () => {
          // Armed before the navigation: the re-apply this test says must NOT
          // happen would happen the instant the version answer lands, so
          // asserting any earlier would pass on a build that does it.
          const versionFetched = playground.waitForPromptVersionFetch(libraryChatPrompt.v1.id);
          await playground.goto();
          await playground.waitForReady();
          await versionFetched;
        });

        await test.step('The edit is kept and the marker is repaired', async () => {
          await expect
            .poll(() => playground.messageBodies())
            .toEqual([V1_MESSAGES[0].content, EDITED_BEFORE_UPGRADE]);
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v1');

          const stored = await playground.readStoredPrompt();
          expect(stored.loadedChatPromptVersionId).toBe(libraryChatPrompt.v1.id);
          expect(stored.appliedChatPromptVersionId).toBe(libraryChatPrompt.v1.id);
          // The flag is what makes the migration one-shot; without it the next
          // reload would backfill again, over state this build wrote.
          expect(await playground.readStoredBackfillFlag()).toBe(true);
        });
      },
    );

    test(
      'still applies the pinned version when the marker names an older one',
      { tag: ['@cap:playground.load-prompt-version'] },
      async ({ project, page, libraryChatPrompt, providerKeys, testNamespace }) => {
        // The counterfactual for the case above: a marker that blocked every
        // load would pass it, and would leave a card that can never change
        // version. The guard has to be specific to the version it names.
        const playground = new PlaygroundPage(page, project.id);
        const prompts = new PromptsPage(page);

        const v2 = await test.step('Publish v2 of the library prompt', async () => {
          const version = await libraryChatPrompt.publishVersion(V2_MESSAGES);
          expect(version.versionNumber).toBe('v2');
          expect(version.id).not.toBe(libraryChatPrompt.v1.id);
          return version;
        });

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground once, so a real stored blob exists', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await test.step('Plant a card pinned to v2 whose marker still names v1', async () => {
          await prompts.goto(project.id);
          await prompts.waitForReady();
          await playground.plantStoredPrompt({
            loadedChatPromptId: libraryChatPrompt.id,
            loadedChatPromptVersionId: v2.id,
            appliedChatPromptVersionId: libraryChatPrompt.v1.id,
            messages: [
              { role: 'system', content: V1_MESSAGES[0].content },
              { role: 'user', content: EDITED_BEFORE_UPGRADE },
            ],
            // Already migrated, so this case is about the loader's own
            // version check and not about the one-shot backfill.
            backfilled: true,
          });
        });

        await test.step('Open the Playground on the planted state', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await test.step("v2's template is applied over the stale messages", async () => {
          await expect.poll(() => playground.messageBodies()).toEqual(contents(V2_MESSAGES));
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v2');
          const stored = await playground.readStoredPrompt();
          expect(stored.loadedChatPromptVersionId).toBe(v2.id);
          expect(stored.appliedChatPromptVersionId).toBe(v2.id);
        });
      },
    );

    test(
      'loads the picked version after a reload that interrupted the pick',
      { tag: ['@cap:playground.load-prompt-version'] },
      async ({ project, page, libraryChatPrompt, providerKeys, testNamespace }) => {
        // Picking a version writes the pin before its messages arrive. A reload
        // in that window stores a card pinned to the new version while the
        // editors still hold the old one's text — and before the backfill was
        // made one-shot, the migration marked those old messages as the new
        // version and swallowed the pick.
        const playground = new PlaygroundPage(page, project.id);
        const prompts = new PromptsPage(page);

        const v2 = await test.step('Publish v2 of the library prompt', async () => {
          const version = await libraryChatPrompt.publishVersion(V2_MESSAGES);
          expect(version.versionNumber).toBe('v2');
          expect(version.id).not.toBe(libraryChatPrompt.v1.id);
          return version;
        });

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground once, so a real stored blob exists', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await test.step("Plant a pick of v2 whose messages had not arrived", async () => {
          await prompts.goto(project.id);
          await prompts.waitForReady();
          await playground.plantStoredPrompt({
            loadedChatPromptId: libraryChatPrompt.id,
            loadedChatPromptVersionId: v2.id,
            // No marker: the pick had not been applied when the reload hit.
            appliedChatPromptVersionId: undefined,
            messages: [...V1_MESSAGES],
            // Written by a build that already has the marker, which is what
            // separates this from the pre-upgrade blob above.
            backfilled: true,
          });
        });

        await test.step('Open the Playground on the planted state', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await test.step('The picked version is loaded rather than marked as already applied', async () => {
          await expect.poll(() => playground.messageBodies()).toEqual(contents(V2_MESSAGES));
          await playground.waitForLoadedPromptVersion(libraryChatPrompt.name, 'v2');
          const stored = await playground.readStoredPrompt();
          expect(stored.loadedChatPromptVersionId).toBe(v2.id);
          expect(stored.appliedChatPromptVersionId).toBe(v2.id);
        });
      },
    );
  },
);
