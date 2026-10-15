import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * How the trace panel classifies an attachment whose mime type is NOT one of the
 * ten in `MINE_TYPE_TO_ATTACHMENT_TYPE_MAP`.
 *
 * `trace-attachments.spec.ts` covers the sibling question — the type tika derives
 * when the caller omits one — and every type it produces is already in that
 * lookup table. OPIK-4954 added two fall-throughs underneath it: a well-formed
 * type outside the table is classified by its TOP-LEVEL type (`image/avif` is an
 * image), and the match is case-insensitive per RFC 2045 (`IMAGE/PNG` is too).
 * Neither branch is reachable from the sibling spec's seed.
 *
 * The classifier is shared code (`constants/attachments.ts`), so this also
 * guards the experiment-side consumer that `experiment-compare-image-output.spec.ts`
 * drives — an experiments-labelled PR changed how the trace sidebar classifies.
 *
 * The octet-stream file is the control, and it is what makes the other three
 * rows a claim: "all four rendered as images" and "the classifier returns IMAGE
 * unconditionally" are the same observation without it.
 *
 * Deterministic: classification is a pure function of a string the fixture
 * declares, and the fixture refuses to hand over a seed whose declared types did
 * not reach storage verbatim.
 */

test.describe('Trace attachments — mime classification', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  /** Four uploads, each a start/PUT/complete round trip, seeded before the test. */
  test.slow();

  test(
    'a declared mime type is stored verbatim rather than re-derived from the file name',
    { tag: ['@cap:traces.attachments-media'] },
    async ({ attachmentMimeTypes, backendClient }) => {
      const listed = await test.step('Read the attachments back', async () => {
        return backendClient.listAttachments({
          projectId: attachmentMimeTypes.projectId,
          entityType: 'trace',
          entityId: attachmentMimeTypes.traceId,
        });
      });

      await test.step('The trace carries exactly the uploaded files', async () => {
        expect(listed.map((a) => a.fileName).sort(), 'file names on the trace').toEqual(
          attachmentMimeTypes.files.map((f) => f.fileName).sort(),
        );
      });

      await test.step('Each file kept the exact type it was uploaded with, case included', async () => {
        // Case is the assertion, not an incidental detail: the backend must
        // store `IMAGE/PNG` as sent. A normalising write would still let the UI
        // classify it as an image and would still pass every render assertion
        // below, while quietly losing what the caller declared.
        const byName = new Map(listed.map((a) => [a.fileName, a]));
        for (const file of attachmentMimeTypes.files) {
          const found = byName.get(file.fileName);
          // Asserted present before being compared, so an absent entry fails
          // here rather than making an optional-chained comparison vacuous.
          expect(found, `${file.fileName} is listed`).toBeDefined();
          expect(found!.mimeType, `stored mime_type for ${file.fileName} (${file.why})`).toBe(
            file.sentMimeType,
          );
        }
      });

      await test.step('No file fell back to a name-derived type', async () => {
        // Every seeded name carries an extension that resolves to nothing, so a
        // build that dropped the declared type on the wire would answer
        // application/octet-stream for all four. Exactly one row is allowed to
        // read that way, and only because it declared it.
        const octetStream = listed
          .filter((a) => a.mimeType === 'application/octet-stream')
          .map((a) => a.fileName);
        expect(octetStream, 'files stored as application/octet-stream').toEqual(
          attachmentMimeTypes.files
            .filter((f) => f.sentMimeType === 'application/octet-stream')
            .map((f) => f.fileName),
        );
      });
    },
  );

  test(
    'types outside the lookup table classify by top-level type and case-insensitively',
    { tag: ['@cap:traces.attachments-media'] },
    async ({ attachmentMimeTypes, project, page }) => {
      const logs = new LogsPage(page);

      const panel = await test.step('Open the trace', async () => {
        await logs.goto(project.id);
        const panel = await logs.openTraceById(attachmentMimeTypes.traceId);
        await panel.waitForFullyLoaded();
        return panel;
      });

      await test.step('The Attachments section is present', async () => {
        await panel.openAttachments();
        await expect(panel.attachmentsSection).toBeVisible();
      });

      await test.step('Every uploaded file is listed, whatever it classified as', async () => {
        // A classifier that threw on an unrecognised type would drop the whole
        // list, so the count is asserted before the per-file branches.
        for (const file of attachmentMimeTypes.files) {
          await expect(
            panel.attachmentThumbnail(file.fileName),
            `one tile for ${file.fileName}`,
          ).toHaveCount(1);
          await expect(
            panel.attachmentLabel(file.fileName),
            `${file.fileName} is labelled in the panel`,
          ).toBeVisible();
        }
      });

      await test.step('Each file renders the branch its declared type selects', async () => {
        for (const file of attachmentMimeTypes.files) {
          if (file.rendersAsImage) {
            await expect(
              panel.attachmentGenericIcon(file.fileName),
              `${file.fileName} (${file.sentMimeType}) must not fall back to a file icon — ${file.why}`,
            ).toHaveCount(0);
            await panel.expectAttachmentDecodes(file.fileName);
          } else {
            await expect(
              panel.attachmentImage(file.fileName),
              `${file.fileName} (${file.sentMimeType}) must not render as an image — ${file.why}`,
            ).toHaveCount(0);
            await expect(
              panel.attachmentGenericIcon(file.fileName),
              `generic file icon for ${file.fileName} — ${file.why}`,
            ).toHaveCount(1);
          }
        }
      });

      await test.step('Exactly the image-typed files rendered as images', async () => {
        // The per-file checks above are each scoped to one tile, so on their own
        // they cannot see a control that rendered a picture under some other
        // label. Counting every image in the section closes that gap: three
        // images and four attachments is a different claim from three passing
        // tiles.
        await expect(panel.attachmentImages, 'attachment tiles rendered as images').toHaveCount(
          attachmentMimeTypes.files.filter((f) => f.rendersAsImage).length,
        );
      });
    },
  );
});
