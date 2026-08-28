import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Attachments on a trace: the MIME type the backend derives for an upload, and
 * the thumbnails the trace panel renders for it.
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
});
