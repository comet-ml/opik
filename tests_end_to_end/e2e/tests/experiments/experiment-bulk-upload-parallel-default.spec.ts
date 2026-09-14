import { test, expect, BULK_SCORE_NAME, BULK_EXPECTED_MEAN_SCORE } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * `Experiment.batch_upload_items` uploads every item exactly once now that it
 * fans its batches out over a pool by default (opik#8315).
 *
 * The method used to upload sequentially unless the caller passed
 * `num_threads`; the PR changed that default to 8, so every caller who never
 * passed one — which is all of them — now issues 8 concurrent bulk requests
 * where there was previously one at a time. Nothing in the estate called
 * `batch_upload_items` at all before this spec, and the PR's own unit test
 * asserts `max_workers == 8` against a mocked REST client, which cannot show
 * more batches than workers landing exactly once against a real backend.
 *
 * The failure this exists to catch is silent. A dropped batch raises nothing:
 * the upload returns, the experiment exists, and the only evidence is rows
 * missing from the experiment detail page. A duplicated batch is worse — it
 * raises nothing either, and it moves an aggregate score without changing
 * anything a count would notice. So the assertions are on the multiset of
 * `dataset_item_id`s (equal counts, no duplicates, no omissions) and on the mean
 * score, which the fixture seeds to exactly 0.45 for that reason.
 *
 * `num_threads=1` runs as the control over the *same* dataset: the concurrent
 * path has to agree with the sequential one item for item, not merely look
 * plausible on its own.
 *
 * The fan-out is asserted before anything else. An upload that split into fewer
 * batches than it has workers would send every batch in one round and never
 * recycle a worker, which is where a pool bug hides — such a run would satisfy
 * every assertion below while exercising none of them, and read as coverage
 * forever. The fixture sizes its payload against the SDK's 3.5MB batch ceiling
 * to make that impossible; this checks it held.
 *
 * Both surfaces, because the disagreement is the point: the SDK writes, and the
 * experiment detail page is what a user reads the result on. The page is
 * verified at the level it can honestly be — its population counter, its
 * aggregate chip, and per-item scores on the rows it renders — because its items
 * table is virtualised and a row count there is a fact about the scroll
 * position, not about the experiment.
 *
 * One test rather than one per behaviour: the seed is ~72MB of bulk traffic
 * across two arms, and splitting it would pay that twice for an independence
 * no reviewer asked for.
 */

/** The worker count the SDK must default to. A literal, deliberately: reading
 * the SDK's own constant back would compare it to itself. */
const EXPECTED_DEFAULT_THREADS = 8;

/**
 * The floor on how many rendered rows must show a score, per page.
 *
 * A floor rather than an exact count because the number of rows in the DOM is a
 * property of the virtual window, not of the experiment — but it has to be a
 * hard floor, or a page that rendered no scores at all would satisfy a
 * "every score that rendered is correct" assertion over an empty list. The
 * window holds roughly a third of a 100-row page, so 5 is comfortably below it
 * and still far from zero.
 */
const MIN_SCORED_ROWS_PER_PAGE = 5;

test.describe('Experiment bulk upload — parallel default', { tag: ['@area:experiments'] }, () => {
  /**
   * Seeding 1200 dataset items and uploading ~36MB of records twice outruns the
   * default 90s budget. Measured at ~35s end to end against a PR environment,
   * so this is headroom for a loaded backend rather than the expected cost.
   *
   * Declared on the describe so it covers fixture setup, where nearly all of
   * that time is spent — a timeout set in the test body would come too late to
   * help it.
   */
  test.setTimeout(300_000);

  test(
    'batch_upload_items at the default worker count lands every item exactly once, across more batches than it has workers',
    { tag: ['@t2-cuj', '@cap:experiments.per-item-scores'] },
    async ({ bulkUploadedExperiments, backendClient, project, page }) => {
      const { parallel, sequential, itemCount, scoreName } = bulkUploadedExperiments;

      await test.step('The default upload really fanned out: more batches than workers', () => {
        expect(
          parallel.numThreads,
          'the SDK reported no worker count for the default upload — the fan-out cannot be asserted',
        ).not.toBeNull();
        expect(
          parallel.numThreads,
          'an upload with no num_threads must take the tuned default, not a sequential path',
        ).toBe(EXPECTED_DEFAULT_THREADS);

        expect(
          parallel.batchCount,
          'the SDK reported no batch count — the fan-out cannot be asserted',
        ).not.toBeNull();
        expect(
          parallel.batchCount!,
          'the payload must split into more batches than there are workers, or the pool ' +
            'never recycles one and this test proves nothing about concurrency',
        ).toBeGreaterThan(EXPECTED_DEFAULT_THREADS);

        expect(sequential.numThreads, 'the control arm must be sequential').toBe(1);
        expect(
          sequential.batchCount,
          'identical records must split identically, or the two arms are not comparable',
        ).toBe(parallel.batchCount);

        expect(parallel.items, 'the default arm uploaded every seeded item').toHaveLength(itemCount);
        expect(sequential.items, 'the control arm uploaded every seeded item').toHaveLength(itemCount);
      });

      const expectedItemIds = [...parallel.items.map((i) => i.datasetItemId)].sort();

      await test.step('The two arms uploaded the identical dataset items', () => {
        // Same dataset, so the arms are comparable item for item rather than
        // merely equal in size.
        expect([...sequential.items.map((i) => i.datasetItemId)].sort()).toEqual(expectedItemIds);
        expect(new Set(expectedItemIds).size, 'the seed itself holds no duplicate ids').toBe(itemCount);
      });

      const readArm = async (experimentName: string) =>
        backendClient.listExperimentItems({ experimentName, projectName: project.name });

      for (const arm of [
        { label: `the default (${EXPECTED_DEFAULT_THREADS}-worker) upload`, ref: parallel },
        { label: 'the num_threads=1 control', ref: sequential },
      ]) {
        await test.step(`${arm.label} landed every item exactly once`, async () => {
          // Experiment-item linkage is eventually consistent, so poll to the
          // expected population rather than reading once and racing ingestion.
          await expect
            .poll(async () => (await readArm(arm.ref.experimentName)).length, {
              timeout: 180_000,
              intervals: [2_000, 5_000, 10_000],
            })
            .toBe(itemCount);

          const items = await readArm(arm.ref.experimentName);

          expect(
            [...new Set(items.map((i) => i.experimentId))],
            'the stream must answer for this experiment alone',
          ).toEqual([arm.ref.experimentId]);

          // Multiset equality, not containment: a set comparison passes when a
          // batch landed twice, which is half of what can go wrong here.
          expect(
            [...items.map((i) => i.datasetItemId)].sort(),
            'every seeded dataset item, exactly once — none missing, none duplicated',
          ).toEqual(expectedItemIds);

          expect(
            new Set(items.map((i) => i.traceId)).size,
            'each item minted a trace of its own',
          ).toBe(itemCount);
        });

        await test.step(`${arm.label} reports the counters a user reads`, async () => {
          // trace_count is aggregated after the write, so it settles later than
          // the item linkage above.
          await expect
            .poll(
              async () => (await backendClient.getExperimentSummary(arm.ref.experimentId)).traceCount,
              { timeout: 180_000, intervals: [2_000, 5_000, 10_000] },
            )
            .toBe(itemCount);

          const summary = await backendClient.getExperimentSummary(arm.ref.experimentId);
          expect(
            summary.feedbackScores[scoreName],
            `the mean ${scoreName} must be exactly the seeded mean — a dropped or ` +
              'duplicated batch moves it even when the count survives',
          ).toBeCloseTo(BULK_EXPECTED_MEAN_SCORE, 5);
        });
      }

      const detail = await test.step('Open the default-upload experiment from the list', async () => {
        const experiments = new ExperimentsPage(page);
        await experiments.goto(project.id);
        await experiments.waitForReady();
        return experiments.openExperimentById(parallel.experimentId);
      });

      const scoreByItemId = new Map(parallel.items.map((i) => [i.datasetItemId, i.score]));

      /**
       * Every row the page has rendered must be one of the uploaded items and
       * must show the score that was uploaded for it. Returns the row ids seen.
       */
      const assertRenderedScores = async (label: string): Promise<string[]> => {
        // The virtual window is still filling for a moment after a page
        // renders, so poll up to the floor rather than reading into a
        // half-populated table. `rendered` keeps the snapshot that satisfied the
        // poll, so the ids and the scores asserted below are one consistent read.
        let rendered: Array<{ rowId: string; score: number | null }> = [];
        await expect
          .poll(
            async () => {
              rendered = await detail.readRenderedRowScores(BULK_SCORE_NAME);
              return rendered.filter((row) => row.score !== null).length;
            },
            { timeout: 30_000, intervals: [500, 1_000, 2_000] },
          )
          .toBeGreaterThanOrEqual(MIN_SCORED_ROWS_PER_PAGE);

        const rowIds = rendered.map((row) => row.rowId);
        expect(new Set(rowIds).size, `${label}: no row is rendered twice`).toBe(rowIds.length);

        for (const { rowId, score } of rendered) {
          const expectedScore = scoreByItemId.get(rowId);
          expect(expectedScore, `${label}: row ${rowId} is not one of the uploaded items`)
            .not.toBeUndefined();
          // Rows whose score cell is outside the rendered column window carry
          // null; the poll above already fixed a floor on how many did render,
          // so skipping those here cannot empty the check.
          if (score === null) continue;
          expect(score, `${label}: ${BULK_SCORE_NAME} for item ${rowId}`)
            .toBeCloseTo(expectedScore!, 5);
        }
        return rowIds;
      };

      const firstPageRows = await test.step(
        'The detail page reports the whole population and the seeded mean',
        async () => {
          await detail.waitForReady();

          // The counter is fed by the same listing the rows come from, and that
          // listing settles a moment after the items do — poll rather than read
          // once into a table still showing a partial total.
          await expect
            .poll(async () => (await detail.readPaginationSummary()).total, {
              timeout: 60_000,
              intervals: [1_000, 2_000, 5_000],
            })
            .toBe(itemCount);

          const summary = await detail.readPaginationSummary();
          expect(summary.from, 'the first page starts at row 1').toBe(1);
          expect(summary.to, 'the first page is not the whole population').toBeLessThan(itemCount);

          expect(
            await detail.readAggregateScore(),
            'the aggregate chip must agree with the mean the API reports',
          ).toBeCloseTo(BULK_EXPECTED_MEAN_SCORE, 5);

          return assertRenderedScores('first page');
        },
      );

      await test.step('The last page renders the other end of the upload', async () => {
        // The rows a dropped *leading* batch would take are at this end: the
        // table orders newest dataset item first, so the last page holds the
        // items the first batch carried.
        await detail.goToLastPage();

        const summary = await detail.readPaginationSummary();
        expect(summary.to, 'the last page ends on the last row').toBe(itemCount);
        expect(summary.total, 'the population did not change under paging').toBe(itemCount);

        const lastPageRows = await assertRenderedScores('last page');
        const overlap = lastPageRows.filter((id) => firstPageRows.includes(id));
        expect(
          overlap,
          'the last page must render different rows from the first — an overlap means ' +
            'paging did not move, and every page assertion would be about page 1',
        ).toEqual([]);
      });
    },
  );
});
