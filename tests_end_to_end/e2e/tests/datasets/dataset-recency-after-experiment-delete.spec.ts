import { test, expect } from '@e2e/fixtures';
import type { ExperimentTypeDatasetRef, ExperimentTypeDatasetsRef } from '@e2e/fixtures';
import type { BackendClient, DatasetSummaryRef } from '@e2e/core/backend';

/**
 * A dataset's last-experiment bookkeeping after experiments are deleted
 * (OPIK-8577 / opik#8607).
 *
 * `DatasetEventListener.onExperimentsDeleted` used to guard on the raw
 * `datasetInfo` of the delete event, then hand the REGULAR-filtered ids to
 * `getMostRecentCreatedExperimentFromDatasets` — which rejects an empty set. So
 * a delete carrying only non-REGULAR experiments passed the guard and threw out
 * of the listener, AFTER the delete had already committed and answered the
 * caller 204. Nothing surfaced: the experiments were gone, the request had
 * succeeded, and the bookkeeping was left in whatever state the exception
 * interrupted.
 *
 * Two fields are read, and they are different facts:
 *
 *  - **`last_created_experiment_at`** is the stored `datasets` column the
 *    listener owns, and it is REGULAR-only: a trial's creation never writes it,
 *    and a delete recomputes it only for datasets whose regular experiments were
 *    in the event. It is therefore the only one of the two that a broken listener
 *    can leave permanently wrong.
 *  - **`experiment_count` / `most_recent_experiment_at`** are computed from
 *    `experiment_items` on every read, with no type filter, so they re-derive
 *    themselves whatever the listener did. They are asserted because they are
 *    what a user reads off the Datasets list — but a spec that asserted only
 *    these could not fail for the bug at all.
 *
 * Scoped honestly: what is asserted here is the POST-FIX CONTRACT in all three
 * shapes, not the exception itself. The throw was only ever visible in backend
 * logs — the HTTP answer was 204 either way, because Guava's EventBus hands a
 * subscriber's exception to its own handler rather than to the caller — and
 * staging does not expose those logs. So no e2e assertion can watch the listener
 * fail; what it can do is pin what must be true of the bookkeeping afterwards, in
 * each of the three shapes the guard distinguishes.
 *
 * Three shapes, and the third is what makes the first two mean anything. Both of
 * those assert that a column did NOT move, which a backend whose post-delete
 * bookkeeping never fires at all satisfies perfectly; the third deletes the last
 * REGULAR experiment through the same code path and requires the column to clear.
 *
 * Every test deletes, so the two datasets it does not name are its bystanders:
 * "the target's count dropped" would be satisfied just as well by a delete that
 * took every experiment in the workspace, and the three shapes deliberately
 * differ so a sweep cannot be mistaken for a no-op.
 *
 * API-level throughout. The Datasets list page is project-scoped and rendered its
 * empty state for API-created datasets when this was explored by hand, so the page
 * cannot observe this seed — and the claim is about numbers a delete recomputes,
 * which the list READ answers directly.
 */

/** The summary as both reads answer it, asserted to agree before either is used. */
async function readSummary(
  backendClient: BackendClient,
  projectId: string,
  dataset: ExperimentTypeDatasetRef,
): Promise<DatasetSummaryRef> {
  const detail = await backendClient.getDatasetSummary(dataset.id);
  const listed = (await backendClient.listDatasetSummaries({ projectId })).rows.find(
    (row) => row.id === dataset.id,
  );
  // The list and the detail are two different projections over the same four
  // lookups, and disagreeing is itself the bug: a user reading a number off the
  // list and then opening the dataset would see two different truths.
  expect(listed, `dataset '${dataset.name}' is on the project's dataset list`).toBeDefined();
  expect(listed, `the list and the detail agree for '${dataset.name}'`).toEqual(detail);
  return detail;
}

/**
 * Poll the dataset summary until `experiment_count` settles at `expected`.
 *
 * The delete is committed before the response, but the figures above are read
 * back out of ClickHouse, which is eventually consistent — so the count is
 * polled rather than read once. The POLL IS ON THE COUNT ALONE: the stamp
 * assertions below are made against the summary this returns, so a stamp that
 * was briefly right and then wrong cannot be polled into passing.
 */
async function summaryOnceCountIs(
  backendClient: BackendClient,
  projectId: string,
  dataset: ExperimentTypeDatasetRef,
  expected: number,
): Promise<DatasetSummaryRef> {
  await expect
    .poll(
      async () => (await backendClient.getDatasetSummary(dataset.id)).experimentCount,
      {
        timeout: 60_000,
        intervals: [500, 1_000, 2_000],
        message: `'${dataset.name}' must report exactly ${expected} experiment(s) after the delete`,
      },
    )
    .toBe(expected);
  return readSummary(backendClient, projectId, dataset);
}

/** Every dataset the test did not name, with the summary each started at. */
function bystanders(
  seed: ExperimentTypeDatasetsRef,
  target: ExperimentTypeDatasetRef,
): ExperimentTypeDatasetRef[] {
  return seed.all.filter((d) => d.id !== target.id);
}

test.describe(
  'Datasets — last-experiment bookkeeping after an experiment delete',
  { tag: ['@t2-cuj', '@area:datasets'] },
  () => {
    test(
      'Deleting only a non-REGULAR experiment succeeds and leaves the dataset regular-experiment stamp alone',
      { tag: ['@cap:datasets.experiment-recency-after-delete'] },
      async ({ experimentTypeDatasets, project, backendClient }) => {
        const dataset = experimentTypeDatasets.mixed;
        const trial = dataset.experiments.find((e) => e.type === 'trial')!;
        const regular = dataset.experiments.find((e) => e.type === 'regular')!;

        const before = await test.step('The seed really recorded both experiments and a regular stamp', async () => {
          // Asserted before the delete, and it is not ceremony: every claim below
          // is a comparison against these values, so a seed whose trial never
          // landed would leave the count assertion passing over a drop that never
          // happened, and a seed with a null stamp would make "the stamp did not
          // change" true by vacuity.
          const summary = await summaryOnceCountIs(
            backendClient,
            project.id,
            dataset,
            dataset.experiments.length,
          );
          expect(
            summary.lastCreatedExperimentAt,
            'a REGULAR experiment was created, so the stored stamp must be set',
          ).not.toBeNull();
          expect(
            summary.mostRecentExperimentAt,
            'the experiments carry items, so the computed recency must be set',
          ).not.toBeNull();
          return summary;
        });

        const bystandersBefore = await test.step('Record the untouched datasets', async () =>
          Promise.all(
            bystanders(experimentTypeDatasets, dataset).map(async (other) => ({
              dataset: other,
              summary: await summaryOnceCountIs(
                backendClient,
                project.id,
                other,
                other.experiments.length,
              ),
            })),
          ));

        await test.step('Delete ONLY the trial', async () => {
          const answer = await backendClient.deleteExperimentsBatch([trial.id]);
          expect(
            answer.status,
            `the batch delete of a lone non-REGULAR experiment must answer 204 (got ${answer.status}: ${answer.message})`,
          ).toBe(204);
        });

        await test.step('The trial is gone and the regular experiment is not', async () => {
          expect(await backendClient.experimentExists(trial.id), 'the trial was deleted').toBe(
            false,
          );
          expect(
            await backendClient.experimentExists(regular.id),
            'the delete named one id and must have taken only that one',
          ).toBe(true);
        });

        const after = await test.step('The dataset counts one experiment and keeps its regular stamp', async () => {
          const summary = await summaryOnceCountIs(
            backendClient,
            project.id,
            dataset,
            dataset.experiments.length - 1,
          );
          // The load-bearing assertion. The trial never contributed to this
          // column, so a listener that recomputed it here — or that nulled it via
          // the no-experiments branch — is wrong in a way nothing else observes:
          // the value never self-corrects, because only a regular experiment's
          // creation or deletion writes it again.
          expect(
            summary.lastCreatedExperimentAt,
            'deleting a trial must not touch the stamp the dataset still has a REGULAR experiment for',
          ).toBe(before.lastCreatedExperimentAt);
          expect(
            summary.mostRecentExperimentAt,
            "the surviving experiment still has items, so the computed recency must still be set",
          ).not.toBeNull();
          return summary;
        });

        await test.step('The bystander datasets are untouched', async () => {
          for (const { dataset: other, summary: expected } of bystandersBefore) {
            expect(
              await readSummary(backendClient, project.id, other),
              `'${other.name}' was never named by the delete and must be byte-identical`,
            ).toEqual(expected);
          }
          // And by id, not only by summary: a summary can agree while the rows
          // behind it are gone and the figures are being re-derived from nothing.
          for (const other of bystanders(experimentTypeDatasets, dataset)) {
            for (const experiment of other.experiments) {
              expect(
                await backendClient.experimentExists(experiment.id),
                `bystander experiment ${experiment.name} survived`,
              ).toBe(true);
            }
          }
        });

        expect(
          after.experimentCount,
          'the count dropped by exactly one, not to zero',
        ).toBe(before.experimentCount - 1);
      },
    );

    test(
      'Deleting a dataset whose only experiments are non-REGULAR still reports success and leaves it consistent',
      { tag: ['@cap:datasets.experiment-recency-after-delete'] },
      async ({ experimentTypeDatasets, project, backendClient }) => {
        const dataset = experimentTypeDatasets.trialOnly;
        const trialIds = dataset.experiments.map((e) => e.id);

        const before = await test.step('The seed recorded two trials and NO regular stamp', async () => {
          const summary = await summaryOnceCountIs(
            backendClient,
            project.id,
            dataset,
            dataset.experiments.length,
          );
          // A trial's creation is skipped by the listener, so this dataset has
          // never had a stamp — which is what makes it the shape whose delete
          // event carries no REGULAR entry at all.
          expect(
            summary.lastCreatedExperimentAt,
            'no REGULAR experiment was ever created against this dataset',
          ).toBeNull();
          expect(
            summary.mostRecentExperimentAt,
            'the trials carry items, so the computed recency is set even with no regular run',
          ).not.toBeNull();
          return summary;
        });

        const bystandersBefore = await test.step('Record the untouched datasets', async () =>
          Promise.all(
            bystanders(experimentTypeDatasets, dataset).map(async (other) => ({
              dataset: other,
              summary: await summaryOnceCountIs(
                backendClient,
                project.id,
                other,
                other.experiments.length,
              ),
            })),
          ));

        await test.step('Delete both trials in one call', async () => {
          const answer = await backendClient.deleteExperimentsBatch(trialIds);
          expect(
            answer.status,
            `a delete whose event carries no REGULAR entry must still answer 204 (got ${answer.status}: ${answer.message})`,
          ).toBe(204);
        });

        await test.step('Both are gone', async () => {
          for (const id of trialIds) {
            expect(await backendClient.experimentExists(id), `experiment ${id} was deleted`).toBe(
              false,
            );
          }
        });

        await test.step('And the dataset reads as having no experiments at all', async () => {
          const summary = await summaryOnceCountIs(backendClient, project.id, dataset, 0);
          expect(
            summary.mostRecentExperimentAt,
            'nothing is recorded against the dataset any more, so the computed recency must clear',
          ).toBeNull();
          expect(
            summary.lastCreatedExperimentAt,
            'it was null before the delete and there was never a regular run to recompute from',
          ).toBeNull();
          expect(
            summary.datasetItemsCount,
            'the dataset itself and its items are untouched by an experiment delete',
          ).toBe(before.datasetItemsCount);
        });

        await test.step('The bystander datasets are untouched', async () => {
          for (const { dataset: other, summary: expected } of bystandersBefore) {
            expect(
              await readSummary(backendClient, project.id, other),
              `'${other.name}' was never named by the delete and must be byte-identical`,
            ).toEqual(expected);
          }
          for (const other of bystanders(experimentTypeDatasets, dataset)) {
            for (const experiment of other.experiments) {
              expect(
                await backendClient.experimentExists(experiment.id),
                `bystander experiment ${experiment.name} survived`,
              ).toBe(true);
            }
          }
        });
      },
    );

    test(
      'Deleting the last REGULAR experiment clears the stamp, which is what proves the listener runs',
      { tag: ['@cap:datasets.experiment-recency-after-delete'] },
      async ({ experimentTypeDatasets, project, backendClient }) => {
        // The discriminating test of the three, and the reason the other two are
        // worth anything. Both of those assert that a column did NOT move, which
        // a backend whose post-delete bookkeeping never fires at all satisfies
        // perfectly — and never firing is the shape of the opik#8607 bug. Here
        // the same column MUST move, through the same code path, on a seed that
        // differs from the first test's only in which experiment is deleted.
        const dataset = experimentTypeDatasets.regularOnly;
        const regular = dataset.experiments.find((e) => e.type === 'regular')!;

        const before = await test.step('The seed recorded a regular stamp', async () => {
          const summary = await summaryOnceCountIs(
            backendClient,
            project.id,
            dataset,
            dataset.experiments.length,
          );
          expect(
            summary.lastCreatedExperimentAt,
            'a REGULAR experiment was created, so the stored stamp must be set',
          ).not.toBeNull();
          return summary;
        });

        const bystandersBefore = await test.step('Record the untouched datasets', async () =>
          Promise.all(
            bystanders(experimentTypeDatasets, dataset).map(async (other) => ({
              dataset: other,
              summary: await summaryOnceCountIs(
                backendClient,
                project.id,
                other,
                other.experiments.length,
              ),
            })),
          ));

        await test.step('Delete ONLY the regular experiment', async () => {
          const answer = await backendClient.deleteExperimentsBatch([regular.id]);
          expect(
            answer.status,
            `the batch delete must answer 204 (got ${answer.status}: ${answer.message})`,
          ).toBe(204);
        });

        await test.step('It is gone', async () => {
          expect(
            await backendClient.experimentExists(regular.id),
            'the regular experiment was deleted',
          ).toBe(false);
        });

        await test.step('The stamp CLEARS, because no experiment is left to hold it', async () => {
          // Asserted as null rather than as "some earlier timestamp", and that is
          // why this dataset holds a regular experiment and nothing else.
          // `FIND_MOST_RECENT_CREATED_EXPERIMENT_BY_DATASET_IDS` takes
          // max(created_at) over every experiment of the dataset with no type
          // filter, so a surviving trial would simply become the new stamp —
          // correct, but only assertable by comparing two timestamps that MySQL
          // stores at microsecond precision and ClickHouse at nanosecond, which
          // `ExperimentService` itself documents as only approximately equal.
          // With nothing left, `updateDatasetsWithoutExperiments` writes null,
          // and null is exact.
          await expect
            .poll(
              async () =>
                (await backendClient.getDatasetSummary(dataset.id)).lastCreatedExperimentAt,
              {
                timeout: 60_000,
                intervals: [500, 1_000, 2_000],
                message:
                  'with its only experiment gone the dataset must hold no regular-experiment stamp',
              },
            )
            .toBeNull();

          const summary = await summaryOnceCountIs(
            backendClient,
            project.id,
            dataset,
            dataset.experiments.length - 1,
          );
          expect(
            summary.mostRecentExperimentAt,
            'nothing is recorded against the dataset any more, so the computed recency must clear too',
          ).toBeNull();
          expect(summary.experimentCount, 'the count dropped by exactly one').toBe(
            before.experimentCount - 1,
          );
        });

        await test.step('The bystander datasets are untouched', async () => {
          for (const { dataset: other, summary: expected } of bystandersBefore) {
            expect(
              await readSummary(backendClient, project.id, other),
              `'${other.name}' was never named by the delete and must be byte-identical`,
            ).toEqual(expected);
          }
          for (const other of bystanders(experimentTypeDatasets, dataset)) {
            for (const experiment of other.experiments) {
              expect(
                await backendClient.experimentExists(experiment.id),
                `bystander experiment ${experiment.name} survived`,
              ).toBe(true);
            }
          }
        });
      },
    );
  },
);
