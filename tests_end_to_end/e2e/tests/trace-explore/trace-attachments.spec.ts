import { createHash } from 'node:crypto';
import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Attachments on a trace: the MIME type the backend derives for an upload, the
 * bytes the object store gives back for it, and the thumbnails the trace panel
 * renders for it.
 *
 * Nothing in the estate read an attachment back before this, so the whole
 * upload → list → render path was unasserted. The type derivation is the part
 * worth pinning: `AttachmentService.getMimeType` falls through to
 * `tika.detect(fileName)` whenever the caller omits `mime_type`, which makes
 * this the only reachable surface of the `tika-core` dependency — a bump to it
 * changes answers here and nowhere else visible.
 *
 * Deterministic by construction. Detection is by file *name*, so no byte
 * sniffing, no wall clock and no model output is involved; the same ten names
 * must produce the same ten answers on every run.
 *
 * The fixture uploads through the real presigned multipart flow
 * (`upload-start` → PUT → `upload-complete`) rather than the direct upload
 * endpoint, because direct upload is refused outright on an S3-backed
 * deployment — so this also covers the multipart path staying intact.
 *
 * The round-trip test is the one assertion here that reads the stored *object*
 * rather than the row describing it. Everything else — `file_name`,
 * `mime_type`, `file_size`, the thumbnail's presence — is served from the
 * backend's own record of the upload and stays exactly right when the bytes in
 * the bucket are truncated, corrupted or another file's. Downloading the link
 * and hashing what comes back is what makes that class of silent wrongness
 * fail, and it is the half of the path a presigning-client change (the AWS SDK
 * bump in 2.2.77) can break on its own.
 */

test.describe('Trace attachments', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  /** Ten uploads, each a start/PUT/complete round trip, seeded before the test. */
  test.slow();

  test(
    'an upload with no MIME type gets one derived from its file name',
    { tag: ['@cap:traces.attachments-media'] },
    async ({ traceAttachments, backendClient }) => {
      const listed = await test.step('Read the attachments back', async () => {
        return backendClient.listAttachments({
          projectId: traceAttachments.projectId,
          entityType: 'trace',
          entityId: traceAttachments.traceId,
        });
      });

      await test.step('The trace carries exactly the uploaded files', async () => {
        // The whole answer, not just that ours are in it: an extra row would
        // mean the list leaked another entity's attachments into this trace.
        expect(listed.map((a) => a.fileName).sort(), 'file names on the trace').toEqual(
          traceAttachments.files.map((f) => f.fileName).sort(),
        );
      });

      await test.step('Every file name resolved to its own MIME type', async () => {
        const byName = new Map(listed.map((a) => [a.fileName, a]));
        for (const file of traceAttachments.files) {
          const found = byName.get(file.fileName);
          // Asserted present before being compared: an absent entry would make
          // an optional-chained comparison pass having checked nothing.
          expect(found, `${file.fileName} is listed`).toBeDefined();
          expect(found!.mimeType, `mime_type for ${file.fileName}`).toBe(file.expectedMimeType);
          expect(found!.fileSize, `file_size for ${file.fileName}`).toBe(file.fileSize);
        }
      });

      await test.step('Only the extension with no known type falls back to octet-stream', async () => {
        // Without this the test would pass against a backend that answered
        // application/octet-stream to everything: nine correct types and one
        // correct fallback are different claims, and only together do they say
        // detection ran.
        const fallbacks = listed.filter((a) => a.mimeType === 'application/octet-stream');
        expect(fallbacks.map((a) => a.fileName), 'files that fell back to a default type').toEqual([
          'mystery.qqzz',
        ]);
      });
    },
  );

  test(
    'every attachment downloads back byte-identical to what was uploaded',
    { tag: ['@cap:traces.attachments-media'] },
    async ({ traceAttachments, backendClient }) => {
      const listed = await test.step('Read the attachments back', async () => {
        return backendClient.listAttachments({
          projectId: traceAttachments.projectId,
          entityType: 'trace',
          entityId: traceAttachments.traceId,
        });
      });

      await test.step('The seed can tell one stored object from another', async () => {
        // The comparison below is per file, against that file's own digest, so
        // it can only catch a link that served the wrong object while the ten
        // payloads really are ten different payloads. Asserted rather than
        // assumed: two seeds that drifted into identical content would leave
        // that half of the claim silently untested.
        const digests = new Set(traceAttachments.files.map((f) => f.sha256));
        expect(digests.size, 'distinct digests among the seeded files').toBe(
          traceAttachments.files.length,
        );
      });

      await test.step('Each link serves back exactly the bytes that were PUT to it', async () => {
        const byName = new Map(listed.map((a) => [a.fileName, a]));
        for (const file of traceAttachments.files) {
          const found = byName.get(file.fileName);
          expect(found, `${file.fileName} is listed`).toBeDefined();
          // Asserted, not optional-chained past: the list type allows a null
          // link, and skipping a file that came back without one would turn
          // "nothing to download" into a passing test.
          expect(found!.link, `${file.fileName} carries a download link`).not.toBeNull();

          const downloaded = await backendClient.downloadAttachment(found!.link!);
          expect(downloaded.byteLength, `downloaded size of ${file.fileName}`).toBe(file.fileSize);
          expect(
            createHash('sha256').update(downloaded).digest('hex'),
            `sha256 of the bytes served for ${file.fileName}`,
          ).toBe(file.sha256);
        }
      });
    },
  );

  test(
    'every attachment renders as a thumbnail in the trace panel',
    { tag: ['@cap:traces.attachments-media'] },
    async ({ traceAttachments, project, page }) => {
      const logs = new LogsPage(page);

      const panel = await test.step('Open the trace', async () => {
        await logs.goto(project.id);
        const panel = await logs.openTraceById(traceAttachments.traceId);
        await panel.waitForFullyLoaded();
        return panel;
      });

      await test.step('The Attachments section is present', async () => {
        // It renders nothing at all for a trace with no media, so its presence
        // is the first assertion and not merely a scoping step.
        await panel.openAttachments();
        await expect(panel.attachmentsSection).toBeVisible();
      });

      await test.step('Each uploaded file has exactly one thumbnail, labelled with its name', async () => {
        for (const file of traceAttachments.files) {
          await expect(
            panel.attachmentThumbnail(file.fileName),
            `one thumbnail for ${file.fileName}`,
          ).toHaveCount(1);
          await expect(
            panel.attachmentLabel(file.fileName),
            `${file.fileName} is labelled in the panel`,
          ).toBeVisible();
        }
      });
    },
  );

  test(
    'the image thumbnail decodes the bytes the browser fetched for it',
    { tag: ['@cap:traces.attachments-media'] },
    async ({ traceAttachments, project, page }) => {
      const image = traceAttachments.files.find((f) => f.expectedMimeType === 'image/png');
      // The seed is what makes this test possible at all: only an `image/*`
      // attachment renders an <img>, every other type renders a type icon
      // whose pixels say nothing about the stored object.
      expect(image, 'the seed carries an image attachment').toBeDefined();

      const logs = new LogsPage(page);

      const panel = await test.step('Open the trace', async () => {
        await logs.goto(project.id);
        const panel = await logs.openTraceById(traceAttachments.traceId);
        await panel.waitForFullyLoaded();
        return panel;
      });

      await test.step('Open the Attachments section', async () => {
        await panel.openAttachments();
        await expect(panel.attachmentsSection).toBeVisible();
      });

      await test.step('The rendered image is one the browser could decode', async () => {
        await expect(
          panel.attachmentImage(image!.fileName),
          `one <img> for ${image!.fileName}`,
        ).toHaveCount(1);

        // A decoded image reports a non-zero intrinsic size; one the browser
        // could not decode reports 0×0 and stays there, which is what it is
        // left with when the object behind the presigned URL is truncated,
        // corrupted, or not an image at all. `toBeVisible` would not notice — a
        // broken <img> still occupies its box.
        //
        // Polled because the decode is asynchronous, and on the smaller of the
        // two dimensions because that is the same claim as both being non-zero
        // while still failing with a number worth reading.
        await expect
          .poll(
            async () => {
              const { width, height } = await panel.decodedImageSize(image!.fileName);
              return Math.min(width, height);
            },
            {
              message: `smaller decoded dimension of ${image!.fileName}`,
              timeout: 30_000,
            },
          )
          .toBeGreaterThan(0);
      });
    },
  );
});
