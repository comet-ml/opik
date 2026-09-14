import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * The Playground output grid is row-virtualized, so only a window of rows is mounted and
 * row position depends on the table's measured offset inside the page scroller. A wrong
 * offset or a null scroll element shows up as a blank band, a header out of line with the
 * body, or rows that cannot be reached by scrolling — none of which the 3-item smoke can
 * see, because 3 rows render fully either way.
 *
 * No model or provider key is needed: selecting a dataset paints the idle output rows from
 * the dataset-items query alone, which is the whole surface under test here.
 *
 * Nothing here assumes a dataset ordering — the grid renders items newest-first, and that
 * is incidental to virtualization.
 */

const ITEM_COUNT = 100;
/** Generous upper bound on the virtual window — the point is "far fewer than ITEM_COUNT". */
const MAX_MOUNTED_ROWS = 40;

test.describe(
  'Playground — virtualized output grid',
  { tag: ['@t2-cuj', '@area:playground', '@cap:playground.run-against-dataset'] },
  () => {
    test('Large dataset mounts a bounded row window and stays reachable by scrolling', async ({
      sdkClient,
      backendClient,
      project,
      testNamespace,
      page,
    }) => {
      test.setTimeout(180_000);

      const items = Array.from({ length: ITEM_COUNT }, (_, i) => ({
        input: `row-${String(i).padStart(3, '0')} input`,
        expected_output: `row-${String(i).padStart(3, '0')} expected`,
      }));

      const created = await sdkClient.python.createDataset({
        project_name: project.name,
        name: `${testNamespace}-virtualized-ds`,
        description: 'large dataset for output-grid virtualization',
        items: items as unknown as Array<Record<string, unknown>>,
      });

      const playground = new PlaygroundPage(page, project.id);

      try {
        await test.step('Load the dataset into the Playground', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.clickRunExperiment();
          await playground.selectRunExperimentSource({ mode: 'dataset', entityName: created.name });
          await expect(playground.loadedSourcePill()).toBeVisible();
          // Idle output rows paint from the items query — no run, no LLM call.
          await playground.waitForRunReady({ expectedRows: 1 });
        });

        let topRows: string[] = [];

        await test.step('Only a window of rows is mounted', async () => {
          await playground.scrollResultsTo(0);
          topRows = await playground.mountedRowLabels();

          expect(topRows.length).toBeGreaterThan(0);
          expect(topRows.length).toBeLessThanOrEqual(MAX_MOUNTED_ROWS);
          // The whole dataset is not in the DOM — that is the point of the change.
          expect(topRows.length).toBeLessThan(ITEM_COUNT);
        });

        await test.step('Scrolling to the end reveals rows that were never mounted', async () => {
          await playground.scrollResultsTo(1);
          const bottomRows = await playground.mountedRowLabels();

          expect(bottomRows.length).toBeGreaterThan(0);
          // Still bounded — the window moved rather than accumulating.
          expect(bottomRows.length).toBeLessThanOrEqual(MAX_MOUNTED_ROWS);
          // Rows unreachable by scrolling is the failure this guards against.
          expect(bottomRows.some((label) => !topRows.includes(label))).toBe(true);
          // A stale table offset renders the window away from the viewport, leaving a gap.
          expect(await playground.hasBlankBandAboveRows()).toBe(false);
        });

        await test.step('Sticky header stays aligned with the body after scrolling', async () => {
          expect(await playground.headerBodyColumnsAligned()).toBe(true);
        });

        await test.step('Changing page size recomputes the window', async () => {
          await playground.setPageSize(50);
          await playground.scrollResultsTo(1);
          const halved = await playground.mountedRowLabels();

          expect(halved.length).toBeGreaterThan(0);
          expect(halved.length).toBeLessThanOrEqual(MAX_MOUNTED_ROWS);
          expect(await playground.hasBlankBandAboveRows()).toBe(false);

          await playground.setPageSize(100);
          await playground.scrollResultsTo(1);

          expect((await playground.mountedRowLabels()).length).toBeGreaterThan(0);
          expect(await playground.hasBlankBandAboveRows()).toBe(false);
        });
      } finally {
        await backendClient.deleteDataset(created.id).catch(() => undefined);
      }
    });
  },
);
