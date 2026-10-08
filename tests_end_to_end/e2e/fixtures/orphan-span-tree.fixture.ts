import { expect } from '@playwright/test';
import { test as baseTest } from './search-population.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed } from '../core/backend';

/**
 * The metadata the SDK writes on a span the trace panel collapses by default —
 * `spanVisibility.ts`'s only hide rule, read at
 * `metadata._opik.is_internal === true`.
 */
const INTERNAL_SPAN_METADATA = { _opik: { is_internal: true } } as const;

/** How long a just-written span batch may take to become queryable. */
const QUERYABLE_TIMEOUT_MS = 120_000;

/**
 * One span of the seeded tree, by the role it plays.
 *
 * `root` and `rootChild` are the ordinary shape. `orphan` is the subject: its
 * `parent_span_id` names an id that is never written. `internal` carries the
 * SDK's hide flag and `internalChild` hangs off it, which is what makes the
 * hide mode's re-parenting observable.
 */
export type OrphanTreeRole = 'root' | 'rootChild' | 'orphan' | 'internal' | 'internalChild';

export interface OrphanSpanRef {
  id: string;
  name: string;
  role: OrphanTreeRole;
  /** The id written as this span's parent; `null` for the trace's own root span. */
  parentSpanId: string | null;
}

export interface OrphanSpanTreeRef {
  traceId: string;
  traceName: string;
  spans: OrphanSpanRef[];
  /** The span name, by role — what the tree node's `data-testid` is built from. */
  nameOf: Record<OrphanTreeRole, string>;
  /**
   * The id the orphan claims as its parent, which exists nowhere.
   *
   * On the ref so a spec can assert the SERVER stored it verbatim: if the
   * backend had nulled a dangling parent, the orphan would simply be a second
   * root span and every UI assertion below would pass without the tree builder
   * having had to decide anything.
   */
  phantomParentId: string;
  /** Every span the trace holds — the count the panel reports in "show all". */
  totalSpanCount: number;
  /** Spans left once the internal one is hidden — the count the panel reports hiding. */
  visibleSpanCount: number;
}

export interface OrphanSpanTreeFixtures {
  orphanSpanTree: OrphanSpanTreeRef;
}

export const test = baseTest.extend<OrphanSpanTreeFixtures>({
  /**
   * One trace whose five spans make every branch of the tree builder observable
   * at once (opik#8797 · issue-5934).
   *
   * ```
   *   trace
   *   ├── root                 (no parent at all)
   *   │   ├── rootChild
   *   │   └── internal         (metadata._opik.is_internal = true)
   *   │       └── internalChild
   *   └── orphan               (parent_span_id = an id never written)
   * ```
   *
   * Why this shape and not just "a trace with an orphan in it":
   *
   *  - **`root` sits beside `orphan`.** The assertion is that the orphan lands
   *    at the TRACE root, which is only a statement about position if there is a
   *    real root span at that depth to compare it with. Without one, "the orphan
   *    is at depth 1" is true of a tree that had collapsed to a single level.
   *  - **`rootChild` is the control for depth.** It is an ordinary child of an
   *    ordinary root, so a build that flattened the whole tree — the opposite of
   *    the bug — fails on it rather than passing the orphan assertion.
   *  - **`internal` + `internalChild` are what the hide mode acts on.** The fix
   *    also stopped `excludeHiddenSpans` rewriting a missing parent to `""` as a
   *    side effect of re-pointing hidden ones, so a seed with no hidden span at
   *    all would exercise only half of what changed — and the eye toggle does
   *    not even render on a trace that has none.
   *
   * `orphan`'s parent is a freshly minted UUIDv7 rather than a literal string:
   * `parent_span_id` is a UUID column, so a non-UUID would be rejected on write
   * and the seed would fail rather than produce the row under test.
   *
   * Seeded over REST in two writes, and the fixture then proves its own premise
   * through the API — see `phantomParentId`. Teardown deletes the trace, which
   * takes its spans with it; neither the `project` fixture's delete nor
   * `global-teardown`'s run-prefix sweep would.
   */
  orphanSpanTree: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    let traceId: string | null = null;

    try {
      const minted = uuid7();
      const phantomParentId = uuid7();
      const nameOf: Record<OrphanTreeRole, string> = {
        root: `${testNamespace}-root`,
        rootChild: `${testNamespace}-root-child`,
        orphan: `${testNamespace}-orphan`,
        internal: `${testNamespace}-internal`,
        internalChild: `${testNamespace}-internal-child`,
      };
      const traceName = `${testNamespace}-trace`;

      const rootId = uuid7();
      const internalId = uuid7();
      const plan: Array<{ role: OrphanTreeRole; id: string; parentSpanId: string | null }> = [
        { role: 'root', id: rootId, parentSpanId: null },
        { role: 'rootChild', id: uuid7(), parentSpanId: rootId },
        { role: 'orphan', id: uuid7(), parentSpanId: phantomParentId },
        { role: 'internal', id: internalId, parentSpanId: rootId },
        { role: 'internalChild', id: uuid7(), parentSpanId: internalId },
      ];

      expect(
        new Set([minted, phantomParentId, ...plan.map((s) => s.id)]).size,
        'every id the seed minted is distinct, phantom parent included',
      ).toBe(plan.length + 2);

      // Distinct, increasing start times so the tree builder's sort by
      // `start_time` is deterministic. A seed that wrote them all at one instant
      // would leave sibling order up to the server's tie-break, and the depth
      // map below is read against node names rather than positions precisely so
      // that it does not depend on that — but an unstable order would still make
      // a failure message harder to read than it needs to be.
      const base = new Date();
      const spans: SpanBatchSeed[] = plan.map((span, index) => ({
        id: span.id,
        traceId: minted,
        name: nameOf[span.role],
        type: 'general',
        ...(span.parentSpanId === null ? {} : { parentSpanId: span.parentSpanId }),
        ...(span.role === 'internal' ? { metadata: { ...INTERNAL_SPAN_METADATA } } : {}),
        startTime: new Date(base.getTime() + index * 10),
        endTime: new Date(base.getTime() + index * 10 + 5),
      }));

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: [
          {
            id: minted,
            name: traceName,
            input: { prompt: 'orphan span tree' },
            output: { answer: 'orphan span tree' },
            startTime: base,
            endTime: new Date(base.getTime() + 100),
          },
        ],
      });
      traceId = minted;
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      await expect
        .poll(
          async () =>
            (await backendClient.listSpansPage({ projectId: project.id, traceId: minted })).total,
          {
            message: 'every seeded span must be queryable before the panel is opened',
            timeout: QUERYABLE_TIMEOUT_MS,
            intervals: [1_000, 2_000, 5_000],
          },
        )
        .toBe(plan.length);

      // ---- the fixture proves it can discriminate ----
      //
      // The whole scenario rests on the server having STORED a parent id that
      // resolves to nothing. If it mapped a dangling parent to null — the way it
      // maps a root span's absent one (opik#8595) — then the "orphan" is just a
      // second root span, the tree builder never reaches the branch opik#8797
      // changed, and every assertion in the spec would pass against a build with
      // the bug still in it.
      const read = await backendClient.listSpansPage({
        projectId: project.id,
        traceId: minted,
      });
      expect(
        read.spans.length,
        'the spans read returns as many rows as it counted',
      ).toBe(read.total);
      const byId = new Map(read.spans.map((span) => [span.id, span]));
      const orphan = plan.find((span) => span.role === 'orphan')!;
      expect(
        byId.get(orphan.id)?.parentSpanId,
        'the server stored the orphan\'s dangling parent verbatim — if it nulled it, the ' +
          'span is simply a second root and the tree builder has nothing to decide',
      ).toBe(phantomParentId);
      expect(
        byId.has(phantomParentId),
        'and the phantom parent really is absent from the loaded set',
      ).toBe(false);
      expect(
        read.spans.filter((span) => span.parentSpanId === null).map((span) => span.name),
        'exactly one span has no parent, and it is the seeded root',
      ).toEqual([nameOf.root]);

      const ref: OrphanSpanTreeRef = {
        traceId: minted,
        traceName,
        spans: plan.map((span) => ({
          id: span.id,
          name: nameOf[span.role],
          role: span.role,
          parentSpanId: span.parentSpanId,
        })),
        nameOf,
        phantomParentId,
        totalSpanCount: plan.length,
        // One internal span, so exactly one row leaves the tree when it is
        // hidden. Derived rather than written as 4 so the two stay in step if the
        // seed grows a second hidden span.
        visibleSpanCount: plan.length - plan.filter((s) => s.role === 'internal').length,
      };

      await testInfo.attach('opik.orphanSpanTree', {
        body: JSON.stringify(
          {
            projectId: project.id,
            projectName: project.name,
            traceId: ref.traceId,
            traceName: ref.traceName,
            phantomParentId: ref.phantomParentId,
            totalSpanCount: ref.totalSpanCount,
            visibleSpanCount: ref.visibleSpanCount,
            spans: ref.spans,
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceId !== null) {
        try {
          await backendClient.deleteTraces([traceId]);
        } catch (err) {
          // Never rethrow from teardown: a cleanup failure must not replace the
          // test's own error.
          console.warn('[orphanSpanTree fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './search-population.fixture';
