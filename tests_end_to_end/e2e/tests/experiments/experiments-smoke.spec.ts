import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

test.describe('Experiments — smoke', { tag: ['@t1-smoke', '@experiments'] }, () => {
  test('SDK-seeded experiment renders in list and shows per-item deterministic scores', async ({
    experiment,
    project,
    page,
  }) => {
    const experiments = new ExperimentsPage(page);

    await test.step('Experiment row appears on the list page', async () => {
      await experiments.goto(project.id);
      await experiments.waitForReady();
      expect(await experiments.countExperiments()).toBe(1);
      await experiments.expectExperimentNameInList(experiment.experimentId, experiment.experimentName);
    });

    const detail = await test.step('Open the experiment detail page', async () => {
      return experiments.openExperimentById(experiment.experimentId);
    });

    await test.step('Detail page renders all seeded items', async () => {
      await detail.waitForReady();
      expect(await detail.countItems()).toBe(experiment.items.length);
    });

    await test.step('Per-item scores match the bridge response (closed-loop on datasetItemId)', async () => {
      for (const seedScore of experiment.scores) {
        const uiScore = await detail.readItemScore(seedScore.datasetItemId, experiment.evaluator.name);
        expect(uiScore, `score for item "${seedScore.input}" (id=${seedScore.datasetItemId})`)
          .toBeCloseTo(seedScore.scoreValue, 5);
      }
    });

    await test.step('Aggregate score chip reflects 2/3 pass rate', async () => {
      const aggregate = await detail.readAggregateScore();
      expect(aggregate, 'aggregate chip = mean of per-item scores').toBeCloseTo(2 / 3, 1);
    });

    await test.step('Seed shape is 2-pass-1-fail (sanity)', async () => {
      expect(experiment.scores.filter((s) => s.scoreValue === 1.0), 'pass rows').toHaveLength(2);
      expect(experiment.scores.filter((s) => s.scoreValue === 0.0), 'fail rows').toHaveLength(1);
    });
  });
});
