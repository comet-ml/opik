import { test, expect } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/** Computed `background-color` for an element that has none. */
const TRANSPARENT = 'rgba(0, 0, 0, 0)';

/**
 * The compare grid's LAYOUT, which is what opik#8510 changed — as distinct from
 * its data, which experiments-compare.spec.ts already covers.
 *
 * #8510 pins the experiment-name column beside the select column so a reader
 * scrolling right keeps each sub-row tied to the experiment it belongs to, and
 * makes hovering one experiment's sub-row highlight that sub-row alone. Two
 * details make it more than cosmetic:
 *
 *  - an inset box-shadow paints BENEATH its element's children, so a hovered
 *    band with a background of its own would hide the pinned column's border.
 *    `main.scss` therefore extends the shadow rule to
 *    `.comet-pinned-last-left [data-virtual-row-id]` as well — a band inside the
 *    pinned cell draws the border itself.
 *  - `VerticallySplitCellWrapper` also paints the row's SHARED cells (select,
 *    item id, dataset fields) with the hovered experiment's colour, so "isolated"
 *    means isolated from the sibling BAND, not from the rest of the row.
 *
 * Asserted as computed style and geometry, never as a pixel baseline: the visual
 * suite owns screenshots, and a baseline here would fail on every unrelated
 * palette or spacing change. The scss and DataTable edits reach every table in
 * the app, which is why this is worth a permanent spec rather than one look.
 */
test.describe(
  'Experiments comparison — pinned name column',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'the experiment-name column stays put while the grid scrolls, and keeps its border',
      { tag: ['@cap:experiments.compare-name-pinning'] },
      async ({ comparison, project, page }) => {
        const [expA, expB] = comparison.experiments;
        const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
          expA.experimentId,
          expB.experimentId,
        ]);
        const rowIds = comparison.itemIds;

        await test.step('Open the compare Results tab', async () => {
          await compare.gotoResults();
          await compare.waitForResultsReady();
          await compare.expectCompareModeHeader(2);
          expect(await compare.countItemRows(), 'one row per shared dataset item').toBe(
            comparison.items.length,
          );
        });

        const before = await test.step('Record where the Name cells are, and which headers are on screen', async () => {
          return {
            edges: await compare.readNameCellLeftEdges(rowIds),
            headers: await compare.readHeaderIdsInView(),
          };
        });

        await test.step('Scroll the grid to its right-hand end', async () => {
          const scrolled = await compare.scrollGridToEnd();
          // Proves the scroll happened before anything is concluded from it. A
          // grid whose columns all fit never moves, and "the Name cells did not
          // move" is then true of every build ever shipped — the assertion would
          // read as coverage while testing nothing at all.
          expect(scrolled, 'horizontal distance the grid scrolled').toBeGreaterThan(0);
        });

        await test.step('The columns really slid past', async () => {
          const headers = await compare.readHeaderIdsInView();
          // The other half of proving the scroll: `scrollLeft > 0` says the
          // container moved, this says the user is now looking at different
          // columns.
          expect(headers, 'the headers on screen after scrolling').not.toEqual(before.headers);
        });

        await test.step('...but every Name cell is exactly where it was', async () => {
          // The claim, per row rather than in aggregate: the cells are separate
          // sticky elements and a mean would hide one that came unstuck.
          expect(await compare.readNameCellLeftEdges(rowIds), 'Name cell left edges').toEqual(
            before.edges,
          );
        });

        await test.step('The pinned column marks itself, on every row and its header', async () => {
          // The class is the contract `main.scss` hangs the border off, so its
          // count is worth pinning: one per body row plus the header row. A build
          // that pinned the wrong column, or stopped pinning, fails here with a
          // number rather than through a downstream style assertion.
          await expect(
            compare.pinnedLastLeftCells,
            'cells marked as the last left-pinned column',
          ).toHaveCount(rowIds.length + 1);
        });

        await test.step('The border is drawn by the cell AND by each band inside it', async () => {
          const borders = await compare.readNameCellBorders(rowIds[0], 2);
          // `-1px 0px 0px 0px inset` is the offset/spread signature of the rule;
          // the colour is left out of the match deliberately, because
          // `hsl(var(--border))` is a theme token and re-tinting it is not a
          // regression.
          expect(borders.cell, 'the pinned cell draws the separating border').toMatch(
            /-1px 0px 0px 0px inset/,
          );
          borders.bands.forEach((shadow, index) => {
            expect(shadow, `sub-row #${index} draws the border too`).toMatch(
              /-1px 0px 0px 0px inset/,
            );
          });
        });
      },
    );

    test(
      'hovering one experiment sub-row highlights that sub-row and not its sibling',
      { tag: ['@cap:experiments.compare-sub-row-hover'] },
      async ({ comparison, project, page }) => {
        const [expA, expB] = comparison.experiments;
        const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
          expA.experimentId,
          expB.experimentId,
        ]);
        const rowId = comparison.itemIds[0];

        await test.step('Open the compare Results tab', async () => {
          await compare.gotoResults();
          await compare.waitForResultsReady();
          await compare.expectCompareModeHeader(2);
        });

        await test.step('Nothing is highlighted before the pointer arrives', async () => {
          // The baseline. Without it, "the hovered band is coloured" is satisfied
          // by a band that was coloured all along, and the isolation assertion
          // below would be comparing two constants.
          for (const index of [0, 1]) {
            await expect(
              compare.subRow(rowId, index),
              `sub-row #${index} before any hover`,
            ).toHaveCSS('background-color', TRANSPARENT);
          }
        });

        await test.step(`Hovering ${expA.experimentName}'s band highlights it alone`, async () => {
          const [hovered, sibling] = await compare.hoverSubRowAndReadBandColours(rowId, 0, 2);

          expect(hovered, 'the hovered sub-row is painted').not.toBe(TRANSPARENT);
          expect(sibling, "the sibling experiment's sub-row is left alone").toBe(TRANSPARENT);
          // Stated directly as well as through the two comparisons above, so the
          // failure message names the actual defect rather than a colour.
          expect(hovered, 'the two sub-rows are painted differently').not.toBe(sibling);
        });

        await test.step(`Moving to ${expB.experimentName}'s band moves the highlight with it`, async () => {
          // The mirror image, and what makes this about the hovered band rather
          // than about band 0: the highlight has to LEAVE the first sub-row, which
          // is a different code path (`onMouseLeave`) from the one that applied it.
          const [sibling, hovered] = await compare.hoverSubRowAndReadBandColours(rowId, 1, 2);

          expect(hovered, "the second experiment's sub-row is painted").not.toBe(TRANSPARENT);
          expect(sibling, 'the first sub-row gave the highlight up').toBe(TRANSPARENT);
        });
      },
    );
  },
);
