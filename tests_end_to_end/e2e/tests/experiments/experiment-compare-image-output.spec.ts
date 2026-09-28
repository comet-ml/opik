import { test, expect, mediaAlt } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * Inline images in an experiment item's output, resolved in the compare row
 * detail panel.
 *
 * `experiments-compare.spec.ts` already opens this panel, but it seeds text
 * outputs and asserts the output string, the score and the metric name — it
 * never opens an item carrying media, so the path OPIK-4954 rewrote was
 * unasserted on the experiments side.
 *
 * What is actually at stake is a MAPPING, not a render. The output text carries
 * `[image_N]` placeholders and the panel shows a row of thumbnails; the question
 * is whether token N names the picture the user is looking at. Both failure
 * modes here are silent — a placeholder resolving to the wrong picture renders
 * as a perfectly normal panel, and so does a missing one.
 *
 * The seed is built to make the mapping falsifiable. `extractPrefixedBase64Images`
 * walks `BASE64_PREFIXES_MAP` format by format, png before gif, so document order
 * and placeholder order DISAGREE for a mixed output: the PNGs take [image_0] and
 * [image_1] in one pass and the GIF sitting between them takes [image_2]. The
 * fix was to read each placeholder out of the extractor's own record
 * (`extractPlaceholderToken(item.name)`) instead of recomputing it from the
 * media array's index — and the array index is exactly what agrees with the
 * truth whenever a seed's formats are uniform. A single-format seed would
 * therefore pass against the bug.
 *
 * Deterministic by construction: the three images are fixed byte strings, so
 * every assertion compares a `src` against a data URL written down in full. No
 * wall clock, no model output, no pixel comparison.
 */

test.describe('Experiment compare — inline image output', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  /**
   * The fixture waits up to 120s for the seeded experiment to become queryable on
   * the compare API, which does not fit the 90s default budget — a comparison that
   * landed at, say, 100s would fail on a bare timeout before the fixture could say
   * what it was still waiting for. Same reason and same 120s budget as
   * `compare-export-all-rows.spec.ts`.
   */
  test.slow();

  test(
    'each placeholder in a mixed-format output resolves to its own picture',
    { tag: ['@cap:experiments.compare-row-detail'] },
    async ({ experimentImageOutput, project, page }) => {
      const seed = experimentImageOutput.mixed;
      const compare = new CompareExperimentsPage(page, project.id, experimentImageOutput.datasetId, [
        experimentImageOutput.experimentId,
      ]);

      await test.step('Open the row detail panel for the mixed-format item', async () => {
        await compare.gotoResults();
        await compare.waitForResultsReady();
        await compare.openRowPanel(seed.datasetItemId);
        // Deliberately NOT opening the Attachments section: it is defaultOpen in
        // the compare panel, so a click would collapse it and every thumbnail
        // assertion below would fail on an absent element.
      });

      await test.step('The output text is numbered by format, not by document order', async () => {
        // The trap this spec exists for. Document order is red-PNG, green-GIF,
        // blue-PNG, so a numbering that followed the text would read
        // "A:[image_0] B:[image_1] C:[image_2]".
        const text = await compare.readPanelOutputText();
        expect(text, 'output text with placeholders').toContain(seed.expectedText);
        // And the images really were lifted out of the text rather than left in
        // it: without this, an output that rendered its raw base64 AND a set of
        // thumbnails would satisfy every other assertion here.
        expect(text, 'raw base64 left in the rendered output').not.toContain(
          seed.rawOutput.slice(0, 48),
        );
      });

      await test.step('Every placeholder resolves to the picture it names', async () => {
        for (const [placeholder, url] of Object.entries(seed.expectedUrlByPlaceholder)) {
          await compare.expectThumbnailResolvesTo(placeholder, url);
          await compare.expectThumbnailDecodes(placeholder);
        }
      });

      await test.step('The panel shows those thumbnails and no others', async () => {
        // The whole answer, not just that ours are among them: an extra
        // thumbnail would mean the panel resolved media the output never
        // referenced, which the per-placeholder checks above cannot see.
        await expect(compare.panelMediaThumbnails, 'inline thumbnails in the panel').toHaveCount(
          seed.expectedThumbnailCount,
        );
        const alts = await compare.panelMediaThumbnails.evaluateAll((nodes) =>
          nodes.map((n) => n.getAttribute('alt') ?? ''),
        );
        expect(alts.sort(), 'the placeholders the panel rendered').toEqual(
          Object.keys(seed.expectedUrlByPlaceholder).map(mediaAlt).sort(),
        );
      });
    },
  );

  test(
    'a repeated image is numbered twice and shown once, and both tokens point at it',
    { tag: ['@cap:experiments.compare-row-detail'] },
    async ({ experimentImageOutput, project, page }) => {
      const seed = experimentImageOutput.repeated;
      const compare = new CompareExperimentsPage(page, project.id, experimentImageOutput.datasetId, [
        experimentImageOutput.experimentId,
      ]);

      await test.step('Open the row detail panel for the repeated-image item', async () => {
        await compare.gotoResults();
        await compare.waitForResultsReady();
        await compare.openRowPanel(seed.datasetItemId);
      });

      await test.step('The same image twice still gets two distinct placeholders', async () => {
        // The extractor deliberately does NOT deduplicate: collapsing repeats
        // here would shift every later index and make [image_n] name the wrong
        // picture in any output that mixed a repeat with other media.
        expect(await compare.readPanelOutputText(), 'output text with placeholders').toContain(
          seed.expectedText,
        );
      });

      await test.step('The list deduplicates by URL, so one thumbnail is shown', async () => {
        await expect(compare.panelMediaThumbnails, 'inline thumbnails in the panel').toHaveCount(
          seed.expectedThumbnailCount,
        );
      });

      await test.step('The surviving thumbnail carries the right picture', async () => {
        await compare.expectThumbnailResolvesTo('[image_0]', seed.expectedUrlByPlaceholder['[image_0]']);
        await compare.expectThumbnailDecodes('[image_0]');
      });
    },
  );
});
