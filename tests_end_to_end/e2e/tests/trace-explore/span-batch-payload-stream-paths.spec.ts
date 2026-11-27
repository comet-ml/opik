import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';
import { createHash } from 'node:crypto';

/**
 * The two payload writers `ANALYTICS_DB_STREAM_PAYLOADS` governs that no spec
 * reaches at size: the SPAN BATCH body and the single-row TRACE write
 * (opik#9594 / OPIK-9233).
 *
 * 9594 rewrote three writers to stream `input`, `output` and `metadata` —
 * `SpanDAO`'s single-row insert, `SpanDAO`'s batch insert
 * (`JsonEachRowBulkInsert`) and `TraceDAO`'s payload write — behind
 * `ANALYTICS_DB_STREAM_PAYLOADS`. The estate covers exactly one of the three:
 * `span-large-payload-round-trip.spec.ts` drives the single-row SPAN create at
 * ~1 MiB, and its own comment says why it does not drive the batch ("the batch
 * path was already streaming before 9146, so a batch seed would exercise the
 * wrong write entirely"). It also seeds its host trace SMALL — `{ seeded: true }`
 * in each field — so `TraceDAO`'s payload write is untested at size too.
 *
 * This spec is those two, on one seed: a trace POSTed to the single-row
 * `/v1/private/traces` with ~1 MiB in each of its three fields, carrying one
 * span POSTed to `/v1/private/spans/batch` with ~1 MiB in each of ITS three.
 * Both are written before either is read, so a reader of a failure can tell
 * "one writer truncates" from "ingestion was down".
 *
 * Why it is worth a spec at all is the failure mode, which is the same one
 * 9146's spec names: a streamed write that truncates or blanks a payload
 * answers 2xx. The row stays listed, the panel still opens, and only the
 * content has quietly gone. The 2.3.2 staging exploration drove exactly this
 * shape by hand and saw all nine fields read back at 1048576 bytes.
 *
 * `ANALYTICS_DB_STREAM_PAYLOADS`'s VALUE is deliberately not asserted and not
 * read: on managed cloud it is neither readable nor settable from a test, so a
 * spec that branched on it could only ever assert what it was already told.
 * What is asserted is the thing that must hold either way — the bytes come back
 * as they went in — which is what makes this spec useful on a deployment that
 * flips the flag later.
 *
 * Size: ~1 MiB per field, for the reasons `span-large-payload-round-trip`
 * records — comfortably into the streamed path, and deliberately nowhere near
 * the >100 MB `max_query_size` case, because this runs on shared staging.
 * Compared by LENGTH and SHA-256 rather than `toEqual`, because a megabyte-wide
 * structural diff is unreadable when it fails.
 *
 * Read-after-write is polled, never read once: the exploration saw
 * `GET /traces/{id}` answer 404 within a second of its own 201 and resolve on
 * retry, with the payload correct every time.
 */

/** ~1 MiB per field, from a fixed block so the payload is identical every run. */
const BLOCK = 'cuj-batch-payload-block-0123456789abcdef-';
const FILLER_BYTES = 1024 * 1024;
const FILLER = BLOCK.repeat(Math.ceil(FILLER_BYTES / BLOCK.length)).slice(0, FILLER_BYTES);

const sha256 = (value: string): string => createHash('sha256').update(value).digest('hex');

/**
 * One payload field: a marker naming the field it belongs in, then the filler.
 *
 * The marker is the FIRST key so the trace panel's data viewer renders it in
 * the first chunk it paints — a marker under a megabyte of filler may never
 * enter the DOM at all. It is also what tells a payload that landed in the
 * wrong FIELD from one that was truncated: both read back "wrong", and only the
 * marker says which.
 */
const field = (marker: string) => ({ marker, filler: FILLER });

const PAYLOAD_KEYS = ['input', 'output', 'metadata'] as const;

/**
 * The slim copies `truncate=true` returns are ~10 KB, which is what makes the
 * truncated read assertable as a RANGE: non-empty, and far smaller than the
 * megabyte it was cut from. An exact size would pin an implementation detail.
 */
const SLIM_CEILING_BYTES = 64 * 1024;

/**
 * Asserts one read-back section is the field it was written as, at its full
 * length, byte-for-byte.
 *
 * Shared between the trace and the span legs because the claim is identical for
 * both and the writers are not — a helper here is what keeps "which writer
 * lost it" the only thing that differs in the failure message.
 */
function expectFieldSurvived(
  read: unknown,
  opts: { writer: string; key: string; marker: string },
): void {
  const { writer, key, marker } = opts;
  // Asserted away rather than coded around with `?? {}`: a blanked payload is
  // exactly the regression this spec exists for, and a default would turn it
  // into a silent pass.
  expect(read, `the ${writer} ${key} came back at all`).not.toBeNull();
  expect(typeof read, `the ${writer} ${key} came back as a JSON object`).toBe('object');

  const section = read as { marker?: unknown; filler?: unknown };
  expect(section.marker, `the ${writer} ${key} is the field it was written as`).toBe(marker);
  expect(typeof section.filler, `the ${writer} ${key} filler is still a string`).toBe('string');

  const filler = section.filler as string;
  expect(filler.length, `the ${writer} ${key} is its full written length`).toBe(FILLER.length);
  expect(sha256(filler), `the ${writer} ${key} is byte-identical to what was written`).toBe(
    sha256(FILLER),
  );
}

test.describe(
  'Streamed payload writers — batch span and single-row trace — CUJ',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'a ~1 MiB payload survives the span BATCH write and the single-row TRACE write byte-identically',
      {
        tag: ['@cap:traces.ingest-payload-fidelity', '@cap:traces.open-trace-panel'],
      },
      async ({ project, backendClient, testNamespace, registerProjectTracesCleanup, page }) => {
        // ~6 MiB of payload across two rows, so this is not left behind:
        // `deleteProject` does not cascade to traces and `global-teardown`'s
        // run-prefix sweep does not know about them. Registered before the
        // first write, so a seed that failed half way through is still swept.
        registerProjectTracesCleanup(project.id);

        test.setTimeout(900_000);

        const traceId = uuid7();
        const spanId = uuid7();
        const traceName = `${testNamespace}-stream-trace`;
        const spanName = `${testNamespace}-stream-batch-span`;

        // Six distinct markers, one per (writer, field) pair. Distinct ACROSS
        // the two rows as well as within each: a trace payload that leaked into
        // its span's section — or the reverse — reads as "the right size, the
        // right hash, the wrong row", which only a cross-row-distinct marker can
        // catch.
        const traceMarkers = {
          input: `${testNamespace}-trace-input-marker`,
          output: `${testNamespace}-trace-output-marker`,
          metadata: `${testNamespace}-trace-metadata-marker`,
        } as const;
        const spanMarkers = {
          input: `${testNamespace}-span-input-marker`,
          output: `${testNamespace}-span-output-marker`,
          metadata: `${testNamespace}-span-metadata-marker`,
        } as const;

        await test.step('POST the trace to the single-row endpoint with ~1 MiB in each field', async () => {
          // `createTraceWithSource` is `POST /v1/private/traces` — the
          // single-row write whose payload clause 9594 rewrote. The host trace
          // carrying real payload rather than a stub is the whole point: this
          // is the writer the estate's existing large-payload spec seeds small.
          await backendClient.createTraceWithSource({
            id: traceId,
            projectName: project.name,
            name: traceName,
            source: 'sdk',
            input: field(traceMarkers.input),
            output: field(traceMarkers.output),
            metadata: field(traceMarkers.metadata),
          });
        });

        await test.step('POST the span to the BATCH endpoint with ~1 MiB in each field', async () => {
          // `createSpansBatch` is `POST /v1/private/spans/batch` — the
          // `JsonEachRowBulkInsert` body, the writer `span-large-payload-round-
          // trip.spec.ts` deliberately avoids. One span, not a page of them:
          // the subject is the body's SIZE, not its row count.
          await backendClient.createSpansBatch({
            projectName: project.name,
            spans: [
              {
                id: spanId,
                traceId,
                name: spanName,
                source: 'sdk',
                input: field(spanMarkers.input),
                output: field(spanMarkers.output),
                metadata: field(spanMarkers.metadata),
              },
            ],
          });
        });

        await test.step('The single-row trace write returns all three fields byte-identically', async () => {
          await expect
            .poll(async () => (await backendClient.getTracePayload(traceId))?.name ?? null, {
              message: `the trace ${traceName} is readable by id`,
              timeout: 180_000,
              intervals: [1_000, 2_000, 5_000],
            })
            .toBe(traceName);

          const payload = await backendClient.getTracePayload(traceId);
          expect(payload, 'the trace is readable by id').not.toBeNull();

          for (const key of PAYLOAD_KEYS) {
            expectFieldSurvived(payload![key], {
              writer: 'single-row trace write',
              key,
              marker: traceMarkers[key],
            });
          }
        });

        await test.step('The batch span write returns all three fields byte-identically', async () => {
          await expect
            .poll(async () => (await backendClient.getSpanPayload(spanId))?.name ?? null, {
              message: `the batch-written span ${spanName} is readable by id`,
              timeout: 180_000,
              intervals: [1_000, 2_000, 5_000],
            })
            .toBe(spanName);

          const payload = await backendClient.getSpanPayload(spanId);
          expect(payload, 'the batch-written span is readable by id').not.toBeNull();
          // The batch write is also what places the span on its trace, and a
          // payload asserted on a span that landed under the wrong trace would
          // be asserted on the wrong row.
          expect(payload!.traceId, 'the batch-written span is on the seeded trace').toBe(traceId);

          for (const key of PAYLOAD_KEYS) {
            expectFieldSurvived(payload![key], {
              writer: 'batch span write',
              key,
              marker: spanMarkers[key],
            });
          }
        });

        await test.step('The truncated list read returns slim copies of the batch span — shorter, and not blank', async () => {
          // Waited for first: the list read is a different index from the by-id
          // read polled above, so the span can be readable by id and not yet
          // listed. The hard assertions below then run on a settled read.
          await expect
            .poll(
              async () =>
                (
                  await backendClient.listLogsPage({
                    entity: 'spans',
                    projectId: project.id,
                    truncate: true,
                  })
                ).rows.some((r) => r.id === spanId),
              {
                message: 'the batch-written span appears in the truncated list read',
                // Generous because `listLogsPage` may stand off for ~2 minutes
                // of its own on a rate limit before it even answers once.
                timeout: 300_000,
                intervals: [1_000, 2_000, 5_000],
              },
            )
            .toBe(true);

          const truncated = await backendClient.listLogsPage({
            entity: 'spans',
            projectId: project.id,
            truncate: true,
          });
          const row = truncated.rows.find((r) => r.id === spanId);
          expect(row, 'the batch-written span is in the truncated list read').toBeDefined();
          // The whole project, not just the found row: a batch write that
          // fanned one span out into several rows is a defect this read is the
          // cheapest place to see, and `find()` alone would hide it.
          expect(
            truncated.rows.filter((r) => r.id === spanId).length,
            'the batch write produced exactly one row for the span',
          ).toBe(1);
          expect(truncated.total, 'the project holds exactly the one seeded span').toBe(1);

          for (const key of ['input', 'output'] as const) {
            const slim = JSON.stringify(row![key] ?? null);
            expect(slim, `the truncated ${key} is not blank`).not.toBe('null');
            expect(
              slim.length,
              `the truncated ${key} is a slim copy, not the whole megabyte`,
            ).toBeLessThan(SLIM_CEILING_BYTES);
            expect(slim.length, `the truncated ${key} carries something`).toBeGreaterThan(0);
          }
          // Deliberately NOT asserted: that `metadata` is slimmed too — the
          // same carve-out `span-large-payload-round-trip.spec.ts` records. It
          // was measured coming back whole while the endpoint's `@Schema` says
          // otherwise, that looks pre-existing rather than a 9594 regression,
          // and it is filed as a soft finding — so pinning either behaviour
          // here would be guessing which is right.
        });

        await test.step('The trace panel renders each of the batch span’s sections with its own marker', async () => {
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          await logs.waitForReady();

          const panel = await logs.openTraceById(traceId);
          await panel.waitForFullyLoaded();
          // Waited for explicitly, and generously, before selecting it: this
          // trace carries ~6 MiB across its own and its span's payload fields,
          // and the panel fetches and renders that on open. Through
          // `waitForSpanTreeNodes` because on staging the tree node failing to
          // appear turned out to be the panel's spans read answered 429, which
          // the panel never re-issues by itself.
          await panel.waitForSpanTreeNodes([spanName], { timeout: 60_000 });
          await panel.selectSpan(spanName);

          // Per SECTION, not anywhere in the panel: a payload that landed in
          // the wrong field renders its marker in the panel either way. The
          // markers asserted are the SPAN's, because the span is what is
          // selected — the trace's six-marker set is what makes this a
          // statement about the batch row rather than about whichever row the
          // panel happened to be showing.
          for (const [section, marker] of [
            ['Input', spanMarkers.input],
            ['Output', spanMarkers.output],
            ['Metadata', spanMarkers.metadata],
          ] as const) {
            await expect(
              panel.sectionText(section, marker),
              `the ${section} section renders the batch span’s own marker`,
            ).toBeVisible({ timeout: 60_000 });
          }
        });
      },
    );
  },
);
