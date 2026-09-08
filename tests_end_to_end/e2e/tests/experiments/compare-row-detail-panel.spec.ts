import { test, expect } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * Two properties of the experiment-comparison row-detail panel that hold across
 * the panel's own layout rather than inside any one section (OPIK-8058).
 *
 * `experiments.compare-row-detail` is already covered by experiments-compare,
 * but only for the *contents* of each section — `panelExperimentSection()`
 * finds a section by its h2 name, so it passes whichever way round the two
 * sections render, and says nothing about the panel's layout surviving
 * anything. Both assertions below are about that layout.
 *
 * ## Why order needs asserting in both directions
 *
 * The compare endpoint returns `experiment_items` in an order that is not
 * specified: `DatasetItemDAO.SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS`
 * aggregates them with a bare `groupArray(...)` and no `ORDER BY` inside the
 * aggregate, so the order is a query-plan artefact — ten identical calls during
 * exploration returned B,A seven times and A,B three times. The panel pins
 * itself to the `experiments` URL array instead, and that sort is the only
 * thing standing between the user and a panel whose columns swap on reload.
 *
 * Asserting one direction would therefore pass roughly a third of the time on
 * an unsorted panel, at random. Asserting the *same row* both ways round cannot:
 * whichever way the API answered, at least one of the two passes is a
 * re-ordering, so a regressed sort key fails one of them every run.
 *
 * ## Why the resize assertion clicks rather than navigates
 *
 * The panel's layout is persisted by react-resizable-panels under an
 * `autoSaveId` whose storage key is derived from the panel set. Stepping to the
 * next dataset item must be an in-place arrow click: a URL reload would restore
 * the saved layout from localStorage and pass whether or not the panels held
 * their widths. The test asserts it never reloaded, so that premise cannot rot
 * silently.
 */

/** How far to drag the first divider. Comfortably past any min-width floor. */
const DRAG_PX = 200;

test.describe('Experiment comparison — row-detail panel layout', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test(
    'the panel orders its experiment sections by the experiments URL array, both ways round',
    { tag: ['@cap:experiments.compare-row-detail'] },
    async ({ comparison, project, page }) => {
      const [expA, expB] = comparison.experiments;

      // An item the two experiments disagree on, so each section has visibly
      // different content and a mis-paired heading can't hide behind identical
      // outputs.
      const itemId = comparison.itemIds.find(
        (id) => expA.scoresByItemId[id] !== expB.scoresByItemId[id],
      );
      expect(itemId, 'seed sanity: an item the two experiments scored differently').toBeDefined();

      const openPanelFor = async (ordered: typeof comparison.experiments) => {
        const compare = new CompareExperimentsPage(
          page,
          project.id,
          comparison.datasetId,
          ordered.map((e) => e.experimentId),
        );
        await compare.gotoResults();
        await compare.waitForResultsReady();
        await compare.openRowPanel(itemId!);
        return compare;
      };

      await test.step('With experiments=[A,B] the panel reads A then B', async () => {
        const compare = await openPanelFor([expA, expB]);
        await compare.expectPanelExperimentOrder([expA.experimentName, expB.experimentName]);
      });

      await test.step('The same row with experiments=[B,A] reads B then A', async () => {
        const compare = await openPanelFor([expB, expA]);
        await compare.expectPanelExperimentOrder([expB.experimentName, expA.experimentName]);

        // Order alone would still pass if the sections were re-ordered but
        // mis-paired with their contents, so tie each heading back to the
        // output and score that belong under it.
        for (const exp of [expB, expA]) {
          await compare.expectPanelExperimentResult(exp.experimentName, {
            output: exp.outputsByItemId[itemId!],
            score: exp.scoresByItemId[itemId!],
            metricName: comparison.evaluator.name,
          });
        }
      });
    },
  );

  test(
    'a dragged panel width survives stepping to the next dataset item',
    { tag: ['@cap:experiments.compare-row-detail'] },
    async ({ comparison, project, page }) => {
      const [expA, expB] = comparison.experiments;
      const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
        expA.experimentId,
        expB.experimentId,
      ]);

      let firstItemId = '';
      let secondItemId = '';

      await test.step('Open the compare grid and take the rendered row order', async () => {
        await compare.gotoResults();
        await compare.waitForResultsReady();

        // Read the order before the panel opens: the panel's own score table
        // also renders `tr[data-row-id]`, so itemRowOrder() would pick those up
        // once it's on screen.
        const rowOrder = await compare.itemRowOrder();
        expect(rowOrder, 'the grid renders every seeded item exactly once')
          .toEqual(expect.arrayContaining(comparison.itemIds));
        expect(rowOrder, 'and nothing else').toHaveLength(comparison.itemIds.length);

        // The first row leaves Next enabled; after one step, Previous is too.
        [firstItemId, secondItemId] = rowOrder;
      });

      await test.step('Open the detail panel on the first row', async () => {
        await compare.openRowPanel(firstItemId);
        await compare.expectPanelExperimentOrder([expA.experimentName, expB.experimentName]);
      });

      let draggedLayout: string[] = [];

      await test.step('Dragging the first divider resizes the panels', async () => {
        expect(await compare.countPanelDividers(), 'dividers for a two-experiment comparison').toBe(2);

        const initialLayout = await compare.panelLayout();
        expect(initialLayout, 'dataset panel plus one per compared experiment').toHaveLength(3);

        draggedLayout = await compare.dragPanelDivider(0, DRAG_PX);
        expect(draggedLayout, 'the drag actually moved the divider').not.toEqual(initialLayout);
      });

      // Everything below is worthless if the page reloads, because the layout
      // would be restored from localStorage rather than held in place. Stamp
      // the window and check the stamp survives.
      await test.step('Mark the document so a reload would be detectable', async () => {
        await page.evaluate(() => {
          (window as Window & { __opikSameDocument?: boolean }).__opikSameDocument = true;
        });
      });

      await test.step('Stepping to the next item keeps the dragged widths', async () => {
        await compare.goToNextRow(secondItemId);

        // Confirm the panel really moved on rather than just the URL: the two
        // items carry different outputs for the same experiment.
        await compare.expectPanelExperimentResult(expA.experimentName, {
          output: expA.outputsByItemId[secondItemId],
          score: expA.scoresByItemId[secondItemId],
          metricName: comparison.evaluator.name,
        });

        expect(await compare.panelLayout(), 'panel layout after Next').toEqual(draggedLayout);
      });

      await test.step('Stepping back keeps them too', async () => {
        await compare.goToPreviousRow(firstItemId);

        await compare.expectPanelExperimentResult(expA.experimentName, {
          output: expA.outputsByItemId[firstItemId],
          score: expA.scoresByItemId[firstItemId],
          metricName: comparison.evaluator.name,
        });

        expect(await compare.panelLayout(), 'panel layout after Previous').toEqual(draggedLayout);
      });

      await test.step('Neither step reloaded the page', async () => {
        const sameDocument = await page.evaluate(
          () => (window as Window & { __opikSameDocument?: boolean }).__opikSameDocument === true,
        );
        expect(sameDocument, 'arrow navigation must not reload the document').toBe(true);
      });
    },
  );
});
