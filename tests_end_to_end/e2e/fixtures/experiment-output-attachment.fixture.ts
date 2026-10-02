import { test as baseTest } from './readability-locale-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * A 1x1 red PNG, uploaded as a real attachment rather than written inline.
 *
 * Bytes matter here for the same reason they do in `experimentImageOutput`: the
 * panel renders an `image/*` attachment as an `<img>`, and a thumbnail that
 * cannot decode leaves an element in the DOM that a `toBeVisible()` would accept.
 */
const RED_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR42mP4z8AAAAMBAQD3A0FDAAAAAElFTkSuQmCC',
  'base64',
);

/** The output both items carry. Plain text, with no inline media of any kind. */
const ATTACHED_OUTPUT = 'the picture is attached to the trace, not inlined';
const PLAIN_OUTPUT = 'a text-only answer with no media anywhere';

export interface OutputAttachmentItemRef {
  datasetItemId: string;
  traceId: string;
  /** The output text the panel must render, unchanged. */
  outputText: string;
}

export interface ExperimentOutputAttachmentRef {
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  projectId: string;
  /** The item whose trace carries an uploaded attachment. */
  attached: OutputAttachmentItemRef & { fileName: string };
  /** The control: same shape, no attachment and no inline media. */
  plain: OutputAttachmentItemRef;
}

export interface ExperimentOutputAttachmentFixtures {
  experimentOutputAttachment: ExperimentOutputAttachmentRef;
}

/** How long the seeded experiment may take to become readable on the compare API. */
const QUERYABLE_TIMEOUT_MS = 120_000;
const QUERYABLE_POLL_MS = 2_000;

/** How long a REST-written trace may take to become readable by id. */
const READABLE_TIMEOUT_MS = 30_000;

/**
 * An experiment with two items: one whose trace carries an UPLOADED attachment,
 * and one carrying no media at all.
 *
 * Distinct from `experimentImageOutput`, which seeds base64 images inline in the
 * output string. That path needs no network at all — a data URL is its own
 * content — so it cannot exercise the half of OPIK-4954 that matters most here:
 * an SDK-logged image reaches the output as an attachment, and the panel has to
 * go and ASK for it. `useExperimentItemMedia` cannot reuse `useUnifiedMedia` to
 * do that (an experiment item carries no project_id of its own, and exposing a
 * trace_id makes `isObjectSpan` misclassify it), so it sources the project from
 * the EXPERIMENT and the entity from the item's trace. That lookup is what this
 * shape makes observable.
 *
 * The plain item is the control, and it is load-bearing twice over: it is what
 * turns "no Attachments section" into a real negative, and without it a build
 * that rendered an empty strip on every item would satisfy every assertion the
 * attached item can make.
 *
 * Seeded through REST rather than an `evaluate()` run for the reason
 * `experimentImageOutput` gives: these rows exist to be rendered, not scored.
 *
 * Teardown deletes the attachment, then the experiment, the dataset and the
 * traces — none of the four cascades with the project.
 */
export const test = baseTest.extend<ExperimentOutputAttachmentFixtures>({
  experimentOutputAttachment: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-att-ds`;
    const experimentName = `${testNamespace}-att-exp`;
    const experimentId = uuid7();

    const attachedItemId = uuid7();
    const plainItemId = uuid7();
    const attachedTraceId = uuid7();
    const plainTraceId = uuid7();
    // Namespaced so the alt-text locator cannot collide with anything else a
    // shared workspace happens to have attached.
    const fileName = `${testNamespace}-trace-side.png`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'uploaded output attachments in the experiment compare row panel',
    });

    let seededTraces = false;
    let seededAttachment = false;
    try {
      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        items: [
          { id: attachedItemId, data: { input: 'attachment-backed' } },
          { id: plainItemId, data: { input: 'no-media' } },
        ],
      });

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: [
          {
            id: attachedTraceId,
            name: `${testNamespace}-att-trace-attached`,
            input: { input: 'attachment-backed' },
            output: { output: ATTACHED_OUTPUT },
          },
          {
            id: plainTraceId,
            name: `${testNamespace}-att-trace-plain`,
            input: { input: 'no-media' },
            output: { output: PLAIN_OUTPUT },
          },
        ],
      });
      seededTraces = true;

      for (const [label, traceId, expected] of [
        ['attached', attachedTraceId, ATTACHED_OUTPUT],
        ['plain', plainTraceId, PLAIN_OUTPUT],
      ] as const) {
        await waitForStoredOutput(backendClient, label, traceId, expected);
      }

      // Uploaded through the real presigned multipart flow, and only onto the
      // ONE trace. `mime_type` is omitted so the backend derives `image/png`
      // from the name, which is the same path a real SDK upload takes.
      await backendClient.uploadAttachment({
        projectName: project.name,
        entityType: 'trace',
        entityId: attachedTraceId,
        fileName,
        content: RED_PNG,
      });
      seededAttachment = true;

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
      });

      await backendClient.createExperimentItems([
        { experimentId, datasetItemId: attachedItemId, traceId: attachedTraceId },
        { experimentId, datasetItemId: plainItemId, traceId: plainTraceId },
      ]);

      // Prove the fixture can discriminate before any browser opens: the
      // attachment really is on the one trace, and really is absent from the
      // other. A UI assertion over a seed that silently failed either way is a
      // test that cannot fail — an empty strip would read as the regression
      // rather than as the setup fault it would be.
      const onAttached = await backendClient.listAttachments({
        projectId: project.id,
        entityType: 'trace',
        entityId: attachedTraceId,
      });
      if (onAttached.length !== 1 || onAttached[0].fileName !== fileName) {
        throw new Error(
          `[experimentOutputAttachment fixture] expected exactly ${fileName} on trace ` +
            `${attachedTraceId}, the API lists: ${onAttached.map((a) => a.fileName).join(', ') || '(none)'}`,
        );
      }
      if (onAttached[0].mimeType !== 'image/png') {
        throw new Error(
          `[experimentOutputAttachment fixture] ${fileName} stored as ` +
            `${onAttached[0].mimeType}, not image/png — it would not render as an <img>`,
        );
      }
      const onPlain = await backendClient.listAttachments({
        projectId: project.id,
        entityType: 'trace',
        entityId: plainTraceId,
      });
      if (onPlain.length !== 0) {
        throw new Error(
          `[experimentOutputAttachment fixture] the control trace ${plainTraceId} must carry no ` +
            `attachments, the API lists: ${onPlain.map((a) => a.fileName).join(', ')}`,
        );
      }

      await waitForRows(backendClient, dataset.id, experimentId, 2);

      const ref: ExperimentOutputAttachmentRef = {
        datasetId: dataset.id,
        datasetName,
        experimentId,
        experimentName,
        projectId: project.id,
        attached: {
          datasetItemId: attachedItemId,
          traceId: attachedTraceId,
          outputText: ATTACHED_OUTPUT,
          fileName,
        },
        plain: {
          datasetItemId: plainItemId,
          traceId: plainTraceId,
          outputText: PLAIN_OUTPUT,
        },
      };

      await testInfo.attach('opik.experimentOutputAttachment', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[experimentOutputAttachment fixture] delete warning for ${what}:`, err);
          }
        };
        if (seededAttachment) {
          await safe(fileName, () =>
            backendClient.deleteAttachments({
              projectId: project.id,
              entityType: 'trace',
              entityId: attachedTraceId,
              fileNames: [fileName],
            }),
          );
        }
        await safe(`experiment ${experimentName}`, () =>
          backendClient.deleteExperiment(experimentId),
        );
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTraces) {
          await safe('2 traces', () =>
            backendClient.deleteTraces([attachedTraceId, plainTraceId]),
          );
        }
      }
    }
  },
});

/**
 * Block until one trace reads back with its output byte-for-byte as written.
 *
 * Polls rather than reads once: `createTracesBatch` is an asynchronous ingest,
 * so `GET /traces/{id}` answers 404 for a while after the write returns, and
 * failing on that first answer turns a healthy seed into a spurious fault.
 */
async function waitForStoredOutput(
  backendClient: {
    getTracePayload: (traceId: string) => Promise<{ output: unknown } | null>;
  },
  label: string,
  traceId: string,
  expected: string,
): Promise<void> {
  const start = Date.now();
  let stored: unknown;
  let seen = false;
  while (Date.now() - start < READABLE_TIMEOUT_MS) {
    const payload = await backendClient.getTracePayload(traceId);
    if (payload !== null) {
      seen = true;
      stored = (payload.output as { output?: unknown } | null)?.output;
      if (stored === expected) return;
    }
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[experimentOutputAttachment fixture] the ${label} trace ${traceId} ` +
      (seen
        ? `did not store its output verbatim: expected ${JSON.stringify(expected)}, got ${JSON.stringify(stored)}`
        : 'never became readable') +
      ` after ${Date.now() - start}ms`,
  );
}

/**
 * Block until the experiment reports exactly `expected` rows.
 *
 * Exactly, not at-least: a row that has not landed yet renders as an empty panel
 * section, which is indistinguishable from a panel that dropped its media.
 */
async function waitForRows(
  backendClient: {
    compareItemsPage: (args: {
      datasetId: string;
      experimentIds: string[];
      size?: number;
    }) => Promise<{ total: number }>;
  },
  datasetId: string,
  experimentId: string,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    seen = (
      await backendClient.compareItemsPage({ datasetId, experimentIds: [experimentId], size: 1 })
    ).total;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[experimentOutputAttachment fixture] experiment ${experimentId} reported ${seen} rows, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

export { expect } from './readability-locale-experiment.fixture';
