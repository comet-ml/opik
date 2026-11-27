import { test, expect, VSE_V1_ITEM_COUNT, VSE_V2_ITEM_COUNT } from '@e2e/fixtures';

/**
 * `dataset_versions` on `GET /v1/private/datasets/{id}/items/experiments/items`
 * really scopes the read to one version's experiments — asserted over a dataset
 * that has TWO versions, which is the only shape in which that claim can fail
 * (opik#9448, OPIK-9113).
 *
 * **Why this exists next to `compare-all-experiments-read.spec.ts`, which already
 * sends the parameter.** That spec's fixture cuts exactly one version, and its
 * own header says what follows from that: with one version, `latest` and the
 * only hash name the same thing and both resolve to every experiment on the
 * dataset, so a correctly-scoped read and a read whose parameter was DISCARDED
 * answer byte-identical row sets. Its positive steps therefore cannot fail on an
 * ignored parameter — only its unknown-hash 404 can, which is why that spec
 * marks the 404 step as the load-bearing one. This spec is the discriminating
 * seed: v1 holds 2 items and v2 holds 3, one experiment is pinned to each, and
 * so each version has a row count that only a genuinely scoped read produces.
 *
 * **The failure this guards against is silent.** A version-scoped read that
 * quietly widens answers 200 with a healthy-looking, correctly-formed page of
 * rows — just the wrong ones. There is no error, no empty state and no partial
 * render for a reader to notice, which is why every assertion below is a closed
 * set over `total`, the row ids AND the resolved experiment ids rather than
 * "the rows I expected are in there": finding v1's two items inside a
 * three-row answer passes exactly when the bug is present.
 *
 * **API-level, and not `both`.** Everything 9448 changed here is a server-side
 * resolution rule. The frontend never sends a version parameter on this
 * endpoint at all — `useCompareExperimentsList` sends `experiment_ids`, filters,
 * sorting and search only — so driving the compare grid would assert nothing
 * about `dataset_versions`, and the rejections below are not reachable from the
 * UI in the first place. That the grid is unaffected is already
 * `compare-side-by-side`'s, via `compare-raw-sorted-paging.spec.ts`.
 *
 * Tagged `@cap:experiments.compare-side-by-side`, whose taxonomy note already
 * scopes it to this endpoint's all-experiments arm including "`dataset_version`
 * scoping it to one version". Deliberately NOT `experiments.compare-view`: that
 * key is a screenshot baseline owned by the visual suite (`@vcap:`), as the
 * taxonomy says in its own comment, and claiming it from an API-level spec would
 * mark a visual check covered that nobody has written.
 *
 * Deterministic and free: fixed item counts, caller-minted ids, no LLM, no
 * provider key and no dependence on data that happens to exist in the workspace.
 */

/** The page-size ceiling the all-experiments mode enforces (ALL_EXPERIMENTS_MAX_PAGE_SIZE). */
const ALL_EXPERIMENTS_MAX_PAGE_SIZE = 100;

/** A hash no version carries. Hex, and the right shape, so only its absence makes it unknown. */
const UNKNOWN_VERSION_HASH = 'deadbeefdeadbeefdeadbeefdeadbeef';

test.describe(
  'Experiment compare — dataset_versions scoping (opik#9448)',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'each version hash resolves to only the experiments pinned to it, and the union resolves to both',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ versionScopedExperiments, backendClient }) => {
        const { datasetId, itemIds, v1, v2 } = versionScopedExperiments;

        await test.step(
          'Unscoped, the read resolves BOTH experiments and every item',
          async () => {
            // The baseline, and the reason the scoped totals below mean
            // something: it is the answer a discarded parameter produces, so
            // each scoped read is asserted to differ from THIS rather than
            // merely to look plausible.
            const unscoped = await backendClient.compareItemsAllExperiments({
              datasetId,
              size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            });
            expect(unscoped.total, 'every item the dataset holds').toBe(VSE_V2_ITEM_COUNT);
            expect([...unscoped.ids].sort(), 'and they are the seeded items').toEqual(
              [...itemIds].sort(),
            );
            expect(unscoped.experimentIds, 'both experiments, unscoped').toEqual(
              [v1.id, v2.id].sort(),
            );
          },
        );

        await test.step("v1's hash resolves to the v1 experiment alone", async () => {
          const scoped = await backendClient.compareItemsAllExperiments({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            datasetVersions: [v1.versionHash],
          });
          // 2, not 3 — the one number an ignored parameter cannot produce.
          expect(scoped.total, `only v1's experiment reported, so ${VSE_V1_ITEM_COUNT} items`).toBe(
            VSE_V1_ITEM_COUNT,
          );
          expect(
            scoped.ids.length,
            'the collection is as short as the total says, so nothing leaked onto the page',
          ).toBe(VSE_V1_ITEM_COUNT);
          expect([...scoped.ids].sort(), "exactly the items v1's experiment covers").toEqual(
            [...v1.itemIds].sort(),
          );
          // The closed set, not a membership check: the v2 experiment also
          // reported against both of these items, so a read that resolved BOTH
          // experiments would answer the same two rows and only the experiment
          // ids would give it away.
          expect(scoped.experimentIds, 'the v1 experiment and nothing else').toEqual([v1.id]);
          expect(
            [...new Set(scoped.experimentItemCounts)],
            'and each row carries exactly one result — v2 is not contributing to it',
          ).toEqual([1]);
        });

        await test.step("v2's hash resolves to the v2 experiment alone", async () => {
          const scoped = await backendClient.compareItemsAllExperiments({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            datasetVersions: [v2.versionHash],
          });
          expect(scoped.total, `v2's experiment covers all ${VSE_V2_ITEM_COUNT} items`).toBe(
            VSE_V2_ITEM_COUNT,
          );
          expect([...scoped.ids].sort(), 'every item').toEqual([...itemIds].sort());
          // This total equals the unscoped one by construction, so the row count
          // alone cannot tell this read from an unscoped one — the experiment id
          // set is what does, and it is asserted as a closed set.
          expect(scoped.experimentIds, 'the v2 experiment and nothing else').toEqual([v2.id]);
          expect(
            [...new Set(scoped.experimentItemCounts)],
            'one result per row, from v2 alone',
          ).toEqual([1]);
        });

        await test.step('Both hashes together resolve to both experiments', async () => {
          const both = await backendClient.compareItemsAllExperiments({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            datasetVersions: [v1.versionHash, v2.versionHash],
          });
          expect(both.total, 'the union of the two experiments covers every item').toBe(
            VSE_V2_ITEM_COUNT,
          );
          expect([...both.ids].sort()).toEqual([...itemIds].sort());
          expect(both.experimentIds, 'both experiments').toEqual([v1.id, v2.id].sort());
          // The per-row shape is what separates "both resolved" from "only v2
          // resolved": the two items v1 also covers must now carry TWO results
          // each, and the item only v2 covers exactly one. A union that had
          // collapsed to one experiment would answer the same three rows with
          // one result each.
          expect(
            [...both.experimentItemCounts].sort(),
            `${VSE_V1_ITEM_COUNT} rows with both results and ` +
              `${VSE_V2_ITEM_COUNT - VSE_V1_ITEM_COUNT} with one`,
            ).toEqual([
            ...Array(VSE_V2_ITEM_COUNT - VSE_V1_ITEM_COUNT).fill(1),
            ...Array(VSE_V1_ITEM_COUNT).fill(2),
          ]);
        });

        await test.step('"latest" resolves to v2, not to every version', async () => {
          const latest = await backendClient.compareItemsAllExperiments({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            datasetVersions: ['latest'],
          });
          expect(latest.total).toBe(VSE_V2_ITEM_COUNT);
          // The whole claim of the tag: `latest` is v2's alias, so it must answer
          // what v2's hash answered and must NOT include v1's experiment.
          expect(latest.experimentIds, 'the latest version is v2, so its experiment alone').toEqual(
            [v2.id],
          );
          expect(latest.experimentIds, 'v1 is not the latest version').not.toContain(v1.id);
        });
      },
    );

    test(
      'the pre-9448 singular dataset_version is silently ignored rather than refused',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ versionScopedExperiments, backendClient }) => {
        const { datasetId, itemIds, v1, v2 } = versionScopedExperiments;

        // Pinned deliberately, as a FACT about the break rather than an
        // endorsement of it. 9448 renamed `dataset_version` to
        // `dataset_versions`, and JAX-RS drops a query parameter it has no
        // binding for — so a caller or SDK that pinned a version before this
        // release gets no 400, no 404 and no warning header, just a wider result
        // set that looks correct. The release's own exploration flagged this as
        // worth a human decision; until that decision is made, this asserts the
        // behaviour that actually ships, so the day a deprecation shim or a
        // rejection is added this test fails LOUDLY instead of the change going
        // out unnoticed.
        await test.step(
          'The old name asks for v1 and gets every version — a 200, not a rejection',
          async () => {
            const legacy = await backendClient.compareItemsAllExperiments({
              datasetId,
              size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
              legacyDatasetVersion: v1.versionHash,
            });
            expect(
              legacy.total,
              'the caller pinned v1 and received the unscoped row set — the silent widening',
            ).toBe(VSE_V2_ITEM_COUNT);
            expect(
              legacy.experimentIds,
              'including the experiment that never ran against the version it asked for',
            ).toEqual([v1.id, v2.id].sort());
          },
        );

        await test.step(
          'And it differs from the same request under the new name, which is the point',
          async () => {
            // The two reads side by side are the assertion. Without this, the
            // step above would pass equally on a build where BOTH names were
            // ignored, or where version scoping had never worked at all.
            const scoped = await backendClient.compareItemsAllExperiments({
              datasetId,
              size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
              datasetVersions: [v1.versionHash],
            });
            expect(scoped.total, 'the plural name scopes to v1').toBe(VSE_V1_ITEM_COUNT);
            expect(scoped.experimentIds).toEqual([v1.id]);
            expect(
              scoped.total,
              'the two spellings must not agree — if they did, one of them is not filtering',
            ).not.toBe(VSE_V2_ITEM_COUNT);
          },
        );

        await test.step(
          'A hash no version carries is a 404 under the new name and a full read under the old one',
          async () => {
            // The sharpest form of the break: the SAME nonexistent hash is a
            // not-found under the plural name and a silent full-dataset read
            // under the singular one.
            const unknownPlural = await backendClient.compareItemsReadStatus({
              datasetId,
              datasetVersions: JSON.stringify([UNKNOWN_VERSION_HASH]),
            });
            expect(unknownPlural.status, 'the new name refuses an unknown version').toBe(404);
            expect(
              unknownPlural.message,
              'and names the hash, so a typo is diagnosable from the response alone',
            ).toContain(UNKNOWN_VERSION_HASH);

            const unknownLegacy = await backendClient.compareItemsAllExperiments({
              datasetId,
              size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
              legacyDatasetVersion: UNKNOWN_VERSION_HASH,
            });
            expect(
              unknownLegacy.total,
              'the old name answers 200 with everything for a hash that does not exist',
            ).toBe(VSE_V2_ITEM_COUNT);
            expect(unknownLegacy.experimentIds).toEqual([v1.id, v2.id].sort());
          },
        );

        await test.step('None of the above disturbed the unscoped read', async () => {
          // A 404 and three ignored parameters later, the endpoint must still
          // answer what it did at the start. Cheap, and it is what separates
          // "the rejections are validation" from "a rejected read left the
          // dataset's resolution in a different state".
          const unscoped = await backendClient.compareItemsAllExperiments({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
          });
          expect(unscoped.total).toBe(VSE_V2_ITEM_COUNT);
          expect([...unscoped.ids].sort()).toEqual([...itemIds].sort());
          expect(unscoped.experimentIds).toEqual([v1.id, v2.id].sort());
        });
      },
    );

    test(
      'dataset_versions is validated: not with experiment_ids, not empty, not malformed',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ versionScopedExperiments, backendClient }) => {
        const { datasetId, v1, v2 } = versionScopedExperiments;

        await test.step('Together with experiment_ids it is refused, naming the rule', async () => {
          // The two parameters are alternative ways to decide which experiments
          // compose a row, so honouring both would mean silently picking one.
          const both = await backendClient.compareItemsReadStatus({
            datasetId,
            experimentIds: JSON.stringify([v1.id, v2.id]),
            datasetVersions: JSON.stringify([v1.versionHash]),
          });
          expect(both.status).toBe(400);
          expect(both.message).toBe(
            'dataset_versions can only be used when experiment_ids is omitted',
          );
        });

        await test.step('An empty array is refused rather than read as "every version"', async () => {
          // Silently treating `[]` as unscoped is the defect this rejection
          // exists to prevent: a caller that built the array from an empty
          // selection would get every version's rows while believing it had
          // asked for none.
          const empty = await backendClient.compareItemsReadStatus({
            datasetId,
            datasetVersions: '[]',
          });
          expect(empty.status).toBe(400);
          expect(empty.message).toBe('dataset_versions cannot be empty');
        });

        await test.step('A blank entry inside the array is refused too', async () => {
          const blankEntry = await backendClient.compareItemsReadStatus({
            datasetId,
            datasetVersions: JSON.stringify(['']),
          });
          expect(blankEntry.status).toBe(400);
          expect(blankEntry.message).toBe('dataset_versions must not contain blank values');
        });

        await test.step('A bare, non-JSON value is refused rather than parsed', async () => {
          // The shape a caller migrating off the singular name writes first:
          // they rename the parameter and keep sending one bare hash. It must
          // not be read as a one-element array, because then the migration
          // would appear to work while the array form and the bare form could
          // drift apart.
          const bare = await backendClient.compareItemsReadStatus({
            datasetId,
            datasetVersions: v1.versionHash,
          });
          expect(bare.status, 'a bare hash is not a JSON array').toBe(400);
          // The exact message, not a substring: it is raised by
          // ParamsValidator.get BEFORE any of the resource's own validation
          // runs, so a build that started accepting the bare form and failed
          // later for a different reason would still answer 400 and pass a
          // looser assertion.
          expect(
            bare.message,
            'and the rejection names the parameter it could not parse, and the value',
          ).toBe(`Invalid query param dataset_versions '${v1.versionHash}'`);
        });

        await test.step('A legal scoped read still works afterwards', async () => {
          // The control for all four rejections above: without it, a build that
          // refused every `dataset_versions` read would pass each of them and
          // read as correct validation.
          const scoped = await backendClient.compareItemsAllExperiments({
            datasetId,
            size: ALL_EXPERIMENTS_MAX_PAGE_SIZE,
            datasetVersions: [v1.versionHash],
          });
          expect(scoped.total).toBe(VSE_V1_ITEM_COUNT);
          expect(scoped.experimentIds).toEqual([v1.id]);
        });
      },
    );
  },
);
