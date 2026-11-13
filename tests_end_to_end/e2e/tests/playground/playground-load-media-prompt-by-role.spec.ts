// `media-prompt.fixture` and not `@e2e/fixtures`: the seed is a prompt version
// whose stored content carries media parts, and `providerKeys` (without which
// the Playground does not mount past its "Add provider configuration" modal)
// lives on the same far end of the fixture chain. Same import shape as the
// other playground-plus-library specs.
import {
  test,
  expect,
  MEDIA_PROMPT_TEXT,
  MEDIA_PROMPT_CONTENT,
} from '../../fixtures/media-prompt.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * Loading a prompt whose content carries media into a message loads the whole
 * content into a USER message and only the text into a SYSTEM or ASSISTANT one,
 * says so once when it drops something, and does not flag the message as
 * unsaved either way (opik#8873).
 *
 * Providers accept only text in system and assistant messages, and only user
 * messages render media at all, so media loaded there was neither shown nor
 * usable — it sat in the message until a run dropped or rejected it.
 *
 * The axis the change added is the TARGET MESSAGE'S ROLE, and the role is
 * exactly what no existing spec varies: the only other spec tagged
 * `@cap:playground.load-prompt-version` loads text-only prompts into the
 * default User row, so it stays green on a build that ignores the role
 * completely. All three roles are driven here for the same reason the unit
 * tests cover all three — the rule is one `isMediaAllowedForRole` call, and the
 * User case is what proves the other two are a rule rather than a blanket strip.
 *
 * ## Why the store is read, and not just the screen
 *
 * On a system or assistant row the editor renders the content's text part
 * whether or not the media came with it, and the media tags never render there
 * at all (`showMediaActions` gates them on the user role). So "the media was
 * dropped" and "the media is still in the message, invisibly" look identical on
 * screen — and the second is the bug. `storedMessages()` reads the shape the
 * Playground actually holds, which is the only place the difference is
 * observable; the tags, the editor text and the toast are asserted alongside it
 * as what the user sees.
 *
 * ## Deterministic, with no provider involved
 *
 * Nothing here runs a prompt: the message rows work without a model, and the
 * unreachable provider is seeded only so the page mounts. No model, no network,
 * no LLM output.
 *
 * ## The absence assertions are not vacuous
 *
 * Every test ends by typing one phrase into the row it just loaded and
 * asserting that the unsaved-changes dot and the save tooltip BOTH flip. The
 * interesting half of this change is a silent one — that a load which drops
 * media is not then reported as an unsaved edit — and an absence asserted
 * through a locator that never matched would pass on any build.
 */

/** Both roles the drop applies to. The rule is per-role, so both are driven. */
const TEXT_ONLY_ROLES = ['System', 'Assistant'] as const;

/** `LLMPromptMessageActions`' toast, title and description as one string. */
const MEDIA_DROPPED_TOAST =
  'Media not loaded' +
  "Only user messages support images, video and audio, so only the prompt's text was loaded.";

/** `PROMPT_SAVE_TO_LIBRARY_TOOLTIP` — the message still matches its version. */
const SAVE_TOOLTIP = 'Save as text prompt';
/** `PROMPT_UNSAVED_TOOLTIP` — the message has diverged from its version. */
const UNSAVED_TOOLTIP = "This prompt version hasn't been saved";

/** The row the Playground's default card opens with, per role. */
const SYSTEM_ROW = 0;
const USER_ROW = 1;

const EDIT = 'and mention the weather.';

test.describe(
  'Playground — loading a prompt with media, by target message role',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'a User message keeps the text and every media part, with no toast',
      {
        tag: [
          '@cap:playground.load-prompt-version',
          '@cap:playground.message-role-media',
        ],
      },
      async ({ page, project, providerKeys, mediaPrompt, backendClient, testNamespace }) => {
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Open the Playground', async () => {
          // Before `goto`, which is what the recorder requires: it is an init
          // script, and the toast under test is raised by the load below.
          await playground.startRecordingToasts();
          await playground.goto();
          await playground.waitForReady();
          expect(
            await playground.messageRoleAt(USER_ROW),
            'the default card opens with a User row where this test expects one',
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

        await test.step('The message holds the whole content, media included', async () => {
          const messages = await playground.storedMessages();
          // The whole array and not just "it has four parts": a load that kept
          // the media but reordered or rewrote a part would satisfy a count.
          expect(
            messages[USER_ROW].content,
            'a user message is loaded exactly as the library stored it',
          ).toEqual(MEDIA_PROMPT_CONTENT);
          expect(
            messages[USER_ROW].role,
            'and the load did not change the row\'s role',
          ).toBe('user');
        });

        await test.step('All three media tags are on screen', async () => {
          await playground.hoverMessageRow(USER_ROW);
          for (const url of mediaPrompt.mediaUrls) {
            await expect(
              playground.mediaTag(USER_ROW, url),
              `the ${url} tag renders on the user message`,
            ).toHaveCount(1);
            await expect(playground.mediaTag(USER_ROW, url)).toBeVisible();
          }
        });

        await test.step('Nothing was dropped, so nothing was announced', async () => {
          // The whole recorded list, not a filtered one: the claim is that this
          // load raised NO toast, which a `filter` for the media toast could not
          // tell apart from a load that raised some other complaint.
          expect(
            await playground.recordedToasts(),
            'a user message loses nothing, so there is nothing to report',
          ).toEqual([]);
        });

        await test.step('The loaded version is not flagged as unsaved', async () => {
          await playground.hoverMessageRow(USER_ROW);
          await expect(
            playground.unsavedChangesDot(USER_ROW),
            'the message matches the version it was loaded from',
          ).toHaveCount(0);
          expect(await playground.saveTooltipText(USER_ROW)).toBe(SAVE_TOOLTIP);
        });

        await test.step('The library copy is untouched', async () => {
          // The read-back that makes this a both-surfaces test: the drop is a
          // client-side transform, so no load of any kind may rewrite the
          // stored version. Asserted for the User row too, because "the API
          // copy survived" is only evidence if it is checked where the UI did
          // keep the media as well as where it did not.
          const stored = await backendClient.getPromptVersion(mediaPrompt.version.id);
          expect(stored.template, 'the version still has its template').toBeDefined();
          const content = (
            JSON.parse(stored.template as string) as Array<{ content: unknown }>
          )[0].content;
          expect(content, 'the stored version still carries all four parts').toEqual(
            MEDIA_PROMPT_CONTENT,
          );
        });

        await test.step('Positive control: an edit does flag the message', async () => {
          await playground.fillMessageAt(USER_ROW, `${MEDIA_PROMPT_TEXT} ${EDIT}`);
          await expect(
            playground.unsavedChangesDot(USER_ROW),
            'the dot the assertions above looked for exists and can appear',
          ).toHaveCount(1);
          expect(await playground.saveTooltipText(USER_ROW)).toBe(UNSAVED_TOOLTIP);
        });
      },
    );

    for (const role of TEXT_ONLY_ROLES) {
      test(
        `${role} messages take only the text, say so once, and are not flagged unsaved`,
        {
          tag: [
            '@cap:playground.load-prompt-version',
            '@cap:playground.message-role-media',
          ],
        },
        async ({ page, project, providerKeys, mediaPrompt, backendClient, testNamespace }) => {
          const playground = new PlaygroundPage(page, project.id);

          await test.step('Give the workspace a provider so the Playground mounts', async () => {
            await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
          });

          await test.step('Open the Playground', async () => {
            await playground.startRecordingToasts();
            await playground.goto();
            await playground.waitForReady();
          });

          // The default card's first row is already a System row, so the System
          // case changes nothing and the Assistant case switches an EMPTY row —
          // which carries no media, so the switch itself cannot be what drops
          // anything. That separation is the point: this test is about the LOAD
          // path, and `playground-media-prompt-role-switch.spec.ts` is about the
          // role-change path.
          if (role === 'Assistant') {
            await test.step('Make the first row an Assistant message', async () => {
              await playground.setMessageRole(SYSTEM_ROW, role);
            });
          }
          expect(
            await playground.messageRoleAt(SYSTEM_ROW),
            `the row this test loads into is a ${role} message`,
          ).toBe(role);

          await test.step(`Load the media prompt into the ${role} row`, async () => {
            await playground.loadTextPromptVersionIntoMessage(
              SYSTEM_ROW,
              mediaPrompt.name,
              mediaPrompt.versionLabel,
            );
            await playground.waitForLoadedPromptVersionInMessage(
              SYSTEM_ROW,
              mediaPrompt.name,
              mediaPrompt.versionLabel,
            );
          });

          await test.step('Only the text landed, as a plain string', async () => {
            const messages = await playground.storedMessages();
            // The exact string, which is the whole claim: not an array with the
            // media stripped out of it, not the text with an empty part beside
            // it — the content a text-only message would have had all along.
            expect(
              messages[SYSTEM_ROW].content,
              `a ${role} message is loaded with the prompt's text and nothing else`,
            ).toBe(MEDIA_PROMPT_TEXT);
            expect(messages[SYSTEM_ROW].role, `the row is still a ${role} message`).toBe(
              role.toLowerCase(),
            );
          });

          await test.step('And the text is what the editor shows', async () => {
            // The user-visible half: the message is not merely stored right, it
            // reads as the prompt's text in the row that was loaded.
            const bodies = await playground.messageBodies();
            expect(bodies[SYSTEM_ROW], 'the editor renders the loaded text').toBe(
              MEDIA_PROMPT_TEXT,
            );
          });

          await test.step('No media tag renders in the row', async () => {
            await playground.hoverMessageRow(SYSTEM_ROW);
            for (const url of mediaPrompt.mediaUrls) {
              await expect(
                playground.mediaTag(SYSTEM_ROW, url),
                `no ${url} tag on a ${role} message`,
              ).toHaveCount(0);
            }
          });

          await test.step('The drop is announced exactly once', async () => {
            // Exactly one, read as the whole list: the toast is the only thing
            // that tells the user their media did not come with the prompt, and
            // one per media part (three) would be as wrong as none.
            expect(
              await playground.recordedToasts(),
              `one toast, naming what a ${role} message cannot take`,
            ).toEqual([MEDIA_DROPPED_TOAST]);
          });

          await test.step('The loaded version is NOT flagged as unsaved', async () => {
            // The silent half of the fix, and the regression the last push to
            // opik#8873 settled: the drop leaves the message different from the
            // version it came from, and comparing the two naively reports an
            // unsaved edit the user never made. `saveWarning` compares against
            // the baseline AS IT WOULD LOAD FOR THIS ROLE, so there is nothing
            // to report.
            await playground.hoverMessageRow(SYSTEM_ROW);
            await expect(
              playground.unsavedChangesDot(SYSTEM_ROW),
              'dropping media the role cannot hold is not an unsaved edit',
            ).toHaveCount(0);
            expect(await playground.saveTooltipText(SYSTEM_ROW)).toBe(SAVE_TOOLTIP);
          });

          await test.step('The library copy keeps its media', async () => {
            const stored = await backendClient.getPromptVersion(mediaPrompt.version.id);
            expect(stored.template, 'the version still has its template').toBeDefined();
            const content = (
              JSON.parse(stored.template as string) as Array<{ content: unknown }>
            )[0].content;
            expect(
              content,
              'the drop is client-side only — the stored version is unchanged',
            ).toEqual(MEDIA_PROMPT_CONTENT);
          });

          await test.step('Positive control: an edit does flag the message', async () => {
            await playground.fillMessageAt(SYSTEM_ROW, `${MEDIA_PROMPT_TEXT} ${EDIT}`);
            await expect(
              playground.unsavedChangesDot(SYSTEM_ROW),
              'the dot the assertion above looked for exists and can appear',
            ).toHaveCount(1);
            expect(await playground.saveTooltipText(SYSTEM_ROW)).toBe(UNSAVED_TOOLTIP);
          });
        },
      );
    }
  },
);
