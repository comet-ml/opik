import { test as baseTest } from './experiment-image-output.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * A 1x1 red PNG — the bytes behind every file here, whatever type it is declared
 * as.
 *
 * Deliberately the same bytes for all four: this fixture is about the mime type
 * the CALLER declares, not about what the bytes are. Giving the avif-typed files
 * genuine AVIF bytes would make the `<img>` assertions depend on the browser
 * shipping an AVIF decoder, which is a different (and flakier) claim than the one
 * `getAttachmentTypeByMimeType` makes. With PNG bytes throughout, every file the
 * classifier routes to an `<img>` decodes, so a thumbnail that fails to render is
 * unambiguously a classification failure.
 */
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR42mP4z8AAAAMBAQD3A0FDAAAAAElFTkSuQmCC',
  'base64',
);

export interface MimeTypedFileSeed {
  fileName: string;
  /** The mime type sent on BOTH upload requests, and so the one stored verbatim. */
  sentMimeType: string;
  /** Does `getAttachmentTypeByMimeType` route this to an `<img>`? */
  rendersAsImage: boolean;
  why: string;
}

export interface AttachmentMimeTypesRef {
  traceId: string;
  projectId: string;
  files: MimeTypedFileSeed[];
}

export interface AttachmentMimeTypesFixtures {
  attachmentMimeTypes: AttachmentMimeTypesRef;
}

/**
 * One file per branch OPIK-4954 added to the attachment classifier, plus the
 * control that makes them mean something.
 *
 * Every file name carries an extension tika resolves to NOTHING (`.qqzz`) or to
 * `application/octet-stream` (`.bin`). That is the load-bearing detail: if the
 * declared type were ever dropped on the way to storage, tika would re-derive
 * from the name and every row here would come back `application/octet-stream`,
 * so the stored-type assertion fails loudly instead of the render assertion
 * passing for the wrong reason. A name like `photo.PNG` would have hidden
 * exactly that, because tika answers `image/png` for it regardless.
 *
 * The octet-stream row is the negative control: without a file the classifier
 * must NOT call an image, "every attachment rendered as an image" would be
 * satisfied by a classifier that returned IMAGE unconditionally.
 */
const FILES: MimeTypedFileSeed[] = [
  {
    fileName: 'uppercase.qqzz',
    sentMimeType: 'IMAGE/PNG',
    rendersAsImage: true,
    why: 'in the lookup table, but only once lower-cased — the case-insensitive branch',
  },
  {
    fileName: 'mixedcase.bin',
    sentMimeType: 'Image/Avif',
    rendersAsImage: true,
    why: 'outside the lookup table AND mixed case — both new branches at once',
  },
  {
    fileName: 'lowercase.bin',
    sentMimeType: 'image/avif',
    rendersAsImage: true,
    why: 'outside the lookup table — classified by its top-level type alone',
  },
  {
    fileName: 'control.bin',
    sentMimeType: 'application/octet-stream',
    rendersAsImage: false,
    why: 'the negative control: in the table, and mapped to OTHER',
  },
];

/**
 * One trace carrying four attachments, each uploaded with an explicit
 * `mime_type` on both `upload-start` and `upload-complete`.
 *
 * The sibling `traceAttachments` fixture is the mirror image of this one — it
 * OMITS the type so tika derives it from the file name. Both paths exist in
 * `AttachmentService.getMimeType` and they are asserted separately.
 *
 * Teardown deletes the attachments explicitly: they cascade with the trace, but
 * this fixture does not own the trace's lifecycle, and the project delete that
 * does own it leaves traces (and so their attachments) behind.
 */
export const test = baseTest.extend<AttachmentMimeTypesFixtures>({
  attachmentMimeTypes: async ({ backendClient, opikTrace, project, testNamespace }, use, testInfo) => {
    const files = FILES.map((f) => ({ ...f, fileName: `${testNamespace}-${f.fileName}` }));

    for (const file of files) {
      await backendClient.uploadAttachment({
        projectName: project.name,
        entityType: 'trace',
        entityId: opikTrace.id,
        fileName: file.fileName,
        content: PNG_1X1,
        mimeType: file.sentMimeType,
      });
    }

    const ref: AttachmentMimeTypesRef = {
      traceId: opikTrace.id,
      projectId: project.id,
      files,
    };

    // Prove the declared types actually reached storage before the browser opens.
    //
    // This is the discriminating check: it is the difference between "the UI
    // classifies IMAGE/PNG as an image" and "the UI classifies whatever tika
    // guessed from a meaningless extension". Without it a regression that
    // dropped mime_type on the wire would leave four octet-stream rows, three
    // generic icons, and a failure that looked like a rendering bug.
    const listed = await backendClient.listAttachments({
      projectId: project.id,
      entityType: 'trace',
      entityId: opikTrace.id,
    });
    const storedByName = new Map(listed.map((a) => [a.fileName, a.mimeType]));
    const wrong = files
      .filter((f) => storedByName.get(f.fileName) !== f.sentMimeType)
      .map((f) => `${f.fileName}: sent ${f.sentMimeType}, stored ${storedByName.get(f.fileName) ?? '<absent>'}`);
    if (listed.length !== files.length || wrong.length > 0) {
      throw new Error(
        `[attachmentMimeTypes fixture] the seed did not store what it declared on trace ` +
          `${opikTrace.id} (${listed.length} of ${files.length} attachments listed)` +
          (wrong.length > 0 ? `: ${wrong.join('; ')}` : ''),
      );
    }

    await testInfo.attach('opik.attachmentMimeTypes', {
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
          fileNames: files.map((f) => f.fileName),
        });
      } catch (err) {
        console.warn(`[attachmentMimeTypes fixture] delete warning for trace ${opikTrace.id}:`, err);
      }
    }
  },
});

export { expect } from './experiment-image-output.fixture';
