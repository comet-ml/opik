import { test as baseTest } from './project-metric-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface AttachedFileSeed {
  fileName: string;
  /** What `tika.detect(fileName)` must answer for this name. */
  expectedMimeType: string;
  fileSize: number;
}

export interface TraceAttachmentsRef {
  traceId: string;
  projectId: string;
  files: AttachedFileSeed[];
}

export interface TraceAttachmentsFixtures {
  traceAttachments: TraceAttachmentsRef;
}

/**
 * A 1x1 transparent PNG. The only file here whose *bytes* matter: the trace
 * panel renders an `image/*` attachment as a real `<img>`, and a thumbnail that
 * fails to decode would make the UI assertion read a broken element.
 */
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
);

/**
 * One file per detection branch worth pinning, with the MIME type the backend
 * must derive from the *name*.
 *
 * Content is deliberately not representative — `AttachmentService.getMimeType`
 * falls through to `tika.detect(fileName)` when the caller omits `mime_type`,
 * so nothing here sniffs bytes. `.qqzz` is the control: without a name that
 * resolves to nothing, "every file got a type" would be satisfied by a backend
 * that answered `application/octet-stream` to all ten.
 */
const FILES: Array<{ fileName: string; expectedMimeType: string; content: Buffer<ArrayBuffer> }> = [
  { fileName: 'report.pdf', expectedMimeType: 'application/pdf', content: Buffer.from('%PDF-1.4 seeded', 'utf-8') },
  { fileName: 'photo.png', expectedMimeType: 'image/png', content: PNG_1X1 },
  { fileName: 'notes.txt', expectedMimeType: 'text/plain', content: Buffer.from('seeded notes', 'utf-8') },
  { fileName: 'data.csv', expectedMimeType: 'text/csv', content: Buffer.from('a,b\n1,2\n', 'utf-8') },
  { fileName: 'archive.zip', expectedMimeType: 'application/zip', content: Buffer.from('PK seeded', 'utf-8') },
  { fileName: 'audio.mp3', expectedMimeType: 'audio/mpeg', content: Buffer.from('ID3 seeded', 'utf-8') },
  { fileName: 'movie.mp4', expectedMimeType: 'video/mp4', content: Buffer.from('ftyp seeded', 'utf-8') },
  {
    fileName: 'sheet.xlsx',
    expectedMimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    content: Buffer.from('PK seeded xlsx', 'utf-8'),
  },
  {
    fileName: 'doc.docx',
    expectedMimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    content: Buffer.from('PK seeded docx', 'utf-8'),
  },
  {
    fileName: 'mystery.qqzz',
    expectedMimeType: 'application/octet-stream',
    content: Buffer.from('seeded unknown', 'utf-8'),
  },
];

/**
 * One trace carrying ten attachments uploaded through the real presigned
 * multipart flow, every one of them with `mime_type` omitted.
 *
 * Omitting the type is the entire point: it is what makes the backend call
 * `tika.detect(fileName)`, which is the only surface of the tika dependency
 * reachable from the public API. Uploading through `upload-start` → PUT →
 * `upload-complete` rather than the direct `PUT /attachment/upload` is not a
 * detail either — direct upload is refused outright on an S3-backed deployment.
 *
 * Teardown deletes the attachments explicitly. Deleting the owning trace would
 * cascade to them, but this fixture does not own the trace's lifecycle, and the
 * project delete that does own it leaves traces (and so their attachments)
 * behind.
 */
export const test = baseTest.extend<TraceAttachmentsFixtures>({
  traceAttachments: async ({ backendClient, opikTrace, project, testNamespace }, use, testInfo) => {
    for (const file of FILES) {
      await backendClient.uploadAttachment({
        projectName: project.name,
        entityType: 'trace',
        entityId: opikTrace.id,
        fileName: file.fileName,
        content: file.content,
        // mimeType intentionally omitted — see the note above.
      });
    }

    const ref: TraceAttachmentsRef = {
      traceId: opikTrace.id,
      projectId: project.id,
      files: FILES.map((f) => ({
        fileName: f.fileName,
        expectedMimeType: f.expectedMimeType,
        fileSize: f.content.byteLength,
      })),
    };

    // Prove the seed took before any test reads it. A silently-empty upload
    // would otherwise leave a UI assertion with nothing to find, which reads as
    // a rendering defect rather than as the setup failure it is.
    const listed = await backendClient.listAttachments({
      projectId: project.id,
      entityType: 'trace',
      entityId: opikTrace.id,
    });
    if (listed.length !== FILES.length) {
      throw new Error(
        `[traceAttachments fixture] expected ${FILES.length} attachments on trace ` +
          `${opikTrace.id}, the API lists ${listed.length}: ${listed.map((a) => a.fileName).join(', ')}`,
      );
    }

    await testInfo.attach('opik.traceAttachments', {
      body: JSON.stringify({ ...ref, namespace: testNamespace }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteAttachments({
          projectId: project.id,
          entityType: 'trace',
          entityId: opikTrace.id,
          fileNames: FILES.map((f) => f.fileName),
        });
      } catch (err) {
        console.warn(`[traceAttachments fixture] delete warning for trace ${opikTrace.id}:`, err);
      }
    }
  },
});

export { expect } from './project-metric-spans.fixture';
