import { test, expect } from '@e2e/fixtures';
import type { DatasetExperimentItemStreamRowRef } from '@e2e/core/backend';

/**
 * `POST /v1/private/datasets/{id}/items/experiments/items/stream` — the
 * cursor-paged experiment-item read opik#9588 (OPIK-9047) added, and the path
 * the Python SDK takes for `find_experiment_items_for_dataset` without
 * `experiment_ids`.
 *
 * A brand-new REST surface the Playwright estate does not touch at all before
 * this spec. Its failure mode is silent in the way only a cursor can be: a
 * keyset predicate that is off by one row either repeats the cursor row or skips
 * the one after it, and either way the caller gets a plausible-looking list with
 * no error anywhere. A caller cannot even tell a short page from the end of the
 * data, because "fewer rows than the limit" IS the end-of-stream signal.
 *
 * ## Why this is a separate spec from the all-experiments GET
 *
 * `compare-all-experiments-read.spec.ts` drives
 * `GET …/items/experiments/items` with `experiment_ids` omitted. That is a
 * different endpoint with a different contract, not a streaming flavour of this
 * one: it is offset-paged, capped at 100, and answers one row per DATASET ITEM
 * carrying a result per experiment. This one is keyset-paged, capped at 2,000,
 * and answers one row per EXPERIMENT ITEM. Nothing either spec asserts
 * transfers to the other.
 *
 * ## What makes each assertion discriminating
 *
 * The seed (`allDatasetExperiments`) is asymmetric on purpose: TWO regular
 * experiments covering every one of its 20 items, and ONE optimizer TRIAL
 * covering the first item only.
 *
 *  - **Completeness and freedom from duplicates** are stated over the whole
 *    drained set compared against the exact `(experiment_id, dataset_item_id)`
 *    product the seed wrote — not over a count, and not by looking for the
 *    seeded rows inside the answer. A `find()`-style check passes just as well
 *    when the stream also served rows it should have excluded.
 *  - **Order** is asserted as the server's own documented key,
 *    `(experiment_id, dataset_item_id, id)` DESC, over the drained sequence.
 *    Completeness alone is satisfied by any permutation, and a permutation is
 *    exactly what a cursor whose predicate disagrees with its ORDER BY
 *    produces.
 *  - **The limit is small and deliberately not a divisor** of the 40 rows, so
 *    the drain crosses several cursor boundaries and the final page is short.
 *    A limit at or above the row count would never issue a second request and
 *    would test no cursor at all, which is asserted here rather than assumed.
 *  - **Paging must be invisible.** Three different limits are drained and
 *    compared against each other and against a single unpaged read, so a
 *    boundary bug has to corrupt every arm identically to escape.
 *  - **The trial exclusion** is asserted over the drained rows AND against a
 *    read that proves the trial's experiment items exist and are reachable —
 *    otherwise "the trial is absent" would be satisfied by a build that had
 *    stopped storing them.
 *
 * API-level throughout, with no browser, and deliberately: 9588 shipped a REST
 * endpoint and an SDK reader over it. No UI issues this request — the compare
 * grid passes `experiment_ids` and so takes the paged GET — so there is nothing
 * to observe it through second-hand, and `@cap:experiments.experiment-item-read`
 * is a read key rather than a claim about any control.
 *
 * ## Not covered
 *
 * The POSITIVE `dataset_versions` filter, and the `DATASET_VIEW` permission
 * gate. Both were blocked in exploration for environment reasons rather than
 * product ones: items written through `PUT /v1/private/datasets/items` cut one
 * version and there is no REST route to create a second, and the gate needs a
 * workspace member without `DATASET_VIEW`, which means changing workspace
 * membership. The unknown-version 404 below is the half that IS reachable, and
 * it is what separates "the parameter is honoured" from "the parameter is
 * ignored" — the same argument `compare-all-experiments-read.spec.ts` makes
 * about its own one-version seed.
 *
 * Also not covered: the `last_retrieved_experiment_id` hint's own branch, which
 * only runs when the cursor row has been DELETED between pages. Reaching it
 * means deleting an experiment item mid-drain, and the one assertion available
 * after that is about which rows a partially-deleted dataset serves — a race,
 * not a shape.
 */

/** Rows per page while draining. Deliberately not a divisor of the 40-row seed. */
const PAGE_LIMIT = 7;

/** A second and a third limit, so a boundary bug cannot be identical in all three. */
const OTHER_LIMITS = [3, 13] as const;

/** The endpoint's own documented ceiling (`DatasetExperimentItemStreamRequest.limit`). */
const MAX_LIMIT = 2_000;

/** `(experiment_id, dataset_item_id, id)` DESC — the order the stream promises. */
const descendingByKey = (
  a: DatasetExperimentItemStreamRowRef,
  b: DatasetExperimentItemStreamRowRef,
): number =>
  b.experimentId.localeCompare(a.experimentId) ||
  b.datasetItemId.localeCompare(a.datasetItemId) ||
  b.id.localeCompare(a.id);

/** `experiment_id:dataset_item_id`, the pair the seed controls and the stream must cover once each. */
const pairKey = (row: { experimentId: string; datasetItemId: string }): string =>
  `${row.experimentId}:${row.datasetItemId}`;

test.describe(
  'Experiments — dataset experiment-item stream (opik#9588)',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'the stream drains completely, in order and without duplicates, over regular experiments only',
      { tag: ['@cap:experiments.experiment-item-read'] },
      async ({ allDatasetExperiments, backendClient }) => {
        // Four full drains of a 40-row dataset at small limits is ~30 cursor
        // round trips against a cloud backend, past the 90s default.
        test.setTimeout(300_000);

        const { datasetId, itemIds, regular, trial } = allDatasetExperiments;

        /** Every `(experiment, item)` pair a correct stream must serve exactly once. */
        const expectedPairs = regular
          .flatMap((experiment) =>
            experiment.itemIds.map((datasetItemId) => ({
              experimentId: experiment.id,
              datasetItemId,
            })),
          )
          .map(pairKey);

        await test.step('The seed poses a multi-page question at this limit', async () => {
          // Asserted rather than assumed: if the fixture's item count ever drops
          // to at or below the limit the drain below issues exactly one request,
          // every cursor assertion becomes vacuous, and the spec reads as
          // coverage of a cursor it never exercised.
          expect(expectedPairs, 'two regular experiments over every seeded item').toHaveLength(
            regular.length * itemIds.length,
          );
          expect(
            expectedPairs.length / PAGE_LIMIT,
            `the ${expectedPairs.length}-row seed must span several pages at limit ${PAGE_LIMIT}`,
          ).toBeGreaterThan(2);
          expect(
            expectedPairs.length % PAGE_LIMIT,
            'and must not divide evenly, so the last page is a short one',
          ).not.toBe(0);
        });

        /**
         * Drain the stream at `limit`, following the cursor until a short page.
         *
         * The loop is bounded by the row count rather than by `while (true)`: a
         * server that re-served the same page forever would otherwise hang until
         * the test timeout, which reports as "slow" instead of "repeated a page".
         */
        const drain = async (limit: number): Promise<DatasetExperimentItemStreamRowRef[]> => {
          const drained: DatasetExperimentItemStreamRowRef[] = [];
          let lastRetrievedId: string | undefined;
          const maxRequests = Math.ceil(expectedPairs.length / limit) + 2;

          for (let request = 0; request < maxRequests; request++) {
            const page = await backendClient.streamDatasetExperimentItems({
              datasetId,
              limit,
              ...(lastRetrievedId === undefined ? {} : { lastRetrievedId }),
            });
            expect(
              page.status,
              `page ${request + 1} at limit ${limit} answered ${page.status}: ${page.message}`,
            ).toBe(200);
            expect(
              page.rows.length,
              `page ${request + 1} at limit ${limit} must not exceed the limit it asked for`,
            ).toBeLessThanOrEqual(limit);

            drained.push(...page.rows);
            if (page.rows.length < limit) return drained;
            lastRetrievedId = page.rows[page.rows.length - 1].id;
          }

          throw new Error(
            `the stream at limit ${limit} never answered a short page within ${maxRequests} ` +
              `requests (${drained.length} rows drained) — it is re-serving rows rather than advancing`,
          );
        };

        const paged = await test.step(
          `Drain the stream at limit ${PAGE_LIMIT}, following the cursor`,
          async () => drain(PAGE_LIMIT),
        );

        await test.step('The drain served every seeded pair exactly once, and nothing else', async () => {
          // The whole answer, as a sorted equality rather than a membership
          // check: a stream that served the trial's row as well, or served one
          // experiment's items twice, satisfies "my rows are in there" and fails
          // here.
          expect(
            [...paged.map(pairKey)].sort(),
            'exactly the (experiment, item) pairs the two regular experiments wrote',
          ).toEqual([...expectedPairs].sort());

          // And by row id, which the pair comparison cannot see: two different
          // experiment items for one pair would make the lists above agree while
          // the stream had in fact duplicated a row.
          expect(
            new Set(paged.map((row) => row.id)).size,
            'every drained row is a distinct experiment item — no row served twice',
          ).toBe(paged.length);
        });

        await test.step('The drained sequence is in (experiment_id, dataset_item_id, id) DESC order', async () => {
          // Order, which completeness cannot imply: any permutation of the rows
          // satisfies the step above. This is the half a cursor whose predicate
          // disagrees with its ORDER BY actually breaks — and it is asserted as
          // the whole sequence against its own sort, so the failure diff names
          // the rows that moved.
          expect(
            paged,
            'the stream orders by experiment, then dataset item, then row id, all descending',
          ).toEqual([...paged].sort(descendingByKey));
        });

        await test.step('Each experiment arrives as one contiguous block', async () => {
          // Implied by the ordering above, and worth its own assertion because
          // it is the property the endpoint's own documentation states and the
          // one the SDK's "results are read experiment by experiment" note
          // depends on. A failure here reads as what it is, where a sort-order
          // diff over 40 rows does not.
          const blocks: string[] = [];
          for (const row of paged) {
            if (blocks[blocks.length - 1] !== row.experimentId) blocks.push(row.experimentId);
          }
          expect(
            blocks,
            'one block per regular experiment, descending by id, with no experiment revisited',
          ).toEqual([...regular.map((e) => e.id)].sort((a, b) => b.localeCompare(a)));
        });

        await test.step('The optimizer trial is excluded — and its items do exist', async () => {
          expect(
            paged.map((row) => row.experimentId),
            'no row belongs to the trial experiment',
          ).not.toContain(trial.id);

          // The control. Without it, "the trial is absent" is also satisfied by
          // a build that had stopped writing the trial's experiment items at
          // all, in which case the exclusion proves nothing. The paged GET can
          // be TOLD to read a trial, which is the one surface that can see them.
          const trialOnly = await backendClient.compareItemsPage({
            datasetId,
            experimentIds: [trial.id],
            size: 100,
          });
          expect(
            trialOnly.total,
            'the trial really did report a result, so its exclusion above is a choice',
          ).toBe(trial.itemIds.length);
        });

        await test.step('Two other limits, and one unpaged read, return the identical sequence', async () => {
          // Paging must be invisible to the caller. Each arm is compared to the
          // first one in full, in order — so a boundary bug would have to corrupt
          // all four arms identically to escape, which a limit-dependent
          // off-by-one cannot.
          for (const limit of OTHER_LIMITS) {
            const arm = await drain(limit);
            expect(
              arm,
              `the drain at limit ${limit} must return exactly what limit ${PAGE_LIMIT} returned, in the same order`,
            ).toEqual(paged);
          }

          const unpaged = await backendClient.streamDatasetExperimentItems({
            datasetId,
            limit: MAX_LIMIT,
          });
          expect(unpaged.status, `the unpaged read answered ${unpaged.message}`).toBe(200);
          expect(
            unpaged.rows,
            'a single read at the endpoint ceiling returns the same rows in the same order, ' +
              'so nothing about the answer depends on having been paged',
          ).toEqual(paged);
        });
      },
    );

    test(
      'the stream validates its identifier, its limit and its version scope',
      { tag: ['@cap:experiments.experiment-item-read'] },
      async ({ allDatasetExperiments, backendClient, project }) => {
        const { datasetId, datasetName, itemIds, regular } = allDatasetExperiments;
        const expectedRows = regular.length * itemIds.length;

        await test.step('A limit above the ceiling is refused, not clamped', async () => {
          // Clamping would be the quiet failure: a caller asking for 2,001 would
          // get 2,000 and have no way to know its limit was not honoured, which
          // matters because the limit is also how it recognises the last page.
          const overCap = await backendClient.streamDatasetExperimentItems({
            datasetId,
            limit: MAX_LIMIT + 1,
          });
          expect(overCap.status, `limit ${MAX_LIMIT + 1} answered: ${overCap.message}`).toBe(422);
        });

        await test.step('A limit of zero is refused too', async () => {
          const zero = await backendClient.streamDatasetExperimentItems({ datasetId, limit: 0 });
          expect(zero.status, `limit 0 answered: ${zero.message}`).toBe(422);
        });

        await test.step('Exactly one of dataset_id and dataset_name must be set', async () => {
          // The record's own `@AssertTrue`. Both forms address the same dataset,
          // so "neither" and "both" are the two ways a caller gets this wrong —
          // and a server that accepted "neither" would have to invent a dataset.
          const neither = await backendClient.streamDatasetExperimentItems({ limit: PAGE_LIMIT });
          expect(neither.status, `no identifier answered: ${neither.message}`).toBe(422);

          const both = await backendClient.streamDatasetExperimentItems({
            datasetId,
            datasetName,
            projectName: project.name,
            limit: PAGE_LIMIT,
          });
          expect(both.status, `both identifiers answered: ${both.message}`).toBe(422);
        });

        await test.step('dataset_name alone addresses the same dataset as dataset_id', async () => {
          // The positive half, which is what makes the two rejections above about
          // the "exactly one" rule rather than about `dataset_name` being
          // unimplemented. Compared row for row against the id-addressed read:
          // a name that resolved to a DIFFERENT dataset would answer 200 with
          // somebody else's rows.
          //
          // `project_name` travels with the name because dataset names became
          // unique PER PROJECT in opik#9659 — a bare name is resolved against
          // the workspace's default project, which is not where this seed lives.
          const byId = await backendClient.streamDatasetExperimentItems({
            datasetId,
            limit: MAX_LIMIT,
          });
          expect(byId.status, `the id-addressed read answered: ${byId.message}`).toBe(200);
          expect(byId.rows, 'the id-addressed read returns the whole seed').toHaveLength(
            expectedRows,
          );

          const byName = await backendClient.streamDatasetExperimentItems({
            datasetName,
            projectName: project.name,
            limit: MAX_LIMIT,
          });
          expect(byName.status, `the name-addressed read answered: ${byName.message}`).toBe(200);
          expect(
            byName.rows,
            'addressing the dataset by name returns the identical rows, in the identical order',
          ).toEqual(byId.rows);
        });

        await test.step('last_retrieved_experiment_id without last_retrieved_id is refused', async () => {
          // The record's second `@AssertTrue`. The hint is only consulted when
          // the cursor row has been deleted, so on its own it names a position
          // the server cannot resume from — accepting it would silently restart
          // the stream from the top of that experiment, which a caller would read
          // as duplicated rows.
          const hintOnly = await backendClient.streamDatasetExperimentItems({
            datasetId,
            limit: PAGE_LIMIT,
            lastRetrievedExperimentId: regular[0].id,
          });
          expect(hintOnly.status, `the bare hint answered: ${hintOnly.message}`).toBe(422);
        });

        await test.step('An unknown dataset_versions entry is a 404, not an empty stream', async () => {
          // The only reachable discriminator for the version scope on a
          // one-version dataset: a 200 with no rows is a legitimate answer for a
          // version that exists and no experiment ran against, so the status is
          // the only thing separating a typo'd hash from a genuinely unused one.
          // A build that had stopped reading `dataset_versions` altogether would
          // answer 200 with every row here.
          const unknown = await backendClient.streamDatasetExperimentItems({
            datasetId,
            limit: PAGE_LIMIT,
            datasetVersions: ['deadbeefdeadbeefdeadbeefdeadbeef'],
          });
          expect(
            unknown.status,
            `an unknown version answered ${unknown.status}: ${unknown.message}`,
          ).toBe(404);
          expect(unknown.rows, 'and serves no rows').toHaveLength(0);
        });

        await test.step('An unknown dataset id is a 404', async () => {
          // The control for the version 404 above: without it, a server that
          // answered 404 to every stream request would satisfy that assertion
          // while serving nobody. This one also pins that the dataset is resolved
          // BEFORE the stream opens — the resource comments say so, and the
          // alternative is an error object delivered mid-stream, which a caller
          // reading NDJSON lines would mistake for a short page.
          const missing = await backendClient.streamDatasetExperimentItems({
            datasetId: '01999999-9999-7999-8999-999999999999',
            limit: PAGE_LIMIT,
          });
          expect(
            missing.status,
            `an unknown dataset answered ${missing.status}: ${missing.message}`,
          ).toBe(404);
        });
      },
    );
  },
);
