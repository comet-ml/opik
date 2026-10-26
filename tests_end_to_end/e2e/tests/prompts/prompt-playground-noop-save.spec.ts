import { test, expect } from '../../fixtures/chat-prompt-versions.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PromptsPage } from '@e2e/pom/prompts.page';
import { PromptDetailPage } from '@e2e/pom/prompt-detail.page';

/**
 * OPIK-8766 / opik#8923 — saving the LATEST version of a prompt back to the
 * library with no edit in between must not grow its version history. The
 * backend compares an incoming version against the latest and answers with that
 * existing version instead of committing a duplicate.
 *
 * The sibling `prompt-playground-save.spec.ts` drives the opposite axis: it
 * always edits before saving and expects a new version. Nothing there would
 * notice if the dedup stopped firing, because an edited save is supposed to
 * commit — which is why this spec exists rather than a case being added there.
 *
 * Asserted on BOTH surfaces the change reaches, because they can disagree and
 * each alone is satisfied by a failure of the other:
 *
 *  - the save's own response, where a deduped save and a fresh commit are told
 *    apart ONLY by `id`/`commit`/`version_number` (both answer 200);
 *  - the prompt detail page's version timeline and the library row's Versions
 *    count, which is what a user would actually read — a server that deduped
 *    correctly while the page rendered a phantom v4 is still the bug a reader of
 *    this ticket cares about.
 */
test.describe(
  'Playground → save an unchanged latest version',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    // The version timeline is the detail page's right sidebar, which only
    // renders at the xl breakpoint — below it the versions collapse into a
    // dropdown instead.
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'saving the latest chat version with no edit returns that same version and does not grow the timeline',
      { tag: ['@cap:playground.save-prompt-version'] },
      async ({ chatPromptVersions, project, page, providerKeys, backendClient, testNamespace }) => {
        test.setTimeout(90_000);

        const latest = chatPromptVersions.latest;

        await test.step('The prompt starts at exactly three versions, v3 newest (seed shape)', async () => {
          // Read through the API before any browser opens: every assertion below
          // is about a count NOT growing, and a prompt that never reached three
          // versions would satisfy all of them while verifying nothing.
          const newestFirst = await backendClient.listPromptVersions(chatPromptVersions.id);
          expect(newestFirst.map((v) => v.versionNumber)).toEqual(['v3', 'v2', 'v1']);
          expect(newestFirst[0].id, 'v3 is the latest committed version').toBe(latest.id);
          expect(newestFirst[0].commit, 'v3 commit as seeded').toBe(latest.commit);
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          // An unreachable base URL, as the sibling Playground specs use: the
          // page will not mount without a provider, and nothing here ever runs
          // a model, so a refused connection is the cheapest way to satisfy it.
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Pre-initialize Playground (set lastActiveProjectId)', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await playground.loadPromptVersionFromLibrary(chatPromptVersions.name, 'v3');

        await test.step('Playground shows the prompt loaded at v3', async () => {
          await playground.waitForReady();
          await playground.waitForLoadedPromptVersion(chatPromptVersions.name, 'v3');
        });

        // No edit of any kind between the load and the save — that is the whole
        // scenario, so there is deliberately no step here.
        const saved = await test.step(
          'Save back to the library and read what the server answered',
          async () => playground.savePromptToLibraryCapturingVersion(),
        );

        await test.step('The save returned the existing v3 rather than committing a new version', async () => {
          // id AND commit AND version_number: `id` alone would also be satisfied
          // by a server that re-committed under the same id, and
          // `version_number` alone by one that renumbered. Together they say the
          // response really is the version that was already stored.
          expect(saved.id, 'the response carries the seeded v3 id').toBe(latest.id);
          expect(saved.commit, 'the response carries the seeded v3 commit').toBe(latest.commit);
          expect(saved.version_number, 'the response is still labelled v3').toBe('v3');
        });

        await test.step('Server-side the prompt still holds exactly three versions', async () => {
          // The whole list, not just "v4 is absent": a save that committed a
          // duplicate under an existing label would leave four rows while every
          // label assertion still passed.
          const afterSave = await backendClient.listPromptVersions(chatPromptVersions.id);
          expect(afterSave).toHaveLength(3);
          expect(afterSave.map((v) => v.versionNumber)).toEqual(['v3', 'v2', 'v1']);
          expect(afterSave.map((v) => v.id)).toEqual(
            [...chatPromptVersions.versions].reverse().map((v) => v.id),
          );
        });

        const detail = new PromptDetailPage(page);

        await test.step("The detail page's version timeline still reads v3, v2, v1", async () => {
          await detail.goto(project.id, chatPromptVersions.id);
          await detail.waitForReady();

          await expect(detail.versionTimelineItems()).toHaveCount(3);
          expect(await detail.readVersionTimelineLabels()).toEqual(['v3', 'v2', 'v1']);
          await expect(detail.activeVersionLabel()).toHaveText('v3');
        });

        await test.step("The library row's Versions cell still reads 3", async () => {
          const prompts = new PromptsPage(page);
          await prompts.goto(project.id);
          await prompts.waitForReady();

          // Anchored and exact: a substring match on "3" would also be satisfied
          // by a cell reading 13 or 30.
          await expect(prompts.promptVersionCountCell(chatPromptVersions.id)).toHaveText(/^\s*3\s*$/);
        });
      },
    );
  },
);
