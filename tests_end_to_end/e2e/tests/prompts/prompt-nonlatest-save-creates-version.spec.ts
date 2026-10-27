import { test, expect } from '../../fixtures/chat-prompt-versions.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PromptsPage } from '@e2e/pom/prompts.page';
import { PromptDetailPage } from '@e2e/pom/prompt-detail.page';

/**
 * The other half of opik#8923's semantics, and the half most easily broken by a
 * well-meant follow-up: the unchanged-save dedup compares against the LATEST
 * version ONLY. Re-saving an OLDER version's content unchanged is a rollback
 * commit, and it must still create a version.
 *
 * A change that widened the comparison to "any existing version" would pass
 * `prompt-playground-noop-save.spec.ts` exactly as it does today while silently
 * swallowing every rollback — so this spec is what makes that regression
 * visible. It is the mirror image of its sibling and shares its fixture; the two
 * are separate files because `tag_lint` allows one `@area:` per spec and they
 * assert capabilities in different areas.
 *
 * Tagged `@cap:prompts.edit-creates-version` rather than a playground key: what
 * is asserted is that a commit lands in the prompt library's version history,
 * read back on the library's own timeline and Versions count. The Playground is
 * only how the save is driven.
 */
test.describe(
  'Prompt library → an unchanged save of a non-latest version still commits',
  { tag: ['@t2-cuj', '@area:prompts'] },
  () => {
    // The version timeline is the detail page's right sidebar, which only
    // renders at the xl breakpoint — below it the versions collapse into a
    // dropdown instead.
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'saving v1 of a three-version chat prompt with no edit creates v4',
      { tag: ['@cap:prompts.edit-creates-version'] },
      async ({ chatPromptVersions, project, page, providerKeys, backendClient, testNamespace }) => {
        test.setTimeout(90_000);

        const [v1, , v3] = chatPromptVersions.versions;

        await test.step('The prompt starts at exactly three versions, and v1 is NOT the latest (seed shape)', async () => {
          // The precondition the whole scenario rests on: if v1 were the latest,
          // this would be the no-op case and the dedup would correctly swallow
          // the save, so the test would fail for a reason that is not a defect.
          const newestFirst = await backendClient.listPromptVersions(chatPromptVersions.id);
          expect(newestFirst.map((v) => v.versionNumber)).toEqual(['v3', 'v2', 'v1']);
          expect(newestFirst[0].id, 'v3 is the latest, not v1').toBe(v3.id);
          expect(v1.id, 'v1 is a distinct older version').not.toBe(v3.id);
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the Playground mounts', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        await test.step('Pre-initialize Playground (set lastActiveProjectId)', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await playground.loadPromptVersionFromLibrary(chatPromptVersions.name, 'v1');

        await test.step('Playground shows the prompt loaded at v1', async () => {
          await playground.waitForReady();
          await playground.waitForLoadedPromptVersion(chatPromptVersions.name, 'v1');
        });

        // No edit between the load and the save: the content being identical to
        // a version that already exists is exactly the case under test.
        const saved = await test.step(
          'Save back to the library and read what the server answered',
          async () => playground.savePromptToLibraryCapturingVersion(),
        );

        await test.step('The save committed a NEW version, not v1 and not v3', async () => {
          expect(saved.version_number, 'the commit is labelled v4').toBe('v4');
          // Against both of the versions it could wrongly have deduped onto:
          // v1, whose content this is, and v3, which the predicate compares
          // against. Asserting only "not v1" would miss a predicate that
          // matched the latest for the wrong reason.
          expect(saved.id, 'not v1 handed back').not.toBe(v1.id);
          expect(saved.id, 'not v3 handed back').not.toBe(v3.id);
          expect(
            chatPromptVersions.versions.map((v) => v.commit),
            'the commit is one the prompt did not already hold',
          ).not.toContain(saved.commit);
        });

        await test.step("Server-side the new version holds v1's content, as a rollback should", async () => {
          const afterSave = await backendClient.listPromptVersions(chatPromptVersions.id);
          expect(afterSave).toHaveLength(4);
          expect(afterSave.map((v) => v.versionNumber)).toEqual(['v4', 'v3', 'v2', 'v1']);
          // The point of a rollback commit: v4 must carry v1's template, not
          // v3's. A spec that only counted versions would pass on a save that
          // committed the wrong content.
          expect(afterSave[0].id).toBe(saved.id);
          expect(afterSave[0].template).toBe(v1.template);
        });

        const detail = new PromptDetailPage(page);

        await test.step("The detail page's version timeline reads v4, v3, v2, v1", async () => {
          await detail.goto(project.id, chatPromptVersions.id);
          await detail.waitForReady();

          await expect(detail.versionTimelineItems()).toHaveCount(4);
          expect(await detail.readVersionTimelineLabels()).toEqual(['v4', 'v3', 'v2', 'v1']);
        });

        await test.step("The library row's Versions cell reads 4", async () => {
          const prompts = new PromptsPage(page);
          await prompts.goto(project.id);
          await prompts.waitForReady();

          await expect(prompts.promptVersionCountCell(chatPromptVersions.id)).toHaveText(/^\s*4\s*$/);
        });
      },
    );
  },
);
