import { test, expect } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * The compare Configuration tab's "Prompt version (linked)" row (OPIK-7964,
 * opik#8468).
 *
 * The row is synthetic — it is prepended to the metadata rows rather than read
 * from the experiment's metadata — and it is the only one whose cells render
 * navigable tags instead of text. Every failure mode it has is a quiet one: a
 * version attributed to the wrong experiment, a link to a deleted prompt that
 * still looks clickable, or a diff computed on labels (which collide) rather
 * than on version ids (which do not). None of those break the page.
 */
test.describe('Experiments compare — linked prompt versions', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  const PROMPT_ROW = 'Prompt version (linked)';

  test('each experiment column carries only its own linked prompt version', { tag: ['@cap:experiments.configuration-tab'] }, async ({
    comparePromptVersions,
    page,
  }) => {
    const seed = comparePromptVersions;
    const compare = new CompareExperimentsPage(page, seed.projectId, seed.datasetId, [
      seed.linkedToAlpha.id,
      seed.linkedToBeta.id,
      seed.linkedToNothing.id,
    ]);

    await test.step('Open the Configuration tab for three experiments', async () => {
      await compare.gotoConfiguration();
      await compare.expectExperimentColumnsInConfiguration([
        seed.linkedToAlpha,
        seed.linkedToBeta,
        seed.linkedToNothing,
      ]);
    });

    await test.step('The prompt version row leads the table, above the metadata rows', async () => {
      const names = await compare.configRowNames();
      expect(names[0], 'first Configuration row').toBe(PROMPT_ROW);
      // The metadata rows have to be there for "first" to mean anything — a
      // table holding only the synthetic row would satisfy the line above
      // however it was ordered.
      expect(names, 'the metadata rows render alongside it').toEqual(
        expect.arrayContaining(['model', 'run.seed']),
      );
    });

    await test.step("Each column holds exactly its own experiment's version, and neither holds the other's", async () => {
      const alphaTags = await compare.configCellPromptTags(PROMPT_ROW, seed.linkedToAlpha.id);
      await expect(alphaTags, 'prompt tags in the alpha-linked column').toHaveCount(1);
      await expect(alphaTags).toHaveText(seed.alphaV1.label);

      const betaTags = await compare.configCellPromptTags(PROMPT_ROW, seed.linkedToBeta.id);
      await expect(betaTags, 'prompt tags in the beta-linked column').toHaveCount(1);
      await expect(betaTags).toHaveText(seed.betaV1.label);

      // Stated the other way round as well: a cell that rendered BOTH versions
      // would already have failed the counts above, but a cell that rendered
      // the wrong single one would not.
      expect(
        await compare.configCellText(PROMPT_ROW, seed.linkedToAlpha.id),
        'the alpha column must not leak beta',
      ).not.toContain(seed.betaV1.promptName);
      expect(
        await compare.configCellText(PROMPT_ROW, seed.linkedToBeta.id),
        'the beta column must not leak alpha',
      ).not.toContain(seed.alphaV1.promptName);
    });

    await test.step('The experiment linked to no prompt reads "No value"', async () => {
      expect(
        await compare.configCellText(PROMPT_ROW, seed.linkedToNothing.id),
        'the unlinked column',
      ).toBe('No value');
      const noTags = await compare.configCellPromptTags(PROMPT_ROW, seed.linkedToNothing.id);
      await expect(noTags, 'tags in the unlinked column').toHaveCount(0);
    });

    await test.step('Each tag links to that exact prompt version', async () => {
      const alphaTag = (await compare.configCellPromptTags(PROMPT_ROW, seed.linkedToAlpha.id)).first();
      const href = await alphaTag.getAttribute('href');
      expect(href, 'href of the alpha prompt tag').toContain(`/prompts/${seed.alphaV1.promptId}`);
      expect(href, 'the tag opens the linked version, not the prompt head').toContain(
        `activeVersionId=${seed.alphaV1.versionId}`,
      );
    });

    await test.step('Searching the row name narrows the table to that one row', async () => {
      await compare.searchConfiguration('Prompt version');
      expect(await compare.configRowNames(), 'rows left after the search').toEqual([PROMPT_ROW]);
    });
  });

  test('a deleted linked prompt renders the disabled deleted state, and the link survives the delete', { tag: ['@cap:experiments.configuration-tab'] }, async ({
    comparePromptVersions,
    backendClient,
    page,
  }) => {
    const seed = comparePromptVersions;
    const compare = new CompareExperimentsPage(page, seed.projectId, seed.datasetId, [
      seed.orphaned.id,
      seed.liveNeighbour.id,
    ]);

    await test.step('The API still reports the link, with no prompt_name at all', async () => {
      const read = await backendClient.getExperimentPromptVersionsRaw(seed.orphaned.id);
      expect(read.status, `GET the orphaned experiment: ${read.message}`).toBe(200);
      expect(read.promptVersions, 'prompt_versions survived the prompt delete').not.toBeNull();
      expect(read.promptVersions, 'exactly the one seeded link').toHaveLength(1);

      const link = read.promptVersions![0];
      expect(link.id, 'the surviving link is the seeded version').toBe(seed.deletedVersionId);
      // The whole contract the render rests on: the frontend infers deleted-ness
      // from `isUndefined(prompt_name)`, which a JSON null does NOT satisfy. A
      // backend that started sending null here would turn the tag below into an
      // enabled link with an empty label and a "Go to prompt: null" tooltip.
      expect(
        Object.keys(link),
        'prompt_name is omitted, not null',
      ).not.toContain('prompt_name');
    });

    await test.step('Open the Configuration tab alongside a live-prompt experiment', async () => {
      await compare.gotoConfiguration();
      await compare.expectExperimentColumnsInConfiguration([seed.orphaned, seed.liveNeighbour]);
    });

    await test.step('The orphaned cell renders one disabled "Deleted prompt" tag', async () => {
      const tags = await compare.configCellPromptTags(PROMPT_ROW, seed.orphaned.id);
      await expect(tags, 'tags in the orphaned column').toHaveCount(1);
      await expect(tags, 'the orphaned tag names the deleted state').toHaveText('Deleted prompt');
      expect(
        await compare.tagTooltipText(tags.first()),
        'tooltip of the orphaned tag',
      ).toBe('Deleted prompt');
      await compare.expectTagNotNavigable(tags.first(), 'the deleted prompt tag');
    });

    await test.step('The neighbouring live prompt is untouched by it', async () => {
      const live = await compare.configCellPromptTags(PROMPT_ROW, seed.liveNeighbour.id);
      await expect(live, 'tags in the live column').toHaveCount(1);
      await expect(live).toHaveText(seed.betaV1.label);
      await expect(live, 'the live tag is still navigable').toHaveAttribute('href', /\/prompts\//);
    });

    await test.step('The same experiment alone shows the deleted state in both places it renders', async () => {
      const single = new CompareExperimentsPage(page, seed.projectId, seed.datasetId, [
        seed.orphaned.id,
      ]);
      await single.gotoConfiguration();

      // Two, exactly: outside compare mode the prompt appears once in the page
      // header and once as a tag beside the Configuration search box, and the
      // experiment has one linked version. Counting them pins both renders at
      // once — a build where either regressed to an enabled, empty-labelled
      // link would no longer say "Deleted prompt" and the count would drop.
      await expect(
        single.deletedPromptTags(),
        'deleted-prompt tags on the single-experiment page',
      ).toHaveCount(2);

      for (const index of [0, 1]) {
        await single.expectTagNotNavigable(
          single.deletedPromptTags().nth(index),
          `deleted prompt tag ${index}`,
        );
      }

      await test.step('…and the synthetic compare row is not rendered outside compare mode', async () => {
        await single.expectConfigRowAbsent(PROMPT_ROW);
      });
    });
  });

  test('"Show differences only" hides the prompt row for matching versions and keeps it for diverging ones', { tag: ['@cap:experiments.configuration-tab'] }, async ({
    comparePromptVersions,
    page,
  }) => {
    const seed = comparePromptVersions;

    // Outside the diff view each cell renders one TAG per linked version, in
    // the order `sortPromptVersions` puts them — by label, so alpha before
    // beta. The joined string below is what the cell falls back to inside the
    // diff view, where `TextDiff` compares the two experiments' labels as text.
    const pairTags = [seed.alphaV1.label, seed.betaV1.label];
    const pairLabel = pairTags.join(', ');
    const divergedLabel = `${seed.alphaV2.label}, ${seed.betaV1.label}`;

    await test.step('Two experiments on the same versions read identically', async () => {
      const same = new CompareExperimentsPage(page, seed.projectId, seed.datasetId, [
        seed.pairBaseline.id,
        seed.pairIdentical.id,
      ]);
      await same.gotoConfiguration();

      await expect(
        await same.configCellPromptTags(PROMPT_ROW, seed.pairBaseline.id),
        'baseline cell tags',
      ).toHaveText(pairTags);
      await expect(
        await same.configCellPromptTags(PROMPT_ROW, seed.pairIdentical.id),
        'the identically-linked cell tags',
      ).toHaveText(pairTags);

      await test.step('and the row disappears when only differences are shown', async () => {
        expect(
          await same.configRowNames(),
          'every row is on the table before the toggle',
        ).toEqual([PROMPT_ROW, 'model', 'run.seed']);

        await same.setShowDifferencesOnly(true);

        // The whole row set, not just the absence of the prompt row: the
        // toggle must hide the prompt row and the matching `run.seed`, and
        // must keep the differing `model`. Asserting only that the prompt row
        // went would pass equally well on a toggle that emptied the table.
        expect(await same.configRowNames(), 'rows left under the toggle').toEqual(['model']);
      });
    });

    await test.step('Changing one version brings the row back, as a diff', async () => {
      const diverged = new CompareExperimentsPage(page, seed.projectId, seed.datasetId, [
        seed.pairBaseline.id,
        seed.pairDiverged.id,
      ]);
      await diverged.gotoConfiguration({ diff: true });

      await expect(
        diverged.showDifferencesOnlyToggle,
        'the view arrived already filtered',
      ).toHaveAttribute('aria-checked', 'true');
      // Exactly these: the prompt row is back because the versions differ, and
      // `run.seed` is still hidden because it still matches — the filter is
      // deciding per row, not giving up on the whole table.
      expect(
        await diverged.configRowNames(),
        'rows under the differences-only filter',
      ).toEqual([PROMPT_ROW, 'model']);

      await expect(
        await diverged.configCellPromptTags(PROMPT_ROW, seed.pairBaseline.id),
        'the baseline column still renders its own tags, undiffed',
      ).toHaveText(pairTags);

      const sides = await diverged.configCellDiffSides(PROMPT_ROW, seed.pairDiverged.id);
      expect(sides.removed, 'the baseline value, struck out').toEqual([pairLabel]);
      expect(sides.added, 'the diverging value, added').toEqual([divergedLabel]);
    });
  });
});
