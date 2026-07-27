import { test, expect } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

test.describe('Experiments comparison — CUJ', { tag: ['@t2-cuj', '@experiments'] }, () => {
  test('two SDK-seeded experiments compare side by side with divergent per-item scores and outputs', async ({
    comparison,
    project,
    page,
  }) => {
    const [expA, expB] = comparison.experiments;
    const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
      expA.experimentId,
      expB.experimentId,
    ]);

    await test.step('Open the compare view in comparison mode', async () => {
      await compare.gotoResults();
      await compare.waitForResultsReady();
      await compare.expectCompareModeHeader(2);
    });

    await test.step('Both seeded experiments are named in the comparison summary', async () => {
      await compare.expectExperimentNamesInSummary([expA.experimentName, expB.experimentName]);
    });

    await test.step('One row per shared dataset item', async () => {
      expect(await compare.countItemRows()).toBe(comparison.items.length);
    });

    await test.step('Per-item scores match the seeded values for each experiment (closed loop)', async () => {
      for (const itemId of comparison.itemIds) {
        const uiA = await compare.readItemScore(itemId, 0, comparison.evaluator.name);
        const uiB = await compare.readItemScore(itemId, 1, comparison.evaluator.name);
        expect(uiA, `experiment A score for item ${itemId}`).toBeCloseTo(expA.scoresByItemId[itemId], 5);
        expect(uiB, `experiment B score for item ${itemId}`).toBeCloseTo(expB.scoresByItemId[itemId], 5);
      }
    });

    await test.step('Per-item evaluation-task outputs match the seeded values for each experiment', async () => {
      for (const itemId of comparison.itemIds) {
        expect(await compare.readItemOutput(itemId, 0), `experiment A output for item ${itemId}`)
          .toBe(expA.outputsByItemId[itemId]);
        expect(await compare.readItemOutput(itemId, 1), `experiment B output for item ${itemId}`)
          .toBe(expB.outputsByItemId[itemId]);
      }
    });

    await test.step('The two experiments disagree on at least one item (comparison is meaningful)', async () => {
      const disagreements = comparison.itemIds.filter(
        (id) => expA.scoresByItemId[id] !== expB.scoresByItemId[id],
      );
      expect(disagreements.length, 'items where the experiments scored differently').toBeGreaterThan(0);
    });

    await test.step('Configuration tab lists each experiment as its own named column', async () => {
      await compare.gotoConfiguration();
      await compare.expectExperimentColumnsInConfiguration([
        { id: expA.experimentId, name: expA.experimentName },
        { id: expB.experimentId, name: expB.experimentName },
      ]);
    });
  });

  test('the grid can be sorted by score and searched by item', async ({ comparison, project, page }) => {
    const [expA, expB] = comparison.experiments;
    const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
      expA.experimentId,
      expB.experimentId,
    ]);

    await test.step('Open the compare view', async () => {
      await compare.gotoResults();
      await compare.waitForResultsReady();
    });

    await test.step('Sorting by score descending puts the highest-aggregate item first', async () => {
      await compare.sortByScoreDescending(comparison.evaluator.name);

      // Per-item aggregate = sum of both experiments' scores. The unique top
      // item is the one where both experiments pass.
      const aggregate = (itemId: string) =>
        expA.scoresByItemId[itemId] + expB.scoresByItemId[itemId];
      const expectedTop = [...comparison.itemIds].sort((a, b) => aggregate(b) - aggregate(a))[0];

      const order = await compare.itemRowOrder();
      expect(order[0], 'top row after sorting by score descending').toBe(expectedTop);
    });

    await test.step('Searching for one item narrows the grid to just that item', async () => {
      const target = comparison.items[0];
      const targetId = comparison.itemIds[0];
      await compare.searchItems(target.input);
      expect(await compare.countItemRows(), `rows after searching "${target.input}"`).toBe(1);
      expect((await compare.itemRowOrder())[0], 'the single matching row').toBe(targetId);
    });
  });

  test('feedback scores tab shows each experiment aggregate, and they differ', async ({
    comparison,
    project,
    page,
  }) => {
    const [expA, expB] = comparison.experiments;
    const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
      expA.experimentId,
      expB.experimentId,
    ]);

    await test.step('Open the Feedback scores tab', async () => {
      await compare.gotoFeedbackScores();
    });

    await test.step('Each experiment aggregate matches the seeded mean', async () => {
      expect(await compare.readAggregateScore(expA.experimentId), 'experiment A aggregate')
        .toBeCloseTo(expA.aggregateScore, 1);
      expect(await compare.readAggregateScore(expB.experimentId), 'experiment B aggregate')
        .toBeCloseTo(expB.aggregateScore, 1);
    });

    await test.step('The aggregates differ (the comparison distinguishes the experiments)', async () => {
      expect(expA.aggregateScore, 'seed sanity: aggregates differ')
        .not.toBeCloseTo(expB.aggregateScore, 1);
    });
  });

  test('the row detail panel shows both experiments side by side for one item', async ({
    comparison,
    project,
    page,
  }) => {
    const [expA, expB] = comparison.experiments;
    const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
      expA.experimentId,
      expB.experimentId,
    ]);

    // Pick an item where the two experiments disagree, so the panel visibly
    // contrasts them rather than showing two identical results.
    const itemId = comparison.itemIds.find(
      (id) => expA.scoresByItemId[id] !== expB.scoresByItemId[id],
    )!;

    await test.step('Open the compare view and the item detail panel', async () => {
      await compare.gotoResults();
      await compare.waitForResultsReady();
      await compare.openRowPanel(itemId);
    });

    await test.step('The panel shows each experiment output and score', async () => {
      await compare.expectPanelExperimentResult(expA.experimentName, {
        output: expA.outputsByItemId[itemId],
        score: expA.scoresByItemId[itemId],
        metricName: comparison.evaluator.name,
      });
      await compare.expectPanelExperimentResult(expB.experimentName, {
        output: expB.outputsByItemId[itemId],
        score: expB.scoresByItemId[itemId],
        metricName: comparison.evaluator.name,
      });
    });
  });
});
