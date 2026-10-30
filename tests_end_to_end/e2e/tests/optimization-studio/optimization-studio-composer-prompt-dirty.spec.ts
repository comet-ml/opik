import { test, expect } from '@e2e/fixtures/project-prompt-library.fixture';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';

const CLEAN_TOOLTIP = 'Save as text prompt';
const DIRTY_TOOLTIP = "This prompt version hasn't been saved";

/**
 * The shared message-row prompt library on the Optimization Studio new-run
 * composer — specifically the dirty check opik#8759 extracted into
 * `useMessageTextPrompt`.
 *
 * ## Why this surface, and why it is the only other one
 *
 * `LLMPromptMessageActions` computes `saveDisabled` itself rather than taking
 * it from its consumer, so the release report reasoned that the renamed tooltip
 * and the extracted hook land on all six surfaces mounting
 * `LLMPromptMessages`. The strip is gated one level up, though:
 * `LLMPromptMessages` takes `hidePromptActions`, defaulting to TRUE, and only
 * `PlaygroundPrompt.tsx` and `OptimizationsNewPromptSection.tsx` pass false.
 * `PromptTemplateEditor`, `NewBlueprintFieldEditor`, `AgentRunnerPromptCard`
 * and `BlueprintValuePrompt` (the traces Configuration tab) all pass
 * `hidePromptActions`, so neither the save button nor its tooltip renders
 * there at all. This composer is therefore the ONE off-Playground surface the
 * change reaches, and it had no e2e assertion of any kind.
 *
 * ## Why an OLDER version, and why that is the whole test
 *
 * The extraction is not behaviour-neutral. The old code compared the row
 * against `promptData?.latest_version` whenever the picked version had not
 * arrived yet; the hook now yields `undefined` and so reads CLEAN. That is
 * intended — the PR ships a unit test saying so — but it means the baseline a
 * row is judged against is the version the user PICKED, which is only
 * observable by picking one that is not the latest. So this loads v1 of a
 * two-version prompt and asserts the body really is v1's: a picker that
 * silently loaded the latest would make "clean" true for a reason that has
 * nothing to do with the hook.
 *
 * Both sides of the flag are asserted in one pass — clean while untouched,
 * dirty after one edit — because either alone is satisfied by a flag that is
 * stuck. Both are read twice over, through the amber dot and through the
 * tooltip, which are two renderings of the same `saveWarning`: a selector that
 * stopped matching shows up as the pair disagreeing rather than as a pass.
 *
 * NOT covered, and deliberately: what the row claims when the version fetch
 * never resolves or answers 500. That state reproduces (the query has no 404
 * opt-out, so it retries and settles errored, leaving the row reading "saved"
 * for good) but it is the PR's documented intent rather than an agreed defect —
 * a product call, not something to pin as correct behaviour. A prompt DELETED
 * from the library is a different path and is handled: `usePromptById` opts out
 * of retrying a 404 and the row detaches instead of going stale.
 */
test.describe(
  'Optimization Studio → the new-run composer\'s message-row prompt library',
  { tag: ['@t2-cuj', '@area:optimization-studio'] },
  () => {
    test.use({ viewport: { width: 1600, height: 1000 } });

    test(
      'an older loaded version reads clean, then flips to unsaved on one edit',
      { tag: ['@cap:optimization-studio.composer-prompt-library'] },
      async ({ page, project, promptLibrary }) => {
        test.setTimeout(150_000);

        const v1Body = 'You are a kind assistant.';
        const v2Body = 'You are a terse assistant.';
        const studio = new OptimizationStudioPage(page, project.id);

        const prompt = await test.step('Seed a text prompt with two committed versions', async () =>
          promptLibrary.seedTextPrompt({
            suffix: 'studio-text',
            role: 'user',
            bodies: [v1Body, v2Body],
          }));

        await test.step('Open the new-run composer', async () => {
          await studio.gotoNew();
          await expect(
            studio.composerMessageRow('user'),
            'the composer seeds exactly one user message card',
          ).toHaveCount(1);
        });

        await test.step('Load the OLDER version into the user row', async () => {
          await studio.loadTextPromptVersionIntoComposer('user', prompt.name, prompt.labels[0]);
          await expect
            .poll(async () => studio.composerMessageBody('user'), { timeout: 30_000 })
            .toBe(v1Body);
          // Stated rather than assumed: v1 and v2 differ, so the body above is
          // evidence that the PICKED version was applied and not the latest.
          expect(v1Body, 'the two seeded versions differ').not.toBe(v2Body);
        });

        await test.step('With v1 loaded and untouched, the row reads clean', async () => {
          await studio.expectComposerPromptChip('user', prompt.name);
          await expect(
            studio.composerUnsavedChangesDot('user'),
            'no unsaved dot on an untouched load',
          ).toHaveCount(0);
          expect(await studio.composerSaveTooltipText('user')).toBe(CLEAN_TOOLTIP);
        });

        await test.step('One edit flips it to unsaved', async () => {
          await studio.editComposerMessage('user', `${v1Body} Also be brief.`);
          await expect(
            studio.composerUnsavedChangesDot('user'),
            'the unsaved dot appears after an edit',
          ).toHaveCount(1);
          expect(await studio.composerSaveTooltipText('user')).toBe(DIRTY_TOOLTIP);
        });
      },
    );
  },
);
