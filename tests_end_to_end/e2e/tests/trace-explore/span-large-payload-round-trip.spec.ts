import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';
import { createHash } from 'node:crypto';

/**
 * Large single-row span and trace writes survive the streamed-payload path
 * (opik#9146 / OPIK-8921).
 *
 * 9146 rewrote EVERY single-row span and trace write — create, update,
 * update-before-create and batch update — to stream `input`, `output`,
 * `metadata` and their slim copies through `input()` + `FORMAT Values` instead
 * of inlining them as SQL literals. Unconditional, no flag, on the default path
 * for all data on every deployment.
 *
 * What makes it worth a spec is the failure mode: a streamed write that
 * truncates or blanks a payload answers 2xx. The row stays listed, the panel
 * still opens, and only the content has quietly gone — so nobody notices for
 * weeks. The estate's four existing merge specs
 * (`trace-partial-update-merge.spec.ts`, `span-by-id-aged-render.spec.ts`,
 * `trace-source-merge.spec.ts`, `span-read-mapping-and-parent-guard.spec.ts`)
 * all assert the merge semantics correctly and all use SMALL payloads, so the
 * size dimension — the whole point of the PR — is unexercised outside the PR's
 * own `LargePayloadTests`.
 *
 * Size: ~1 MiB per field. Comfortably into the streamed path and far below the
 * 21 MB `online-evaluation-oversized-payload.spec.ts` measured as repeatably
 * degrading a shared deployment for ~2 minutes — this spec runs on shared
 * staging, so it deliberately does not go near the >100 MB `max_query_size`
 * case the PR exists to fix. That case stays with the PR's Java tests; the
 * 2.2.116 exploration verified this shape by hand at 1 MiB and 8 MiB.
 *
 * Compared by LENGTH and SHA-256 rather than with `toEqual` on the value: a
 * megabyte-wide structural diff is unreadable when it fails, and a hash names
 * "the bytes changed" in one line. The marker keys are what the UI half asserts
 * and what tells a payload that landed in the wrong FIELD from one that was
 * truncated.
 *
 * Read-after-write is polled, never read once. The exploration saw a
 * `GET /spans/{id}` 404 ~250 ms after its own 201, and a tag PATCH take >700 ms
 * to show on a by-id read, twice — both resolved on retry, with the payload
 * correct every time.
 */

/** ~1 MiB per field, from a fixed block so the payload is identical every run. */
const BLOCK = 'cuj-payload-block-0123456789abcdef-';
const FILLER_BYTES = 1024 * 1024;
const FILLER = BLOCK.repeat(Math.ceil(FILLER_BYTES / BLOCK.length)).slice(0, FILLER_BYTES);

const sha256 = (value: string): string => createHash('sha256').update(value).digest('hex');

/**
 * One payload field: a marker naming the field it belongs in, then the filler.
 *
 * The marker is the FIRST key so the trace panel's data viewer renders it in
 * the first chunk it paints — a marker under a megabyte of filler may never
 * enter the DOM at all.
 */
const field = (marker: string) => ({ marker, filler: FILLER });

/**
 * The slim copies `truncate=true` returns are ~10 KB, which is what makes the
 * truncated read assertable as a RANGE: non-empty, and far smaller than the
 * megabyte it was cut from. An exact size would pin an implementation detail.
 */
const SLIM_CEILING_BYTES = 64 * 1024;

test.describe(
  'Trace & span large payload writes — CUJ',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'a multi-megabyte span survives create and a tag-only update byte-identically',
      { tag: ['@cap:traces.update-span-api', '@cap:traces.open-trace-panel'] },
      async ({ project, backendClient, testNamespace, page }) => {
        test.setTimeout(600_000);

        const traceId = uuid7();
        const spanId = uuid7();
        const spanName = `${testNamespace}-large-span`;
        const markers = {
          input: `${testNamespace}-input-marker`,
          output: `${testNamespace}-output-marker`,
          metadata: `${testNamespace}-metadata-marker`,
        };
        const written = {
          input: field(markers.input),
          output: field(markers.output),
          metadata: field(markers.metadata),
        };
        const tag = `${testNamespace}-patched`;

        await test.step('Seed a host trace, then POST the span with ~1 MiB in each of the three fields', async () => {
          await backendClient.createTraceWithSource({
            id: traceId,
            projectName: project.name,
            name: `${testNamespace}-large-host-trace`,
            source: 'sdk',
            input: { seeded: true },
            output: { seeded: true },
          });
          // The single-row endpoint, not the batch one: the batch path was
          // already streaming before 9146, so a batch seed would exercise the
          // wrong write entirely.
          await backendClient.createSpan({
            id: spanId,
            traceId,
            projectName: project.name,
            name: spanName,
            source: 'sdk',
            input: written.input,
            output: written.output,
            metadata: written.metadata,
          });
        });

        await test.step('All three fields read back at full length, byte-identical', async () => {
          await expect
            .poll(async () => (await backendClient.getSpanPayload(spanId))?.name ?? null, {
              message: `span ${spanName} is readable by id`,
              timeout: 180_000,
              intervals: [1_000, 2_000, 5_000],
            })
            .toBe(spanName);

          const payload = await backendClient.getSpanPayload(spanId);
          expect(payload, 'the span is readable by id').not.toBeNull();

          for (const key of ['input', 'output', 'metadata'] as const) {
            const read = payload![key] as { marker?: unknown; filler?: unknown } | null;
            // Asserted away, not coded around: a blanked payload is exactly the
            // regression, and `?? ''` would turn it into a silent pass.
            expect(read, `the span's ${key} came back at all`).not.toBeNull();
            expect(read!.marker, `the span's ${key} is the field it was written as`).toBe(
              markers[key],
            );
            expect(typeof read!.filler, `the span's ${key} filler is still a string`).toBe(
              'string',
            );
            const filler = read!.filler as string;
            expect(filler.length, `the span's ${key} is its full written length`).toBe(
              FILLER.length,
            );
            expect(sha256(filler), `the span's ${key} is byte-identical to what was written`).toBe(
              sha256(FILLER),
            );
          }
        });

        await test.step('The truncated list read returns slim copies — shorter, and not blank', async () => {
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
                message: 'the span appears in the truncated list read',
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
          expect(row, 'the span is in the truncated list read').toBeDefined();

          for (const key of ['input', 'output'] as const) {
            const slim = JSON.stringify(row![key] ?? null);
            expect(slim, `the truncated ${key} is not blank`).not.toBe('null');
            expect(
              slim.length,
              `the truncated ${key} is a slim copy, not the whole megabyte`,
            ).toBeLessThan(SLIM_CEILING_BYTES);
            expect(slim.length, `the truncated ${key} carries something`).toBeGreaterThan(0);
          }
          // Deliberately NOT asserted: that `metadata` is slimmed too. The
          // 2.2.116 exploration measured it coming back whole (8.4 MB of a
          // 9.5 MB "truncated" response) while the endpoint's own `@Schema`
          // says it truncates all three. That looks pre-existing rather than a
          // 9146 regression — the schema has `input_slim` and `output_slim` and
          // no `metadata_slim` — and it is filed as a soft finding for a human,
          // so pinning either behaviour here would be guessing which is right.
        });

        await test.step('A tag-only PATCH leaves all three payloads byte-identical', async () => {
          const result = await backendClient.updateSpan({
            spanId,
            projectName: project.name,
            traceId,
            tags: [tag],
          });
          expect(result.status, 'the tag-only update landed').toBe(204);

          await expect
            .poll(async () => (await backendClient.getSpanPayload(spanId))?.tags ?? null, {
              message: `the ${tag} tag is visible on a by-id read`,
              timeout: 180_000,
              intervals: [1_000, 2_000, 5_000],
            })
            .toEqual([tag]);

          const payload = await backendClient.getSpanPayload(spanId);
          expect(payload, 'the span is still readable after the update').not.toBeNull();

          for (const key of ['input', 'output', 'metadata'] as const) {
            const read = payload![key] as { marker?: unknown; filler?: unknown } | null;
            expect(read, `the span's ${key} survived the update`).not.toBeNull();
            expect(read!.marker, `the span's ${key} is still its own field`).toBe(markers[key]);
            const filler = read!.filler as string;
            expect(filler.length, `the span's ${key} kept its full length`).toBe(FILLER.length);
            expect(sha256(filler), `the span's ${key} is unchanged byte-for-byte`).toBe(
              sha256(FILLER),
            );
          }
        });

        await test.step('The truncated read still returns slim copies after the update', async () => {
          // Polled, not read once. The slim copies are rewritten by the update
          // on the same eventually-consistent path as the payload itself — the
          // by-id read above already needed polling for the tag — so a single
          // read here times the assertion against ingestion rather than against
          // the behaviour. A permanently blanked slim copy still fails: the poll
          // runs out and reports exactly that.
          await expect
            .poll(
              async () => {
                const truncated = await backendClient.listLogsPage({
                  entity: 'spans',
                  projectId: project.id,
                  truncate: true,
                });
                const row = truncated.rows.find((r) => r.id === spanId);
                if (!row) return null;
                return (['input', 'output'] as const).every((key) => {
                  const slim = JSON.stringify(row[key] ?? null);
                  return slim !== 'null' && slim.length > 0;
                });
              },
              {
                message:
                  'the truncated input and output were not blanked by the update — ' +
                  'the update writes its own slim copies, which is the half that ' +
                  'can go wrong while the full payload stays correct',
                // Generous for the same reason as the read above: `listLogsPage`
                // may stand off ~2 minutes on a rate limit before answering.
                timeout: 300_000,
                intervals: [1_000, 2_000, 5_000],
              },
            )
            .toBe(true);
        });

        await test.step('The trace panel renders each section with its own marker', async () => {
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          await logs.waitForReady();

          const panel = await logs.openTraceById(traceId);
          await panel.waitForFullyLoaded();
          // Waited for explicitly, and generously, before selecting it: this
          // trace carries ~3 MiB across its span's three payload fields, and the
          // panel fetches and renders that on open. The default 15s action
          // timeout is enough when the suite runs this spec alone and not when
          // the whole branch runs beside it — observed as the tree node never
          // appearing on a 4-worker run.
          await expect(
            panel.spanTreeNode(spanName),
            'the span appears in the trace tree',
          ).toBeVisible({ timeout: 120_000 });
          await panel.selectSpan(spanName);

          // Per SECTION, not anywhere in the panel: a payload that landed in
          // the wrong field renders its marker in the panel either way.
          for (const [section, marker] of [
            ['Input', markers.input],
            ['Output', markers.output],
            ['Metadata', markers.metadata],
          ] as const) {
            await expect(
              panel.sectionText(section, marker),
              `the ${section} section renders its own marker`,
            ).toBeVisible({ timeout: 60_000 });
          }
        });
      },
    );

    test(
      'a span update arriving before its create merges with the create instead of replacing it',
      { tag: ['@cap:traces.update-span-api'] },
      async ({ project, backendClient, testNamespace }) => {
        test.setTimeout(600_000);

        const traceId = uuid7();
        const spanId = uuid7();
        const spanName = `${testNamespace}-ooo-span`;
        const tag = `${testNamespace}-ooo-tag`;
        const patchedMetadata = { marker: `${testNamespace}-ooo-metadata`, fromPatch: true };
        const createdInput = { marker: `${testNamespace}-ooo-input` };
        const createdOutput = { marker: `${testNamespace}-ooo-output` };

        await test.step('Seed the host trace', async () => {
          await backendClient.createTraceWithSource({
            id: traceId,
            projectName: project.name,
            name: `${testNamespace}-ooo-host-trace`,
            source: 'sdk',
            input: { seeded: true },
            output: { seeded: true },
          });
        });

        await test.step('PATCH a span id that has not been created yet', async () => {
          // SDKs really send updates ahead of creates under load, which is why
          // this is one of the four write modes 9146 rewrote — and no spec in
          // the estate drives it.
          const result = await backendClient.updateSpan({
            spanId,
            projectName: project.name,
            traceId,
            tags: [tag],
            metadata: patchedMetadata,
          });
          expect(result.status, 'an update for an uncreated span is accepted').toBe(204);
        });

        await test.step('POST the create for the same id', async () => {
          await backendClient.createSpan({
            id: spanId,
            traceId,
            projectName: project.name,
            name: spanName,
            source: 'sdk',
            input: createdInput,
            output: createdOutput,
          });
        });

        await test.step('The stored span carries BOTH the create and the earlier update', async () => {
          await expect
            .poll(async () => (await backendClient.getSpanPayload(spanId))?.name ?? null, {
              message: `the create for ${spanName} landed`,
              timeout: 180_000,
              intervals: [1_000, 2_000, 5_000],
            })
            .toBe(spanName);

          const payload = await backendClient.getSpanPayload(spanId);
          expect(payload, 'the span is readable by id').not.toBeNull();

          // The create's half. A merge that let the create win outright would
          // satisfy these and drop the tag and metadata below.
          expect(payload!.input, "the create's input survived the merge").toEqual(createdInput);
          expect(payload!.output, "the create's output survived the merge").toEqual(createdOutput);
          // The update's half, which is the one an out-of-order write loses.
          expect(payload!.tags, "the earlier update's tag survived the create").toEqual([tag]);
          expect(payload!.metadata, "the earlier update's metadata survived the create").toEqual(
            patchedMetadata,
          );
        });
      },
    );
  },
);
