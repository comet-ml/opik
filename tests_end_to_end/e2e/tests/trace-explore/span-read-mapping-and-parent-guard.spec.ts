import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The post-cutover spans read (opik#8595 / OPIK-8551).
 *
 * After the spans-local-v2 EXCHANGE, `parent_span_id` is non-nullable, so a
 * root span's absent parent is stored as a sentinel and mapped back to null on
 * read. Two failure modes, and they are not equal:
 *
 * - The parent mapping fails SILENTLY. The driver discards a row whose mapping
 *   throws, so the endpoint still answers 200 — with `total` greater than the
 *   rows it returned, and every trace quietly missing its root span. A reader
 *   that only checked "some spans came back" would see nothing wrong.
 * - The usage widening to `Int64` fails LOUDLY: a response carrying a span with
 *   usage fails to serialise at all.
 *
 * The same dropped row also skips `SpanService.update`'s conflict guard, which
 * is what refuses an update whose `parent_span_id` disagrees with the stored
 * one — so a foreign parent would be WRITTEN rather than refused, silently
 * re-parenting a span in someone's trace tree.
 *
 * `trace-spans-depth.spec.ts` covers the tree's expand/collapse and
 * `span-cost-resolution.spec.ts` covers pricing, but neither is cutover-aware
 * and neither asserts read integrity: both would pass against a response that
 * had lost its root span. The PR's own coverage is
 * `SpansPostCutoverReadMappingTest.java`.
 *
 * Every assertion here holds on a PRE-cutover deployment too — a root span
 * reads back with no parent either way, and the conflict guard is not new — so
 * this costs nothing on an un-migrated environment and catches the regression
 * on a migrated one. Staging is migrated: the 2.2.84 exploration confirmed
 * `spanColumnsNonNullable` is on there.
 */

/**
 * Token counts large enough that a narrowed integer type would be visible, and
 * distinct per span so a response that returned the same usage for every row
 * cannot pass.
 */
const CHILD_USAGE = [
  { prompt_tokens: 1200, completion_tokens: 3400, total_tokens: 4600 },
  { prompt_tokens: 150, completion_tokens: 250, total_tokens: 400 },
  { prompt_tokens: 7, completion_tokens: 11, total_tokens: 18 },
];

test.describe('Trace spans — post-cutover read mapping', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'every span of a trace reads back, root included, with its usage priced',
    { tag: ['@cap:traces.span-tree-expand'] },
    async ({ project, backendClient, testNamespace, page }) => {
      test.setTimeout(300_000);

      const traceId = uuid7();
      const rootId = uuid7();
      const childIds = CHILD_USAGE.map(() => uuid7());
      const grandchildId = uuid7();
      const names = {
        root: `${testNamespace}-root`,
        children: CHILD_USAGE.map((_, i) => `${testNamespace}-child-${i + 1}`),
        grandchild: `${testNamespace}-grandchild`,
      };

      await test.step('Seed a root span, three usage-carrying children and a grandchild', async () => {
        await backendClient.createTracesBatch({
          projectName: project.name,
          traces: [{ id: traceId, name: `${testNamespace}-trace`, input: {}, output: {} }],
        });
        await backendClient.createSpansBatch({
          projectName: project.name,
          spans: [
            // No parentSpanId at all — the row whose read mapping is the subject.
            { id: rootId, traceId, name: names.root },
            ...childIds.map((id, i) => ({
              id,
              traceId,
              name: names.children[i],
              parentSpanId: rootId,
              type: 'llm' as const,
              model: 'gpt-4o-mini',
              provider: 'openai',
              usage: CHILD_USAGE[i],
            })),
            { id: grandchildId, traceId, name: names.grandchild, parentSpanId: childIds[0] },
          ],
        });
      });

      const seededSpanCount = 1 + childIds.length + 1;

      const spans = await test.step('The read returns every span it counted', async () => {
        // Polled on `total` rather than on the rows, so ingestion lag is a wait
        // and the row-count assertion below stays a real assertion: a poll on
        // the rows would keep retrying the very shortfall this test exists to
        // report, and then fail as a timeout rather than as a dropped span.
        await expect
          .poll(
            async () =>
              (await backendClient.listSpansPage({ projectId: project.id, traceId })).total,
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(seededSpanCount);

        const read = await backendClient.listSpansPage({ projectId: project.id, traceId });

        // THE assertion for the silent failure mode. A row whose mapping threw
        // is discarded by the driver, leaving a 200 whose `total` outruns its
        // `content` — which is invisible to any check that only looks at the
        // rows that did come back.
        expect(
          read.spans.length,
          'the response must return as many spans as it says it found — ' +
            'a shortfall means a row was dropped on the way out',
        ).toBe(read.total);
        return read;
      });

      await test.step('Exactly one span has no parent, and it is the seeded root', async () => {
        const roots = spans.spans.filter((s) => s.parentSpanId === null);
        // Exactly one, not "the root is among them": two parentless spans would
        // mean a child's parent was mapped away, which renders as a flat tree
        // rather than as an error.
        expect(roots.map((s) => s.name), 'the trace has exactly one root span').toEqual([
          names.root,
        ]);
        expect(roots[0].id, 'the root span is the one that was seeded without a parent').toBe(
          rootId,
        );

        const byId = new Map(spans.spans.map((s) => [s.id, s]));
        for (const [i, id] of childIds.entries()) {
          expect(byId.get(id)?.parentSpanId, `${names.children[i]} still hangs off the root`).toBe(
            rootId,
          );
        }
        expect(
          byId.get(grandchildId)?.parentSpanId,
          'the grandchild still hangs off its own child',
        ).toBe(childIds[0]);
      });

      await test.step('Usage comes back as the integers it was written with, and is priced', async () => {
        const byId = new Map(spans.spans.map((s) => [s.id, s]));
        for (const [i, id] of childIds.entries()) {
          const span = byId.get(id);
          expect(span, `${names.children[i]} is in the response`).toBeDefined();
          // Required, not conditional: the widening fails at serialisation, so
          // an absent usage map here is the regression itself. Coding around it
          // would retire this half of the assertion in silence.
          expect(span!.usage, `${names.children[i]} carries its usage map`).not.toBeNull();
          expect(span!.usage, `${names.children[i]} token counts round-trip exactly`).toMatchObject(
            CHILD_USAGE[i],
          );
          expect(
            span!.totalEstimatedCost,
            `${names.children[i]} was priced server-side from its usage`,
          ).not.toBeNull();
          expect(
            span!.totalEstimatedCost!,
            `${names.children[i]} priced above zero`,
          ).toBeGreaterThan(0);
        }
      });

      await test.step('The trace panel renders the whole tree, root included', async () => {
        const logs = new LogsPage(page);
        await logs.goto(project.id);
        const panel = await logs.openTraceById(traceId);
        await panel.waitForFullyLoaded();

        await expect(
          panel.spansCountLabel(seededSpanCount),
          'the panel counts every span the read returned',
        ).toBeVisible();
        for (const name of [names.root, ...names.children, names.grandchild]) {
          await expect(panel.spanTreeNode(name), `${name} renders in the span tree`).toBeVisible();
        }
      });
    },
  );

  test(
    'an update cannot re-parent a root span, and one that is silent about the parent leaves it alone',
    { tag: ['@cap:traces.update-span-api'] },
    async ({ project, backendClient, testNamespace }) => {
      test.setTimeout(300_000);

      const traceId = uuid7();
      const rootId = uuid7();
      const childId = uuid7();

      await test.step('Seed a root span with one child', async () => {
        await backendClient.createTracesBatch({
          projectName: project.name,
          traces: [{ id: traceId, name: `${testNamespace}-guard-trace`, input: {}, output: {} }],
        });
        await backendClient.createSpansBatch({
          projectName: project.name,
          spans: [
            { id: rootId, traceId, name: `${testNamespace}-guard-root` },
            { id: childId, traceId, name: `${testNamespace}-guard-child`, parentSpanId: rootId },
          ],
        });
        await expect
          .poll(
            async () =>
              (await backendClient.listSpansPage({ projectId: project.id, traceId })).spans.length,
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(2);
      });

      await test.step('An update naming a different parent is refused with a 409', async () => {
        const result = await backendClient.updateSpan({
          spanId: rootId,
          projectName: project.name,
          traceId,
          parentSpanId: childId,
        });
        expect(result.status, 'the conflict guard refuses a foreign parent').toBe(409);
        // The reason, not just the code: a 409 raised by some other conflict
        // would satisfy the status alone and leave the guard untested.
        expect(result.message).toContain('parent_span_id does not match the existing span');
      });

      await test.step('An update that says nothing about the parent is accepted and keeps it null', async () => {
        const result = await backendClient.updateSpan({
          spanId: rootId,
          projectName: project.name,
          traceId,
          output: { patched: true },
        });
        expect(result.status, 'an ordinary partial update still lands').toBe(204);

        const after = await backendClient.listSpansPage({ projectId: project.id, traceId });
        const root = after.spans.find((s) => s.id === rootId);
        expect(root, 'the root span is still readable after the update').toBeDefined();
        expect(
          root!.parentSpanId,
          'an update silent about the parent must not give the root one',
        ).toBeNull();
        // The refused update must not have half-landed either.
        expect(after.spans.length, 'neither update changed the span population').toBe(after.total);
        expect(after.total).toBe(2);
      });
    },
  );
});
