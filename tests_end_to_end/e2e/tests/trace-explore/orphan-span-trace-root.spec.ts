import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A span whose parent is not in the loaded set (opik#8797 · issue-5934).
 *
 * `TraceTreeViewer` built its tree by looking each span's `parent_span_id` up in
 * the nodes it had just created. A span naming a parent that is not there —
 * dropped by a filter, lost to a partial read, written by a producer whose
 * parent span never arrived — matched neither the "no parent" branch nor the
 * "parent found" one, and was simply never attached. It then rendered NOWHERE,
 * while the "Spans (n)" label above the tree went on counting it. From a user's
 * side: the panel says five spans and shows four, with no error.
 *
 * The estate could not have caught that. `trace-spans-depth.spec.ts` and
 * `span-read-mapping-and-parent-guard.spec.ts` both seed fully-connected trees,
 * so neither has an orphan to lose, and both pass whether or not the fix is in
 * place. The PR's own coverage is a `hiddenSpans.test.ts` unit test over
 * `excludeHiddenSpans`, which is only half of the change: the tree BUILDER is
 * the other half, and it is only reachable through a rendered panel.
 *
 * **Which half of this spec discriminates.** The two tree modes are not equally
 * interesting. Before the fix, `excludeHiddenSpans` rewrote a missing parent to
 * `""` as an incidental side effect of re-pointing hidden spans, so in HIDE mode
 * the orphan reached the root anyway and the panel looked correct. It was
 * "show all" — the mode that skips `excludeHiddenSpans` entirely — where the
 * span disappeared. The show-all test below is therefore the regression test;
 * the hiding one guards the side effect the fix deliberately removed (parents
 * are now kept rather than blanked), which would otherwise be free to break
 * unobserved.
 *
 * Depth is asserted as a map over the WHOLE tree, not as a claim about the
 * orphan alone. "The orphan is at the root" is also true of a build that
 * flattened every span to the root, which is the opposite defect — so the
 * sibling that must stay nested is asserted in the same breath. See
 * `TracePanelPage.spanTreeDepths` for why depth is measured from padding rather
 * than from position.
 */
test.describe(
  'Trace spans — a span whose parent is not loaded',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    /** A batch write, an ingestion poll and two panel renders. */
    test.setTimeout(300_000);

    test(
      'renders at the trace root with every span shown, and the count agrees with the tree',
      { tag: ['@cap:traces.orphan-span-at-trace-root'] },
      async ({ orphanSpanTree, project, page }) => {
        const tree = orphanSpanTree;
        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace', async () => {
          await logs.goto(project.id);
          const panel = await logs.openTraceById(tree.traceId);
          await panel.waitForFullyLoaded();
          return panel;
        });

        await test.step('Switch the tree to showing every span', async () => {
          // The panel opens hiding internal spans (`useHideSpansPreference`
          // defaults to true), and `setHiddenSpansMode` asserts that starting
          // state before it clicks — so a profile that had persisted the other
          // mode fails here rather than silently inverting both assertions
          // below.
          await panel.setHiddenSpansMode('showing-all');
        });

        await test.step(`The panel counts all ${tree.totalSpanCount} spans`, async () => {
          await expect(
            panel.spansCountLabel(tree.totalSpanCount),
            'with nothing hidden, the label reports the whole trace',
          ).toBeVisible();
        });

        await test.step('The orphan sits at the trace root, beside the real root span', async () => {
          const depths = await panel.spanTreeDepths();

          // Asserted whole. The orphan's own depth is the headline, but a map
          // comparison is what makes the other failure modes visible in the same
          // assertion: a missing key is the span that vanished (the regression),
          // an extra key is a span rendered twice, and every sibling's depth
          // being 1 would be a flattened tree passing a check that only looked
          // at the orphan.
          expect(
            depths,
            'the tree holds the trace, its root span and the root span\'s subtree, ' +
              'with the orphan re-homed one level under the trace',
          ).toEqual({
            [tree.traceName]: 0,
            [tree.nameOf.root]: 1,
            [tree.nameOf.rootChild]: 2,
            [tree.nameOf.internal]: 2,
            [tree.nameOf.internalChild]: 3,
            // THE assertion. Same depth as the real root span, under the trace
            // node — not absent, which is what this build is being asked about.
            [tree.nameOf.orphan]: 1,
          });
        });

        await test.step('Every seeded span is on screen, the orphan included', async () => {
          // Stated as visibility as well as as depth: `spanTreeDepths` reads
          // mounted nodes, and a node mounted but clipped to zero height would
          // satisfy the map while showing the user nothing.
          for (const span of tree.spans) {
            await expect(
              panel.spanTreeNode(span.name),
              `${span.role} span '${span.name}' renders in the tree`,
            ).toBeVisible();
          }
        });
      },
    );

    test(
      'stays at the trace root once internal spans are hidden, with its own child re-parented',
      { tag: ['@cap:traces.orphan-span-at-trace-root'] },
      async ({ orphanSpanTree, project, page }) => {
        const tree = orphanSpanTree;
        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace', async () => {
          await logs.goto(project.id);
          const panel = await logs.openTraceById(tree.traceId);
          await panel.waitForFullyLoaded();
          return panel;
        });

        await test.step('The tree opens hiding the span the SDK marked internal', async () => {
          await expect(
            panel.hiddenSpansToggle('hiding'),
            'the eye toggle renders and reports the hiding mode, which is the default — ' +
              'it appears at all only because the trace carries an internal span',
          ).toHaveCount(1);
          await expect(
            panel.spansCountLabel(tree.visibleSpanCount),
            `the label counts the ${tree.visibleSpanCount} spans still shown, not the ` +
              `${tree.totalSpanCount} the trace holds`,
          ).toBeVisible();
        });

        await test.step('The orphan is still under the trace, and the hidden span\'s child moved up', async () => {
          const depths = await panel.spanTreeDepths();

          expect(
            depths,
            'the internal span is gone, its child hangs off the root in its place, and the ' +
              'orphan is unmoved',
          ).toEqual({
            [tree.traceName]: 0,
            [tree.nameOf.root]: 1,
            [tree.nameOf.rootChild]: 2,
            // Re-pointed to its nearest VISIBLE ancestor rather than dropped
            // with its parent: `resolveVisibleParent` walks up past the hidden
            // span to the root, so this child rises from depth 3 to depth 2.
            [tree.nameOf.internalChild]: 2,
            // Unchanged from the show-all tree. The fix stopped
            // `excludeHiddenSpans` blanking an unresolvable parent, so this now
            // arrives at the root through the tree builder's own fallback rather
            // than through that side effect — the same place, by the path both
            // modes share.
            [tree.nameOf.orphan]: 1,
          });

          await expect(
            panel.spanTreeNode(tree.nameOf.internal),
            'the internal span itself is not rendered while the tree is hiding',
          ).toHaveCount(0);
        });

        await test.step('Showing all spans brings back exactly the hidden one', async () => {
          await panel.setHiddenSpansMode('showing-all');
          await expect(
            panel.spansCountLabel(tree.totalSpanCount),
            'the count returns to the whole trace',
          ).toBeVisible();
          await expect(
            panel.spanTreeNode(tree.nameOf.internal),
            'and the internal span is back on screen',
          ).toBeVisible();
          // The round trip, not just the end state: the orphan surviving BOTH
          // transitions is what "both modes take the same path" means, and a
          // build that re-introduced a mode-specific branch would drop it on one
          // side of the toggle only.
          await expect(
            panel.spanTreeNode(tree.nameOf.orphan),
            'the orphan survived the round trip through both modes',
          ).toBeVisible();
        });
      },
    );
  },
);
