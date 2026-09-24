import { test as baseTest } from './experiment-item-read.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * Three 1x1 images, distinct in BYTES as well as in format.
 *
 * Distinct bytes is the whole design: `AttachmentsList` deduplicates by URL, and
 * the URL is `data:image/<ext>;base64,<the bytes>`. Two images that merely looked
 * different would collapse into one thumbnail and the mapping this fixture exists
 * to pin would be unobservable. Each is therefore its own colour — a red PNG, a
 * blue PNG and a green GIF — and the spec identifies a thumbnail by matching its
 * `src` against these exact strings, which makes every assertion an identity
 * check rather than a visual one.
 *
 * Handwritten rather than loaded from disk so the expected data URL can be
 * written down in full: the test's claim is "[image_1] resolved to the BLUE
 * picture", and that is only checkable against a known byte string.
 */
export const RED_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR42mP4z8AAAAMBAQD3A0FDAAAAAElFTkSuQmCC';
export const BLUE_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR42mNgYPgPAAEDAQA2dBFAAAAAAElFTkSuQmCC';
export const GREEN_GIF_BASE64 = 'R0lGODlhAQABAIAAAAD/AAAAACwAAAAAAQABAAACAkQBADs=';

export const RED_PNG_URL = `data:image/png;base64,${RED_PNG_BASE64}`;
export const BLUE_PNG_URL = `data:image/png;base64,${BLUE_PNG_BASE64}`;
export const GREEN_GIF_URL = `data:image/gif;base64,${GREEN_GIF_BASE64}`;

/**
 * The alt text a thumbnail carries for an inline image.
 *
 * `useExperimentItemMedia` keeps the extractor's own `name` ("Base64: [image_0]")
 * and `AttachmentThumbnail` renders it as the `<img alt>`, so the placeholder
 * written into the output text and the picture on screen are joined through this
 * one string. That join is precisely what OPIK-4954 fixed, and it is why the spec
 * addresses thumbnails by alt rather than by position.
 */
export const mediaAlt = (placeholder: string): string => `Base64: ${placeholder}`;

export interface ImageOutputItemRef {
  datasetItemId: string;
  traceId: string;
  /** The raw output string written to the trace, images and all. */
  rawOutput: string;
  /** The output text as the panel must render it once placeholders are resolved. */
  expectedText: string;
  /** Placeholder token -> the data URL its thumbnail must carry. */
  expectedUrlByPlaceholder: Record<string, string>;
  /** How many thumbnails survive `uniqBy(media, "url")`. */
  expectedThumbnailCount: number;
}

export interface ExperimentImageOutputRef {
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  projectId: string;
  /**
   * Three images in DOCUMENT order red-PNG, green-GIF, blue-PNG — deliberately
   * not the order the extractor numbers them in. `extractPrefixedBase64Images`
   * walks `BASE64_PREFIXES_MAP`, whose png key precedes its gif key, so it makes
   * a full pass for PNGs before it ever sees the GIF: the two PNGs take
   * [image_0] and [image_1] and the GIF sitting between them takes [image_2].
   *
   * A seed whose document order and placeholder order agreed would pass equally
   * well against the array-index recomputation this replaced, which is the
   * regression worth catching.
   */
  mixed: ImageOutputItemRef;
  /** The same PNG twice: two placeholders, one deduplicated thumbnail. */
  repeated: ImageOutputItemRef;
}

export interface ExperimentImageOutputFixtures {
  experimentImageOutput: ExperimentImageOutputRef;
}

/** How long the seeded experiment may take to become readable on the compare API. */
const QUERYABLE_TIMEOUT_MS = 120_000;
const QUERYABLE_POLL_MS = 2_000;

const MIXED_OUTPUT = `A:${RED_PNG_BASE64} B:${GREEN_GIF_BASE64} C:${BLUE_PNG_BASE64}`;
const REPEATED_OUTPUT = `first:${RED_PNG_BASE64} second:${RED_PNG_BASE64}`;

/**
 * An experiment whose items carry images inline in their trace output.
 *
 * Seeded through REST rather than the bridge's `evaluate` route for the reason
 * `experimentItemRead` gives: these rows exist to be rendered, not scored, and a
 * real `evaluate()` would run an LLM task per item. The images have to reach the
 * trace `output` verbatim, which a task's return value cannot guarantee.
 *
 * Teardown deletes the experiment, then the dataset it references, then the
 * traces — none of the three cascades with the project.
 */
export const test = baseTest.extend<ExperimentImageOutputFixtures>({
  experimentImageOutput: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const datasetName = `${testNamespace}-img-ds`;
    const experimentName = `${testNamespace}-img-exp`;
    const experimentId = uuid7();

    const mixedItemId = uuid7();
    const repeatedItemId = uuid7();
    const mixedTraceId = uuid7();
    const repeatedTraceId = uuid7();

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'inline image output in the experiment compare row panel',
    });

    let seededTraces = false;
    try {
      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        items: [
          { id: mixedItemId, data: { input: 'mixed-formats' } },
          { id: repeatedItemId, data: { input: 'repeated-image' } },
        ],
      });

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: [
          {
            id: mixedTraceId,
            name: `${testNamespace}-img-trace-mixed`,
            input: { input: 'mixed-formats' },
            output: { output: MIXED_OUTPUT },
          },
          {
            id: repeatedTraceId,
            name: `${testNamespace}-img-trace-repeated`,
            input: { input: 'repeated-image' },
            output: { output: REPEATED_OUTPUT },
          },
        ],
      });
      seededTraces = true;

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
      });

      await backendClient.createExperimentItems([
        { experimentId, datasetItemId: mixedItemId, traceId: mixedTraceId },
        { experimentId, datasetItemId: repeatedItemId, traceId: repeatedTraceId },
      ]);

      // Prove the images survived the write before any test opens a browser.
      //
      // Not a formality: the panel resolves placeholders out of the output
      // string, so an ingest that truncated or re-encoded it would leave the
      // thumbnails simply absent — and a UI assertion over that reads as the
      // rendering defect this spec is hunting rather than as the seed failure it
      // would actually be.
      for (const [label, traceId, expected] of [
        ['mixed', mixedTraceId, MIXED_OUTPUT],
        ['repeated', repeatedTraceId, REPEATED_OUTPUT],
      ] as const) {
        const payload = await backendClient.getTracePayload(traceId);
        const stored = (payload?.output as { output?: unknown } | null)?.output;
        if (stored !== expected) {
          throw new Error(
            `[experimentImageOutput fixture] the ${label} trace ${traceId} did not store its ` +
              `output verbatim: expected ${expected.length} chars, got ` +
              `${typeof stored === 'string' ? `${stored.length} chars` : typeof stored}`,
          );
        }
      }

      await waitForRows(backendClient, dataset.id, experimentId, 2);

      const ref: ExperimentImageOutputRef = {
        datasetId: dataset.id,
        datasetName,
        experimentId,
        experimentName,
        projectId: project.id,
        mixed: {
          datasetItemId: mixedItemId,
          traceId: mixedTraceId,
          rawOutput: MIXED_OUTPUT,
          // Format-grouped, not document order: B is the GIF and it is numbered
          // last even though it sits in the middle.
          expectedText: 'A:[image_0] B:[image_2] C:[image_1]',
          expectedUrlByPlaceholder: {
            '[image_0]': RED_PNG_URL,
            '[image_1]': BLUE_PNG_URL,
            '[image_2]': GREEN_GIF_URL,
          },
          expectedThumbnailCount: 3,
        },
        repeated: {
          datasetItemId: repeatedItemId,
          traceId: repeatedTraceId,
          rawOutput: REPEATED_OUTPUT,
          expectedText: 'first:[image_0] second:[image_1]',
          expectedUrlByPlaceholder: {
            '[image_0]': RED_PNG_URL,
            '[image_1]': RED_PNG_URL,
          },
          // Both placeholders point at one URL, and the list deduplicates by URL.
          expectedThumbnailCount: 1,
        },
      };

      await testInfo.attach('opik.experimentImageOutput', {
        body: JSON.stringify(
          { ...ref, mixed: { ...ref.mixed, rawOutput: '<elided>' }, repeated: { ...ref.repeated, rawOutput: '<elided>' } },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[experimentImageOutput fixture] delete warning for ${what}:`, err);
          }
        };
        await safe(`experiment ${experimentName}`, () => backendClient.deleteExperiment(experimentId));
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTraces) {
          await safe('2 traces', () => backendClient.deleteTraces([mixedTraceId, repeatedTraceId]));
        }
      }
    }
  },
});

/**
 * Block until the experiment reports exactly `expected` rows.
 *
 * Exactly, not at-least: a row that has not landed yet renders as an empty panel
 * section, which is indistinguishable from a panel that dropped its media.
 */
async function waitForRows(
  backendClient: { compareItemsPage: (args: { datasetId: string; experimentIds: string[]; size?: number }) => Promise<{ total: number }> },
  datasetId: string,
  experimentId: string,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    seen = (await backendClient.compareItemsPage({ datasetId, experimentIds: [experimentId], size: 1 })).total;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[experimentImageOutput fixture] experiment ${experimentId} reported ${seen} rows, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

export { expect } from './experiment-item-read.fixture';
