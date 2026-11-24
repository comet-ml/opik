import { test, expect } from '@e2e/fixtures';

/**
 * `ExperimentsClient.find_experiment_items_for_dataset` on the arm opik#9588
 * (OPIK-9047) added: `experiment_ids` OMITTED, so the SDK resolves the
 * dataset's own regular experiments and reads them through the new
 * experiment-item cursor stream, grouped by dataset item.
 *
 * The estate drives the Python SDK heavily, but has never called this method at
 * all — `experiment-items-sdk-read.spec.ts` is `Experiment.get_items()`, one
 * experiment through a different endpoint, and the compare specs reach the REST
 * layer directly. The release report lists this path explicitly as not
 * exercised.
 *
 * ## Why this spec exists beside the REST stream spec
 *
 * `dataset-experiment-item-stream.spec.ts` pins the ENDPOINT: completeness,
 * order and the cursor. This one pins the SDK's READER over it, which is a
 * separate set of failure modes and the one a user actually meets:
 *
 *  - The method dispatches on whether `experiment_ids` is `None`. A reader that
 *    took the paged path regardless would answer the same ROWS on a
 *    single-version dataset and differ only in `experiment_id` being absent —
 *    which is asserted here per result.
 *  - It drains the cursor itself, experiment by experiment, and re-groups the
 *    rows by dataset item. A regrouping that mispaired a row with another row's
 *    experiment leaves every count intact.
 *  - `max_results` cuts the drain, and the SDK's own docstring warns that a cut
 *    read "can miss later experiments entirely". That is a documented behaviour
 *    rather than a bug, so it is pinned as one.
 *  - The two refusals are the SDK's own contract, not the endpoint's:
 *    `experiment_ids` with `dataset_versions` is a client-side `ValueError`
 *    that never reaches the network, and an unknown version is the SDK's
 *    translation of the endpoint's 404 into `DatasetVersionNotFound`.
 *
 * API-level with no browser, and deliberately: this is an SDK read path and
 * there is no UI that surfaces it. `@cap:experiments.experiment-item-read` is a
 * read key — the same one `experiment-items-sdk-read.spec.ts` carries — and
 * names no control.
 *
 * ## Not covered
 *
 * The POSITIVE `dataset_versions` filter. The fixture's insert cuts exactly ONE
 * version, so `latest` and the only hash name the same thing and a read scoped
 * to either is indistinguishable from an unscoped one; items written through
 * `PUT /v1/private/datasets/items` create no second version and there is no
 * REST route that does. The unknown-version refusal below is the half that
 * discriminates, which is the same argument
 * `compare-all-experiments-read.spec.ts` makes about its own seed.
 *
 * Also not covered: `page_size` and `num_threads` on this arm. The SDK
 * validates them and then does not use them for paging (the cursor is
 * server-side), which its docstring says out loud — so an assertion over them
 * here would pin the validation, not a read.
 */

/** The item every "one item across experiments" read is addressed at. */
const TARGET_ITEM_INDEX = 3;

test.describe(
  'Experiments — SDK dataset-wide experiment-item read (opik#9588)',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'without experiment_ids the SDK reads every regular experiment, each result naming its own',
      { tag: ['@cap:experiments.experiment-item-read'] },
      async ({ allDatasetExperiments, project, sdkClient, backendClient }) => {
        // One bridge call drains a server-side cursor experiment by experiment
        // against a cloud backend, and there are five of them below.
        test.setTimeout(600_000);

        const { datasetId, datasetName, itemIds, regular, trial } = allDatasetExperiments;
        const regularIds = [...regular.map((e) => e.id)].sort();
        const expectedCount = regular.length * itemIds.length;

        const read = await test.step(
          'Read the dataset with experiment_ids omitted entirely',
          async () => {
            const answer = await sdkClient.python.readDatasetExperimentItems({
              dataset_name: datasetName,
              project_name: project.name,
            });
            expect(
              answer.error_type,
              `the omitted-ids read must not raise (got ${answer.error_type}: ${answer.error_message})`,
            ).toBeNull();
            return answer;
          },
        );

        await test.step('It returns one result per (regular experiment, dataset item) pair', async () => {
          // The count AND the collection length, so a reader that reported a
          // total it did not return fails here rather than downstream.
          expect(
            read.count,
            'two regular experiments over every seeded item, and nothing from the trial',
          ).toBe(expectedCount);
          expect(read.items, 'and the collection is that long too').toHaveLength(expectedCount);

          // The whole answer as a sorted equality, not a membership check: a
          // read that also served the trial's result, or served an experiment's
          // items twice, satisfies "my rows are in there" and fails this.
          const pairs = read.items.map((item) => `${item.experiment_id}:${item.dataset_item_id}`);
          const expectedPairs = regular.flatMap((experiment) =>
            experiment.itemIds.map((datasetItemId) => `${experiment.id}:${datasetItemId}`),
          );
          expect(
            [...pairs].sort(),
            'exactly the pairs the two regular experiments wrote, each once',
          ).toEqual([...expectedPairs].sort());

          expect(
            new Set(read.items.map((item) => item.id)).size,
            'every result is a distinct experiment item — no row returned twice',
          ).toBe(read.items.length);
        });

        await test.step('Every result carries its own experiment_id', async () => {
          // The field that distinguishes this arm from the paged one. It is
          // nullable on `ExperimentItemContent`, and that optionality is
          // asserted away rather than coded around: the caller never named the
          // experiments, so a result with no `experiment_id` is one it cannot
          // place, and a build that stopped populating it would otherwise
          // return the right rows unusably.
          expect(
            read.items.filter((item) => item.experiment_id === null).length,
            'no result may come back without the experiment it belongs to',
          ).toBe(0);

          expect(
            [...new Set(read.items.map((item) => item.experiment_id!))].sort(),
            'the two regular experiments and nothing else',
          ).toEqual(regularIds);
          expect(
            read.items.map((item) => item.experiment_id),
            'the optimizer trial is excluded from the resolution',
          ).not.toContain(trial.id);
        });

        await test.step('Every result carries the dataset item content its id names', async () => {
          // The pairing, which the pair-set equality above cannot see: it
          // compares ids to ids, so a read that handed item 5's row item 7's
          // CONTENT satisfies it. The SDK regroups the drained rows by dataset
          // item and attaches each one's content from the newest version among
          // the experiments read, which is exactly where that pairing gets
          // lost — so the content is compared per result.
          //
          // The expected content is read back from the dataset itself rather
          // than derived from the item's POSITION in `itemIds`: the listing
          // pages items out in the endpoint's own order, not the order the seed
          // inserted them, so a positional map would compare the right rows
          // against the wrong strings. Each item's own stored `input` is the
          // only thing that can say what its content is.
          const stored = await backendClient.listDatasetItemsWithData(datasetId);
          expect(
            stored,
            'the dataset really holds the items the pairing is asserted over',
          ).toHaveLength(itemIds.length);

          const expectedInputByItemId = new Map(
            stored.map((item) => [item.id, item.data['input']]),
          );
          for (const [id, input] of expectedInputByItemId) {
            // Asserted away rather than coded around: an item whose stored
            // `input` is missing or not a string would make every comparison
            // below a comparison between two `undefined`s.
            expect(typeof input, `dataset item ${id} stores a string input`).toBe('string');
          }

          for (const item of read.items) {
            const expectedInput = expectedInputByItemId.get(item.dataset_item_id);
            expect(
              expectedInput,
              `result ${item.id} names dataset item ${item.dataset_item_id}, which this dataset holds`,
            ).toBeDefined();
            expect(
              item.dataset_item_input,
              `result ${item.id} must carry the content of the item it names`,
            ).toBe(expectedInput);
          }
        });

        await test.step('Supplying the same experiment ids returns the same pairs', async () => {
          // The control for everything above. Without it, "the omitted-ids read
          // returned these rows" could be satisfied by a build whose resolution
          // was wrong in a way that happened to match the seed — and it is also
          // what shows the two arms agree about the DATA while differing about
          // who chose the experiments.
          const supplied = await sdkClient.python.readDatasetExperimentItems({
            dataset_name: datasetName,
            project_name: project.name,
            experiment_ids: regular.map((e) => e.id),
          });
          expect(
            supplied.error_type,
            `the supplied-ids read must not raise (got ${supplied.error_type}: ${supplied.error_message})`,
          ).toBeNull();
          expect(supplied.count, 'the same number of results as the omitted-ids read').toBe(
            expectedCount,
          );
          expect(
            [...supplied.items.map((i) => i.dataset_item_id)].sort(),
            'over the same dataset items',
          ).toEqual([...read.items.map((i) => i.dataset_item_id)].sort());
        });

        await test.step(
          'The documented filter_string returns that one item from each experiment',
          async () => {
            // The example the method's own docstring gives for this arm, and the
            // reason the arm exists: `filter_string` is evaluated against
            // DATASET ITEM fields here, not trace fields, so `id = "<item>"` is
            // a legal expression on this path and is not on the other.
            const targetItemId = itemIds[TARGET_ITEM_INDEX];
            const filtered = await sdkClient.python.readDatasetExperimentItems({
              dataset_name: datasetName,
              project_name: project.name,
              filter_string: `id = "${targetItemId}"`,
            });
            expect(
              filtered.error_type,
              `the filtered read must not raise (got ${filtered.error_type}: ${filtered.error_message})`,
            ).toBeNull();

            // Asserted as the whole answer, not as "my item is in there": a
            // filter that was silently ignored answers every row and would
            // satisfy a membership check.
            expect(
              filtered.count,
              'one result per regular experiment, for the one item asked for',
            ).toBe(regular.length);
            expect(
              [...new Set(filtered.items.map((item) => item.dataset_item_id))],
              'and every result is about that item alone',
            ).toEqual([targetItemId]);
            expect(
              [...filtered.items.map((item) => item.experiment_id!)].sort(),
              'with both regular experiments still represented, so the filter scoped the ITEM and not the experiments',
            ).toEqual(regularIds);
          },
        );
      },
    );

    test(
      'the dataset-wide read honours max_results and refuses the two shapes it documents',
      { tag: ['@cap:experiments.experiment-item-read'] },
      async ({ allDatasetExperiments, project, sdkClient }) => {
        test.setTimeout(300_000);

        const { datasetName, itemIds, regular } = allDatasetExperiments;

        await test.step('max_results cuts the read to exactly that many results', async () => {
          // Cut deliberately inside the FIRST experiment's block rather than on
          // a boundary: the SDK reads experiment by experiment, so a cut that
          // landed between experiments would be satisfied by a reader that
          // stopped a whole experiment early, which is a different arithmetic.
          const cut = itemIds.length - 3;
          expect(
            cut,
            'the cut must fall inside one experiment, or it tests the wrong boundary',
          ).toBeLessThan(itemIds.length);
          expect(cut, 'and must still be more than nothing').toBeGreaterThan(0);

          const truncated = await sdkClient.python.readDatasetExperimentItems({
            dataset_name: datasetName,
            project_name: project.name,
            max_results: cut,
          });
          expect(
            truncated.error_type,
            `the truncated read must not raise (got ${truncated.error_type}: ${truncated.error_message})`,
          ).toBeNull();
          expect(truncated.count, 'max_results is honoured exactly').toBe(cut);
          expect(truncated.items, 'and the collection is that long').toHaveLength(cut);

          // The behaviour the docstring warns about, pinned as the documented
          // one rather than left to be rediscovered: a cut read can miss later
          // experiments entirely, because the drain is experiment-ordered.
          expect(
            new Set(truncated.items.map((item) => item.experiment_id)).size,
            'a read cut inside the first experiment reaches only that experiment, which is ' +
              "the SDK's documented behaviour — results are read experiment by experiment",
          ).toBe(1);
        });

        await test.step(
          'experiment_ids together with dataset_versions raises ValueError, before any request',
          async () => {
            // A client-side refusal, which is why it is the SDK's contract and
            // not the endpoint's: the paged read takes explicit experiments,
            // each already run against one version, so scoping it by version is
            // meaningless rather than merely unsupported.
            const both = await sdkClient.python.readDatasetExperimentItems({
              dataset_name: datasetName,
              project_name: project.name,
              experiment_ids: regular.map((e) => e.id),
              dataset_versions: ['latest'],
            });
            expect(both.error_type, 'refused client-side, by type').toBe('ValueError');
            expect(both.error_message).toBe(
              'dataset_versions can only be used when experiment_ids is omitted.',
            );
            expect(both.items, 'and nothing was read').toHaveLength(0);
          },
        );

        await test.step('An unknown dataset version raises DatasetVersionNotFound', async () => {
          // The SDK's translation of the endpoint's 404. Asserted by exception
          // CLASS, not by message: an empty result would read as "no experiment
          // ran against that version", which is a legitimate answer for a
          // version that exists, so only the named error separates a typo'd
          // hash from a genuinely unused one.
          const unknown = await sdkClient.python.readDatasetExperimentItems({
            dataset_name: datasetName,
            project_name: project.name,
            dataset_versions: ['deadbeefdeadbeefdeadbeefdeadbeef'],
          });
          expect(
            unknown.error_type,
            `an unknown version must raise, not answer empty (got ${unknown.error_type}: ${unknown.error_message})`,
          ).toBe('DatasetVersionNotFound');
          expect(unknown.items, 'and nothing was read').toHaveLength(0);
        });

        await test.step('dataset_versions=["latest"] is accepted and reads the whole seed', async () => {
          // The control for the refusal above: without it, a build that raised
          // for EVERY `dataset_versions` value would satisfy that assertion
          // while the parameter was unusable. The seed cuts one version, so
          // `latest` resolves to it and the row set is the unscoped one — which
          // is why this is a control and not a scoping claim (see the header).
          const latest = await sdkClient.python.readDatasetExperimentItems({
            dataset_name: datasetName,
            project_name: project.name,
            dataset_versions: ['latest'],
          });
          expect(
            latest.error_type,
            `"latest" must resolve on a one-version dataset (got ${latest.error_type}: ${latest.error_message})`,
          ).toBeNull();
          expect(latest.count, 'every regular experiment, over every item').toBe(
            regular.length * itemIds.length,
          );
        });
      },
    );
  },
);
