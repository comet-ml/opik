import {
  test,
  expect,
  MEDIA_PROMPT_TEXT,
  MEDIA_PROMPT_CONTENT,
} from '../../fixtures/media-prompt.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * Switching a message that was loaded from the library to System strips the
 * media the new role cannot hold — and does not then report the message as an
 * unsaved edit (opik#8873).
 *
 * This is the half the last push to that PR settled, and it is a DIFFERENT door
 * into the same rule from the one `playground-load-media-prompt-by-role.spec.ts`
 * drives. There, the role is already set and the library load adjusts the
 * content on the way in (`LLMPromptMessageActions`). Here the content is
 * already in the message and the ROLE moves under it (`handleRoleChange` in
 * `LLMPromptMessage`), so the load path is never entered. The two reach
 * `saveWarning` independently and so regress independently: a build that lost
 * the role-aware baseline comparison would keep the load spec green and fail
 * only this one, because only here does the user perform an action they would
 * never call an edit and get an unsaved-changes flag for it.
 *
 * The failure mode is a message permanently marked unsaved, with a save
 * tooltip telling the user to commit a version they never meant to write.
 *
 * ## Addressed by index, deliberately
 *
 * The default card opens with a System row and a User row. Switching the User
 * row to System leaves TWO rows answering to `[data-role="system"]`, so the
 * role-addressed `variantMessage` locator stops being unique and silently
 * resolves to the other row. Every step here uses `messageRowAt(index)`, which
 * a role change does not disturb.
 *
 * Deterministic and provider-free, exactly as the load spec is: nothing runs.
 */

/** `PROMPT_SAVE_TO_LIBRARY_TOOLTIP` — the message still matches its version. */
const SAVE_TOOLTIP = 'Save as text prompt';
/** `PROMPT_UNSAVED_TOOLTIP` — the message has diverged from its version. */
const UNSAVED_TOOLTIP = "This prompt version hasn't been saved";

/** `LLMPromptMessageActions`' toast, title and description as one string. */
const MEDIA_DROPPED_TOAST =
  'Media not loaded' +
  "Only user messages support images, video and audio, so only the prompt's text was loaded.";

/** The row the default card opens as a User message — the one switched below. */
const USER_ROW = 1;

test.describe(
  'Playground — switching a loaded message to a role that cannot hold media',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'the media goes and the loaded version is not flagged as unsaved',
      { tag: ['@cap:playground.message-role-media'] },
      async ({ page, project, providerKeys, mediaPrompt, backendClient, testNamespace }) => {
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground', async () => {
          await playground.startRecordingToasts();
          await playground.goto();
          await playground.waitForReady();
          expect(
            await playground.messageRoleAt(USER_ROW),
            'the row this test switches starts out as a User message',
          ).toBe('User');
        });

        await test.step('Load the media prompt into the User row', async () => {
          await playground.loadTextPromptVersionIntoMessage(
            USER_ROW,
            mediaPrompt.name,
            mediaPrompt.versionLabel,
          );
          await playground.waitForLoadedPromptVersionInMessage(
            USER_ROW,
            mediaPrompt.name,
            mediaPrompt.versionLabel,
          );
        });

        await test.step('The media really is in the message before the switch', async () => {
          // The precondition, and load-bearing: every assertion after the switch
          // is that media WENT, and a user row that never received any would
          // satisfy all of them while proving nothing about the switch.
          const messages = await playground.storedMessages();
          expect(
            messages[USER_ROW].content,
            'the user row holds the whole content, media parts included',
          ).toEqual(MEDIA_PROMPT_CONTENT);

          await playground.hoverMessageRow(USER_ROW);
          for (const url of mediaPrompt.mediaUrls) {
            await expect(
              playground.mediaTag(USER_ROW, url),
              `the ${url} tag is on screen before the switch`,
            ).toBeVisible();
          }
          expect(
            await playground.recordedToasts(),
            'a user-row load drops nothing, so nothing has been announced yet',
          ).toEqual([]);
        });

        await test.step('Switch that row to a System message', async () => {
          await playground.setMessageRole(USER_ROW, 'System');
        });

        await test.step('The message is now the plain text', async () => {
          const messages = await playground.storedMessages();
          expect(
            messages[USER_ROW].content,
            'the switch left the text and took the media',
          ).toBe(MEDIA_PROMPT_TEXT);
          expect(messages[USER_ROW].role, 'and the row really did become a system message').toBe(
            'system',
          );
          // The card still has the row it started with: a "switch" that removed
          // and re-added a row would satisfy the two assertions above.
          expect(messages, 'the card still holds both of its rows').toHaveLength(2);
        });

        await test.step('And no media tag is left in the row', async () => {
          await playground.hoverMessageRow(USER_ROW);
          for (const url of mediaPrompt.mediaUrls) {
            await expect(
              playground.mediaTag(USER_ROW, url),
              `the ${url} tag is gone from the row`,
            ).toHaveCount(0);
          }
        });

        await test.step('The loaded version is NOT flagged as unsaved', async () => {
          // The assertion this spec exists for. The switch leaves the message
          // holding less than the version it was loaded from, and a baseline
          // comparison that ignored the role would read that as an edit —
          // leaving a message the user never typed in marked unsaved for good.
          await playground.hoverMessageRow(USER_ROW);
          await expect(
            playground.unsavedChangesDot(USER_ROW),
            'a role switch is not an edit, so there is nothing unsaved',
          ).toHaveCount(0);
          expect(await playground.saveTooltipText(USER_ROW)).toBe(SAVE_TOOLTIP);
        });

        await test.step('The switch is silent, unlike a library load', async () => {
          // Asserted rather than assumed: the toast belongs to the load path,
          // and the role dropdown is a different component that never raises it.
          // Pinned because it is user-visible behaviour either way — a build
          // that started announcing a switch would be a change worth seeing.
          expect(
            await playground.recordedToasts(),
            'the role dropdown drops the media without a toast',
          ).toEqual([]);
          expect(
            (await playground.recordedToasts()).includes(MEDIA_DROPPED_TOAST),
            'and in particular not the load path\'s own toast',
          ).toBe(false);
        });

        await test.step('The library copy keeps its media', async () => {
          const stored = await backendClient.getPromptVersion(mediaPrompt.version.id);
          expect(stored.template, 'the version still has its template').toBeDefined();
          const content = (
            JSON.parse(stored.template as string) as Array<{ content: unknown }>
          )[0].content;
          expect(
            content,
            'the strip is client-side only — the stored version is unchanged',
          ).toEqual(MEDIA_PROMPT_CONTENT);
        });

        await test.step('Positive control: an edit does flag the message', async () => {
          await playground.fillMessageAt(USER_ROW, `${MEDIA_PROMPT_TEXT} and be brief.`);
          await expect(
            playground.unsavedChangesDot(USER_ROW),
            'the dot the assertion above looked for exists and can appear',
          ).toHaveCount(1);
          expect(await playground.saveTooltipText(USER_ROW)).toBe(UNSAVED_TOOLTIP);
        });
      },
    );
  },
);
