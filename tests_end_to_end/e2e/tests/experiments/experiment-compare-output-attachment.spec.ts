import { test, expect } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * An UPLOADED attachment on the trace behind an experiment item, resolved in the
 * compare row-detail panel — and the project the panel asked for it under.
 *
 * `experiment-compare-image-output.spec.ts` already covers the other half of
 * OPIK-4954: images written INLINE into the output as base64. That half needs no
 * network at all, because a data URL carries its own bytes — which is exactly why
 * it cannot see the seam this spec exists for. An SDK-logged image does not reach
 * the output as bytes; it reaches it as an attachment, and the panel has to go and
 * ask for it.
 *
 * Asking is the part that was broken and the part that is easy to break again.
 * An experiment item cannot go through `useUnifiedMedia` — it has no project_id of
 * its own, and exposing a `trace_id` makes `isObjectSpan` misclassify it as a span
 * — so `useExperimentItemMedia` takes the trace explicitly and sources the
 * project_id from the EXPERIMENT. `Experiment.project_id` is optional in the FE
 * type, so the failure mode is not an error: the lookup is simply disabled
 * (`enabled: Boolean(projectId && traceId)`) and the strip renders empty. A user
 * sees an item that appears to have logged no picture.
 *
 * That is why the request itself is asserted and not only the rendered tile. A
 * tile proves the round trip worked today; the request parameters are what say
 * WHICH project the panel resolved, which is the axis the hook's own unit tests
 * mock out and the one that decides whether attachments appear at all.
 *
 * The media-free item is the control. Without it, a build that rendered an empty
 * Attachments strip on every row would satisfy every assertion the attached item
 * can make, and "the strip is there" would be worth nothing.
 */
test.describe(
  'Experiment compare — uploaded output attachments',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    /**
     * The fixture waits up to 120s for the seeded experiment to become queryable
     * on the compare API, which does not fit the 90s default budget. Same reason
     * and same budget as `experiment-compare-image-output.spec.ts`.
     */
    test.slow();

    test(
      "an attachment on the item's trace renders, looked up under the experiment's project",
      { tag: ['@cap:experiments.compare-row-detail'] },
      async ({ experimentOutputAttachment, project, page }) => {
        const seed = experimentOutputAttachment;
        const compare = new CompareExperimentsPage(page, project.id, seed.datasetId, [
          seed.experimentId,
        ]);

        /**
         * Every attachment lookup the panel issues, recorded at its cause.
         *
         * Registered before the navigation: the panel fires this on mount, so a
         * listener attached afterwards would race the very request under test and
         * report "no lookup" on a perfectly working build.
         */
        const attachmentReads: URL[] = [];
        page.on('request', (request) => {
          const url = new URL(request.url());
          if (url.pathname.endsWith('/v1/private/attachment/list')) {
            attachmentReads.push(url);
          }
        });

        await test.step('Open the row detail panel for the attachment-backed item', async () => {
          await compare.gotoResults();
          await compare.waitForResultsReady();
          await compare.openRowPanel(seed.attached.datasetItemId);
          // Deliberately NOT clicking the Attachments header: the section is
          // defaultOpen in this panel, so a click would collapse it and every
          // assertion below would fail on an absent element.
        });

        await test.step('The uploaded picture is on screen and decoded', async () => {
          await compare.expectAttachmentThumbnailDecodes(seed.attached.fileName);
        });

        await test.step('The output text is rendered unchanged beside it', async () => {
          // The attachment is an addition to the output, not a substitute for
          // it: a hook that swallowed the text while resolving the media would
          // still show a correct thumbnail.
          expect(
            await compare.readPanelOutputTextContaining(seed.attached.outputText),
            'the output text beside the attachment',
          ).toContain(seed.attached.outputText);
        });

        await test.step('That picture is the only thing the panel resolved', async () => {
          // The whole answer, not just that ours is in it. An extra tile means
          // the panel resolved media this item never had — most plausibly by
          // asking for the wrong entity — which the per-file check above cannot
          // see.
          await expect(
            compare.panelAllThumbnails,
            'media tiles in the panel for an item with exactly one attachment',
          ).toHaveCount(1);
        });

        await test.step(
          "Every attachment lookup named the experiment's project and the item's trace",
          async () => {
            // At least one, asserted before anything is read off the list: with
            // an empty list the per-request loop below would vacuously pass, and
            // "the panel never asked" is the precise shape of the regression.
            expect(
              attachmentReads.length,
              'the panel issued an attachment lookup for the open item',
            ).toBeGreaterThan(0);

            // Every one of them, not just one of them. A panel that asked under
            // the right project once and under `undefined` again would render
            // the same tile, and is one refactor away from asking only the wrong
            // way.
            const wrong = attachmentReads
              .map((url) => ({
                project_id: url.searchParams.get('project_id'),
                entity_id: url.searchParams.get('entity_id'),
                entity_type: url.searchParams.get('entity_type'),
              }))
              .filter(
                (q) =>
                  q.project_id !== seed.projectId ||
                  q.entity_id !== seed.attached.traceId ||
                  q.entity_type !== 'trace',
              );
            expect(
              wrong,
              'attachment lookups that did not name the experiment\'s project_id and the ' +
                "item's trace_id — the Experiment.project_id plumbing this panel depends on",
            ).toEqual([]);
          },
        );
      },
    );

    test(
      'an item whose output carries no media renders no Attachments section at all',
      { tag: ['@cap:experiments.compare-row-detail'] },
      async ({ experimentOutputAttachment, project, page }) => {
        const seed = experimentOutputAttachment;
        const compare = new CompareExperimentsPage(page, project.id, seed.datasetId, [
          seed.experimentId,
        ]);

        await test.step('Open the row detail panel for the media-free item', async () => {
          await compare.gotoResults();
          await compare.waitForResultsReady();
          await compare.openRowPanel(seed.plain.datasetItemId);
        });

        await test.step('The output reads exactly as it was logged', async () => {
          expect(
            await compare.readPanelOutputTextContaining(seed.plain.outputText),
            'the rendered output of a text-only item',
          ).toContain(seed.plain.outputText);
        });

        await test.step('There is no Attachments section and no tile', async () => {
          // `AttachmentsList` returns null rather than an empty container when it
          // resolved nothing, so the section is absent — which is a stronger and
          // more legible claim than "it is empty". Both are asserted: a section
          // header with no tiles and a tile with no header are different bugs.
          await expect(
            compare.panelAttachmentsSection,
            'an item with no media must render no Attachments section',
          ).toHaveCount(0);
          await expect(
            compare.panelAllThumbnails,
            'media tiles in the panel for an item with no media',
          ).toHaveCount(0);
        });
      },
    );
  },
);
