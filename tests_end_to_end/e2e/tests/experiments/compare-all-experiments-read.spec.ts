import { test, expect } from '@e2e/fixtures';

/**
 * `GET /v1/private/datasets/{id}/items/experiments/items` — the read behind the
 * experiment compare Results tab — gained two parameters in opik#9263:
 * `experiment_ids` became optional (omit it and the server resolves the
 * dataset's own experiments), and `dataset_version` scopes that resolution to
 * one version.
 *
 * Neither has any e2e coverage. `compare-raw-sorted-paging.spec.ts`,
 * `compare-deep-paging.spec.ts` and `experiment-items-sdk-read.spec.ts` all
 * drive the supplied-ids arm, and `e2e/core/backend/client.ts` always sent
 * `experiment_ids` — there was no way to express the new call at all before
 * `compareItemsAllExperiments` was added alongside this spec.
 *
 * API-level throughout, and deliberately so: everything 9263 changed is a
 * server-side resolution rule plus parameter validation. Driving the grid to
 * observe it second-hand would be slower, flakier, and could not reach the
 * rejections at all — the UI never issues a request with a blank
 * `experiment_ids` or an over-cap page size.
 *
 * Tagged `@cap:experiments.compare-side-by-side`, which is this read's own
 * capability: "which experiments compose a compare row" is exactly what the
 * all-experiments mode decides. It is NOT a claim about the compare grid's
 * controls — no filter, search, sort or paging control is driven here, and the
 * filter pushdown 9263 also moved stays on the manual list.
 */

/** The page-size ceiling the all-experiments mode enforces (ALL_EXPERIMENTS_MAX_PAGE_SIZE). */
const ALL_EXPERIMENTS_MAX_PAGE_SIZE = 100;

test.describe(
  'Experiment compare — all-experiments read (opik#9263)',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'omitting experiment_ids resolves every regular experiment and excludes optimizer trials',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ allDatasetExperiments, backendClient }) => {
        const { datasetId, itemIds, regular, trial } = allDatasetExperiments;
        const regularIds = regular.map((e) => e.id).sort();

        const supplied = await test.step(
          'With experiment_ids supplied, the read answers every item with a result per experiment',
          async () => {
            const page = await backendClient.compareItemsPage({
              datasetId,
              experimentIds: regular.map((e) => e.id),
              size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            });
            // The baseline the all-experiments answer is compared against. It
            // is asserted rather than merely recorded: a seed whose experiment
            // items had not all landed would make the comparison below agree
            // about a row set that was never the one seeded.
            expect(page.total, 'every seeded item is in the comparison').toBe(itemIds.length);
            expect(page.ids.length, 'and they all fit on the one page asked for').toBe(
              itemIds.length,
            );
            expect([...page.ids].sort(), 'exactly the seeded items, no others').toEqual(
              [...itemIds].sort(),
            );
            return page;
          },
        );

        await test.step(
          'With experiment_ids OMITTED, the same rows come back — resolved from the dataset',
          async () => {
            const all = await backendClient.compareItemsAllExperiments({
              datasetId,
              size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            });

            expect(all.total, 'the same total as the supplied-ids read').toBe(itemIds.length);
            expect(all.ids.length, 'and the same number of rows on the page').toBe(
              itemIds.length,
            );
            expect([...all.ids].sort(), 'the same row set, by id').toEqual([...supplied.ids].sort());

            // The discriminating assertion. The trial experiment reports a
            // result on the first item, so if the type filter were dropped that
            // row would carry three results while every other row carried two —
            // invisible to the row-set comparison above, and invisible to an id
            // set that already contains both regular experiments.
            expect(
              [...new Set(all.experimentItemCounts)],
              'every row carries a result from exactly the two regular experiments',
            ).toEqual([regular.length]);
            expect(
              all.experimentItemCounts.length,
              'counted over every row, not just the ones that happened to be read',
            ).toBe(itemIds.length);

            // The whole answer, not just that the regulars are in it: an
            // equality here is what fails if the trial leaks in.
            expect(all.experimentIds, 'the two regular experiments and nothing else').toEqual(
              regularIds,
            );
            expect(
              all.experimentIds,
              'the optimizer trial is excluded from the resolution',
            ).not.toContain(trial.id);
          },
        );

        await test.step(
          'The trial experiment is still readable when its id is supplied explicitly',
          async () => {
            // Excluded from the RESOLUTION, not hidden from the endpoint: the
            // Optimization Studio's Trials tab reads trials through this same
            // path by naming them. Without this, "the trial is absent above"
            // would be satisfied just as well by a build that had stopped
            // storing the trial's experiment items at all — in which case the
            // exclusion assertion proves nothing.
            const trialOnly = await backendClient.compareItemsPage({
              datasetId,
              experimentIds: [trial.id],
              size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            });
            expect(trialOnly.total, 'the one item the trial reported against').toBe(
              trial.itemIds.length,
            );
            expect(trialOnly.ids, 'and it is that item').toEqual(trial.itemIds);
          },
        );
      },
    );

    test(
      'the all-experiments mode caps its page size and still refuses an empty experiment_ids',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ allDatasetExperiments, backendClient }) => {
        const { datasetId, regular } = allDatasetExperiments;

        await test.step('A page size at the cap is served', async () => {
          const atCap = await backendClient.compareItemsAllExperiments({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
          });
          // The control for the rejection below: without it, a build that
          // rejected every all-experiments read would pass the 400 assertions
          // and look like correct validation.
          expect(atCap.total, 'the cap itself is a legal size').toBeGreaterThan(0);
        });

        await test.step('One above the cap is refused, naming the cap', async () => {
          const overCap = await backendClient.compareItemsReadStatus({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE + 1,
          });
          expect(overCap.status, 'refused at validation, not served or 500').toBe(400);
          expect(overCap.message).toBe(
            `size must be at most ${ALL_EXPERIMENTS_MAX_PAGE_SIZE} when experiment_ids is omitted`,
          );
        });

        await test.step(
          'The same page size is fine when experiment_ids IS supplied',
          async () => {
            // The cap belongs to the new mode alone — each row there carries a
            // result per experiment on the dataset, so the response grows with
            // the experiment count. A cap that had leaked onto the supplied-ids
            // arm would break the existing deep-paging reads, which page at
            // 2,000.
            const supplied = await backendClient.compareItemsReadStatus({
              datasetId,
              experimentIds: JSON.stringify(regular.map((e) => e.id)),
              size: ALL_EXPERIMENTS_MAX_PAGE_SIZE + 1,
            });
            expect(supplied.status, 'the supplied-ids arm is not capped at 100').toBe(200);
          },
        );

        await test.step(
          'A BLANK experiment_ids is not an omitted one — it is still a 400',
          async () => {
            // The distinction 9263 had to preserve: absent means "resolve them
            // all", present-but-empty means the caller sent nothing usable. A
            // build that treated blank as absent would silently compare every
            // experiment on the dataset for a grid that asked for none.
            const blank = await backendClient.compareItemsReadStatus({
              datasetId,
              experimentIds: '',
            });
            expect(blank.status).toBe(400);
            expect(blank.message).toBe('experiment_ids cannot be empty');

            const emptyArray = await backendClient.compareItemsReadStatus({
              datasetId,
              experimentIds: '[]',
            });
            expect(emptyArray.status).toBe(400);
            expect(emptyArray.message).toBe('experiment_ids cannot be empty');
          },
        );
      },
    );

    test(
      'dataset_version scopes the all-experiments read, and is rejected for an unknown version or alongside experiment_ids',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ allDatasetExperiments, backendClient }) => {
        const { datasetId, itemIds, versionHash, regular } = allDatasetExperiments;
        const regularIds = regular.map((e) => e.id).sort();

        await test.step('dataset_version=latest returns the full row set', async () => {
          const latest = await backendClient.compareItemsAllExperiments({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            datasetVersion: 'latest',
          });
          expect(latest.total).toBe(itemIds.length);
          expect([...latest.ids].sort()).toEqual([...itemIds].sort());
          expect(latest.experimentIds, 'still the regular experiments only').toEqual(regularIds);
        });

        await test.step('The version addressed by its own hash returns the same set', async () => {
          // The seed cut exactly one version, so its hash and `latest` name the
          // same thing — which is the point: the two spellings must agree. The
          // fixture asserts the hash is present, so this cannot quietly become
          // an unscoped read.
          const byHash = await backendClient.compareItemsAllExperiments({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            datasetVersion: versionHash,
          });
          expect(byHash.total).toBe(itemIds.length);
          expect([...byHash.ids].sort()).toEqual([...itemIds].sort());
          expect(byHash.experimentIds).toEqual(regularIds);
          expect(
            [...new Set(byHash.experimentItemCounts)],
            'and each row still carries both regular results',
          ).toEqual([regular.length]);
        });

        await test.step('An unknown version is a 404, not an empty page', async () => {
          // An empty 200 here would read as "no experiments ran against that
          // version", which is a legitimate answer for a version that exists —
          // so the status is the only thing that separates a typo'd hash from a
          // genuinely unused one.
          const unknown = await backendClient.compareItemsReadStatus({
            datasetId,
            datasetVersion: 'deadbeefdeadbeefdeadbeefdeadbeef',
          });
          expect(unknown.status).toBe(404);
        });

        await test.step(
          'dataset_version together with experiment_ids is refused',
          async () => {
            const both = await backendClient.compareItemsReadStatus({
              datasetId,
              experimentIds: JSON.stringify(regular.map((e) => e.id)),
              datasetVersion: 'latest',
            });
            expect(both.status).toBe(400);
            expect(both.message).toBe(
              'dataset_version can only be used when experiment_ids is omitted',
            );
          },
        );
      },
    );
  },
);
