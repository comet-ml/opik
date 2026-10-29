import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Resolving a span BY ID at every age its UUIDv7 can encode, and what a
 * tags-only update does to the rest of the span (opik#8537, OPIK-8361).
 *
 * The PR bounds the span-by-id read to the weeks the id resolves to, turning a
 * full scan into a partition-local one. The hazard it introduces is arithmetic:
 * a bound computed from an instant the storage cannot represent — 1969 below
 * the 16-bit `Date` floor, ~2201 where a 32-bit `DateTime` wraps, 2350 past the
 * `DateTime64` ceiling — can exclude the row's own partition. Nothing errors
 * when it does. The read simply answers "not found".
 *
 * That silence is why the second test exists, and why it is the sharper of the
 * two. `SpanService.update` resolves the row before merging, and when the
 * lookup misses it falls through to a PARTIAL INSERT: the request answers 204,
 * the tag appears, and name, input, output and metadata are gone. A user sees a
 * span that lost its payload for no stated reason. A tags-only PATCH is the
 * worst case on purpose — everything the span holds is absent from the request
 * body, so everything is what a missed lookup can destroy.
 *
 * WHAT THIS ADDS. The estate has `trace-partial-update-merge.spec.ts` on the
 * TRACE side only; spans have no equivalent. The PR's own
 * `SpansReadPathWeekBoundTest` pins the API half in Java. The trace panel is
 * the half no Java test can reach, and it is where a user would actually
 * notice: the span tree resolves each node by id, so a bound that misses takes
 * the node's contents with it.
 *
 * The present-day span is the control. Its id sits inside every bound, right or
 * wrong, so if it failed too the read would be broken outright rather than at
 * the edges — which is a different finding and should not be reported as this
 * one.
 *
 * Deterministic: the ages are fixed instants, not offsets from the wall clock,
 * and every entity is API-seeded from clean.
 */
test.describe(
  'Trace Explore — spans resolved by id at every age',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'every aged span resolves by id and renders its own payload in the trace panel',
      { tag: ['@cap:traces.span-tree-expand'] },
      async ({ idAgedSpans, backendClient, project, page }) => {
        await test.step('Each span resolves by id with the payload it was written with', async () => {
          for (const span of idAgedSpans.spans) {
            const payload = await backendClient.getSpanPayload(span.id);
            expect(
              payload,
              `the ${span.label} span (id instant ${span.idMoment.toISOString()}) must resolve ` +
                'by id — a week bound that misses its partition answers 404 here',
            ).not.toBeNull();
            expect(
              {
                name: payload?.name,
                input: payload?.input,
                output: payload?.output,
                metadata: payload?.metadata,
              },
              `the ${span.label} span's stored payload`,
            ).toEqual({
              name: span.name,
              input: span.input,
              output: span.output,
              metadata: span.metadata,
            });
          }
        });

        const panel = await test.step('Open the trace panel', async () => {
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          const panel = await logs.openTraceById(idAgedSpans.traceId);
          await panel.waitForFullyLoaded();
          return panel;
        });

        await test.step('The span tree holds every seeded span', async () => {
          // The count, not just "my nodes are present": a tree that listed the
          // four seeded spans plus something else would mean the read is not
          // scoped the way this spec assumes.
          await expect(
            panel.spansCountLabel(idAgedSpans.spans.length),
            `the tree must report ${idAgedSpans.spans.length} spans`,
          ).toBeVisible();
        });

        for (const span of idAgedSpans.spans) {
          await test.step(`Selecting the ${span.label} span renders its own input and output`, async () => {
            await panel.selectSpan(span.name);
            // Its OWN payload, not merely "some payload": selecting a node
            // whose read missed would leave the previous node's contents on
            // screen, which is the same class of silent wrongness as an empty
            // panel and much easier to miss.
            await expect(
              panel.inputValue(span.input.q),
              `the ${span.label} span's input in the panel`,
            ).toBeVisible();
            await expect(
              panel.outputValue(span.output.a),
              `the ${span.label} span's output in the panel`,
            ).toBeVisible();
          });
        }
      },
    );

    test(
      'a tags-only PATCH keeps every other field, at every id age',
      // `update-span-api` is the load-bearing claim: this is the span counterpart
      // of `trace-partial-update-merge.spec.ts`, and that key's own taxonomy note
      // says a span update is explicitly NOT part of span-tree-expand. The tree
      // tag stays because the last step really does drive the panel's tree —
      // selecting each node and reading the preserved payload back off it.
      { tag: ['@cap:traces.update-span-api', '@cap:traces.span-tree-expand'] },
      async ({ idAgedSpans, backendClient, project, page }) => {
        const tagFor = (label: string) => `aged-${label}-tag`;

        await test.step('Tag each span, sending nothing else', async () => {
          for (const span of idAgedSpans.spans) {
            const answer = await backendClient.updateSpan({
              spanId: span.id,
              projectName: idAgedSpans.projectName,
              traceId: idAgedSpans.traceId,
              tags: [tagFor(span.label)],
            });
            expect(
              answer.status,
              `PATCH of the ${span.label} span: ${answer.message}`,
            ).toBe(204);
          }
        });

        await test.step('The tag landed and nothing else moved', async () => {
          for (const span of idAgedSpans.spans) {
            const payload = await backendClient.getSpanPayload(span.id);
            expect(payload, `the ${span.label} span after its PATCH`).not.toBeNull();
            // Compared as one object rather than field by field, so a failure
            // names everything the update dropped in one go — which is the
            // signature of the partial-insert fall-through, as opposed to a
            // single field going wrong.
            expect(
              {
                name: payload?.name,
                type: payload?.type,
                traceId: payload?.traceId,
                projectId: payload?.projectId,
                input: payload?.input,
                output: payload?.output,
                metadata: payload?.metadata,
                tags: payload?.tags,
              },
              `the ${span.label} span (id instant ${span.idMoment.toISOString()}) after a ` +
                'tags-only PATCH: a week bound that misses sends the update down the ' +
                'partial-insert path, which answers 204 and silently drops every field the ' +
                'request did not carry',
            ).toEqual({
              name: span.name,
              type: span.type,
              traceId: idAgedSpans.traceId,
              projectId: project.id,
              input: span.input,
              output: span.output,
              metadata: span.metadata,
              tags: [tagFor(span.label)],
            });
          }
        });

        await test.step('The panel still renders the preserved payload beside the new tag', async () => {
          // The API read and the panel are two different projections of the
          // same row and are allowed to disagree — the panel resolves the node
          // through the tree's own read. Asserting only the API half would
          // leave the surface a user actually looks at unchecked.
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          const panel = await logs.openTraceById(idAgedSpans.traceId);
          await panel.waitForFullyLoaded();

          for (const span of idAgedSpans.spans) {
            await panel.selectSpan(span.name);
            await expect(
              panel.inputValue(span.input.q),
              `the ${span.label} span's input survived the PATCH, on screen`,
            ).toBeVisible();
            await expect(
              panel.outputValue(span.output.a),
              `the ${span.label} span's output survived the PATCH, on screen`,
            ).toBeVisible();
            await expect(
              panel.tagChip(tagFor(span.label)),
              `the ${span.label} span's new tag chip`,
            ).toBeVisible();
          }
        });
      },
    );
  },
);
