import {
  test,
  expect,
  datasetFieldValue,
  metricScoreValue,
  taskOutputValue,
  type WideComparisonRef,
} from '@e2e/fixtures';
import {
  CompareExperimentsPage,
  COLUMN_SPACER,
  type ResultsGridScan,
} from '@e2e/pom/compare-experiments.page';
import type { BackendClient } from '@e2e/core/backend';
import type { Page } from '@playwright/test';

/**
 * The compare Results grid windows its columns: only a slice of them is in the
 * DOM at any horizontal scroll offset, and the two-row header is re-spanned
 * per window so each section title covers exactly the leaves of its own it can
 * currently see.
 *
 * Both failure modes this covers look like a healthy table on screen: a wrong
 * colSpan puts "Feedback scores" over the evaluation-task columns, and a
 * dropped column shears every value one place. So the assertions are about
 * alignment and value identity across the whole scroll range, not about the
 * page having rendered.
 *
 * The estate's `comparison` fixture cannot reach this: 3 items / 2 experiments
 * / 1 metric barely overflows one viewport, so its grid never leaves the first
 * window. Hence the wider `wideComparison` seed.
 */

/** Section titles the grid groups its columns under, exactly as rendered. */
const SECTION_DATASET = 'Dataset';
const SECTION_EXPERIMENTS = 'Experiments';
const SECTION_EVALUATION = 'Evaluation task (last trial)';
const SECTION_SCORES = 'Feedback scores';
/** Columns that sit in no section at all render an empty group-header cell. */
const NO_SECTION = '';

/**
 * Column ids as the table states them. The grid builds its dynamic columns from
 * accessor keys `data.<field>`, `output.<key>` and `feedback_scores.<metric>`,
 * and the table derives a column id by replacing that first dot with an
 * underscore — which is what lands in `data-cell-id`.
 */
const datasetColumnId = (field: string): string => `data_${field}`;
const outputColumnId = (key: string): string => `output_${key}`;
const scoreColumnId = (metric: string): string => `feedback_scores_${metric}`;

/** Which section owns a leaf column. This is the contract the header asserts against. */
function sectionOf(columnId: string): string {
  if (columnId === 'select' || columnId === 'passed') return NO_SECTION;
  if (columnId === 'id' || columnId.startsWith('data_')) return SECTION_DATASET;
  if (columnId === 'experiment_name') return SECTION_EXPERIMENTS;
  if (columnId.startsWith('feedback_scores_')) return SECTION_SCORES;
  // Everything else the grid shows per experiment — the task output, duration,
  // token usage, cost, comments — belongs to the evaluation-task section.
  return SECTION_EVALUATION;
}

/**
 * Scroll offsets covering the grid end to end, stepping by half a viewport so
 * consecutive windows overlap and no column can slip between two scans unseen.
 */
function sweepOffsets(scan: ResultsGridScan): number[] {
  const maxScroll = scan.scrollWidth - scan.clientWidth;
  const step = Math.max(Math.floor(scan.clientWidth / 2), 1);
  const offsets: number[] = [];
  for (let offset = 0; offset < maxScroll; offset += step) offsets.push(offset);
  offsets.push(maxScroll);
  return offsets;
}

const compareFor = (page: Page, seed: WideComparisonRef): CompareExperimentsPage =>
  new CompareExperimentsPage(
    page,
    seed.projectId,
    seed.datasetId,
    seed.experiments.map((e) => e.experimentId),
  );

/**
 * Assert through the API that the seed really holds before opening a browser.
 * The comparison read snapshots feedback scores when an experiment item is
 * linked, so a seeding order slip yields a grid full of dashes — and a UI
 * assertion over that fixture would be a test that cannot fail.
 */
async function expectSeedIsServed(
  backendClient: BackendClient,
  seed: WideComparisonRef,
): Promise<void> {
  const { items, datasetColumnNames } = await backendClient.getCompareItems({
    datasetId: seed.datasetId,
    experimentIds: seed.experiments.map((e) => e.experimentId),
  });

  expect(items.map((i) => i.id).sort(), 'compare read returns exactly the seeded items').toEqual(
    [...seed.itemIds].sort(),
  );
  expect(
    [...datasetColumnNames].sort(),
    'compare read reports exactly the seeded dataset columns',
  ).toEqual([...seed.datasetFields].sort());

  for (const item of items) {
    const itemIndex = seed.itemIds.indexOf(item.id);
    expect(
      item.experimentItems.map((ei) => ei.experimentId).sort(),
      `item ${item.id} carries a result from every experiment`,
    ).toEqual(seed.experiments.map((e) => e.experimentId).sort());

    for (const experimentItem of item.experimentItems) {
      const experimentIndex = seed.experiments.findIndex(
        (e) => e.experimentId === experimentItem.experimentId,
      );
      expect(
        experimentItem.output[seed.outputKey],
        `task output for item ${itemIndex} / experiment ${experimentIndex}`,
      ).toBe(taskOutputValue(experimentIndex, itemIndex));
      expect(
        Object.keys(experimentItem.feedbackScores).sort(),
        `scores present for item ${itemIndex} / experiment ${experimentIndex}`,
      ).toEqual([...seed.metricNames].sort());
      for (const [metricIndex, metric] of seed.metricNames.entries()) {
        expect(
          experimentItem.feedbackScores[metric],
          `score ${metric} for item ${itemIndex} / experiment ${experimentIndex}`,
        ).toBeCloseTo(metricScoreValue(metricIndex, experimentIndex, itemIndex), 2);
      }
    }
  }
}

/** Open the Results tab and wait until the grid is as wide as it will get. */
async function openWideGrid(
  compare: CompareExperimentsPage,
  seed: WideComparisonRef,
): Promise<ResultsGridScan> {
  await compare.gotoResults();
  await compare.waitForResultsReady();
  // The score columns arrive on a later request than the rows; the last of them
  // being reachable is the signal that no more columns are coming.
  await compare.waitForRightmostColumn(
    scoreColumnId(seed.metricNames[seed.metricNames.length - 1]),
  );

  const scan = await compare.scrollResultsGridTo(0);
  expect(
    scan.scrollWidth,
    'the seeded grid is wider than its scroller (otherwise nothing is virtualized)',
  ).toBeGreaterThan(scan.clientWidth);
  expect(
    scan.columnSlots,
    'columns are withheld at scrollLeft 0 — a spacer stands in for them',
  ).toContain(COLUMN_SPACER);
  return scan;
}

test.describe(
  'Experiments comparison — wide grid column virtualization',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'every section header spans exactly its own columns across the full horizontal scroll range',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ wideComparison, backendClient, page }) => {
        const compare = compareFor(page, wideComparison);

        await test.step('The comparison the grid will be served really holds', async () => {
          await expectSeedIsServed(backendClient, wideComparison);
        });

        const first = await test.step('Open the Results grid in comparison mode', async () =>
          openWideGrid(compare, wideComparison));

        await test.step('At every scroll offset each column sits under the section that owns it', async () => {
          for (const offset of sweepOffsets(first)) {
            const scan = await compare.scrollResultsGridTo(offset);
            const problems: string[] = [];

            expect(
              scan.slotCounts.headerRows.length,
              `header rows at scrollLeft ${offset} (a grouped header is two rows)`,
            ).toBe(2);

            const slotCounts = [
              scan.slotCounts.colgroup,
              ...scan.slotCounts.headerRows,
              ...scan.slotCounts.bodyRows,
            ];
            for (const count of slotCounts) {
              if (count !== scan.columnSlots.length) {
                problems.push(
                  `column-slot counts disagree: colgroup/header/body = ${JSON.stringify(scan.slotCounts)}, body row slots = ${scan.columnSlots.length}`,
                );
                break;
              }
            }

            if (scan.groupSlots.length !== scan.columnSlots.length) {
              problems.push(
                `section header row spans ${scan.groupSlots.length} slots but the columns occupy ${scan.columnSlots.length}`,
              );
            } else {
              scan.columnSlots.forEach((columnId, slot) => {
                const section = scan.groupSlots[slot];
                if (columnId === COLUMN_SPACER || section === COLUMN_SPACER) {
                  if (columnId !== section) {
                    problems.push(
                      `slot ${slot}: spacer misaligned — section "${section}" over column "${columnId}"`,
                    );
                  }
                  return;
                }
                const expected = sectionOf(columnId);
                if (section !== expected) {
                  problems.push(
                    `slot ${slot}: column "${columnId}" sits under "${section}", expected "${expected}"`,
                  );
                }
              });
            }

            // A section renders as one run of slots. Two runs of the same title
            // means the header was emitted twice for one group.
            const runs = scan.groupSlots.filter(
              (title, index) => title !== scan.groupSlots[index - 1],
            );
            const seen = new Set<string>();
            for (const title of runs) {
              if (title === COLUMN_SPACER || title === NO_SECTION) continue;
              if (seen.has(title)) problems.push(`section "${title}" rendered more than once`);
              seen.add(title);
            }

            expect(problems, `section headers at scrollLeft ${offset}`).toEqual([]);
          }
        });
      },
    );

    test(
      'every cell keeps its seeded value across the scroll range, and no column is permanently unreachable',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ wideComparison, backendClient, page }) => {
        const compare = compareFor(page, wideComparison);
        const experimentCount = wideComparison.experiments.length;

        await test.step('The comparison the grid will be served really holds', async () => {
          await expectSeedIsServed(backendClient, wideComparison);
        });

        const first = await test.step('Open the Results grid in comparison mode', async () =>
          openWideGrid(compare, wideComparison));

        const rendered = new Set<string>();

        await test.step('Every rendered cell carries the value seeded for its own row and column', async () => {
          for (const offset of sweepOffsets(first)) {
            const scan = await compare.scrollResultsGridTo(offset);
            const problems: string[] = [];
            const columnsThisOffset = new Set<string>();

            for (const columnId of scan.columnSlots) {
              if (columnId === COLUMN_SPACER) continue;
              rendered.add(columnId);
            }

            for (const cell of scan.cells) {
              const itemIndex = wideComparison.itemIds.indexOf(cell.rowId);
              if (itemIndex === -1) {
                problems.push(`row ${cell.rowId} is not one of the seeded dataset items`);
                continue;
              }
              const key = `${cell.rowId}/${cell.columnId}`;
              if (columnsThisOffset.has(key)) {
                problems.push(`column "${cell.columnId}" rendered twice on row ${cell.rowId}`);
              }
              columnsThisOffset.add(key);

              const field = wideComparison.datasetFields.find(
                (f) => datasetColumnId(f) === cell.columnId,
              );
              if (field !== undefined) {
                const expected = datasetFieldValue(itemIndex, field);
                if (cell.text !== expected) {
                  problems.push(
                    `dataset cell ${cell.columnId} on item ${itemIndex}: rendered "${cell.text}", seeded "${expected}"`,
                  );
                }
                continue;
              }

              if (cell.columnId === outputColumnId(wideComparison.outputKey)) {
                for (let experimentIndex = 0; experimentIndex < experimentCount; experimentIndex++) {
                  const expected = taskOutputValue(experimentIndex, itemIndex);
                  if (cell.bands[experimentIndex] !== expected) {
                    problems.push(
                      `task output on item ${itemIndex} / experiment ${experimentIndex}: rendered "${cell.bands[experimentIndex]}", seeded "${expected}"`,
                    );
                  }
                }
                continue;
              }

              const metricIndex = wideComparison.metricNames.findIndex(
                (m) => scoreColumnId(m) === cell.columnId,
              );
              if (metricIndex !== -1) {
                for (let experimentIndex = 0; experimentIndex < experimentCount; experimentIndex++) {
                  const expected = metricScoreValue(metricIndex, experimentIndex, itemIndex);
                  const actual = Number.parseFloat(cell.bands[experimentIndex] ?? '');
                  if (!Number.isFinite(actual) || Math.abs(actual - expected) > 0.005) {
                    problems.push(
                      `score ${cell.columnId} on item ${itemIndex} / experiment ${experimentIndex}: rendered "${cell.bands[experimentIndex]}", seeded ${expected}`,
                    );
                  }
                }
              }
            }

            expect(problems, `rendered values at scrollLeft ${offset}`).toEqual([]);
          }
        });

        await test.step('Sweeping the grid reaches every column it is meant to have', async () => {
          const expected = [
            'select',
            'experiment_name',
            outputColumnId(wideComparison.outputKey),
            ...wideComparison.datasetFields.map(datasetColumnId),
            ...wideComparison.metricNames.map(scoreColumnId),
          ];
          expect(
            expected.filter((columnId) => !rendered.has(columnId)),
            'columns never reached at any scroll offset',
          ).toEqual([]);
        });
      },
    );
  },
);
