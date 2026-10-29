import { test, expect } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * How long the compare read's target-projects cache holds an entry.
 *
 * The second read is taken inside this window on purpose, so the CACHED set is
 * exercised and not only the freshly-computed one. Nothing sleeps for it; the
 * test asserts that the second read really did land inside the window rather
 * than assuming it.
 */
const TARGET_PROJECTS_TTL_MS = 10_000;

/**
 * Compare rows whose experiment item names a different project from its trace
 * (opik#8515, OPIK-8274).
 *
 * `experiment_items.project_id` is filled from the trace only when the item
 * named no project of its own, so the two can disagree — any SDK caller that
 * passes a project name produces exactly that. OPIK-8274 added a cached set of
 * "target projects" that the compare read prunes `traces`, `spans` and
 * `comments` by, and where that set comes from decides whether such a row
 * survives. Derived from the traces (which is what the query does) it is
 * right; derived from the denormalized item column it would hold only the
 * named project, and every row's trace data would be pruned away.
 *
 * The failure mode is why this is worth a spec. Pruning does not error. The
 * rows come back — right ids, right count — with empty input, output, duration
 * and cost. On the grid that reads as "this experiment logged nothing", which
 * is a conclusion someone would act on.
 *
 * Nothing in the estate covers it: every other comparison fixture logs its
 * traces into the same project its items name, so the two sources of truth
 * agree and the distinction cannot be seen.
 *
 * This pins behaviour rather than a bug — it passes today. That is the point of
 * writing it now: the lookup is new, it is cached, and nothing else is watching
 * the next change to it.
 */
test.describe(
  "Experiments comparison — item project differs from its trace's",
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    /** Same 120s seed budget and reason as the other comparison specs. */
    test.slow();

    test(
      'every row keeps its trace input, output, duration and cost, fresh and cached',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ compareProjectMismatch, backendClient, page }) => {
        const seed = compareProjectMismatch;
        const expectedById = new Map(seed.items.map((item) => [item.datasetItemId, item]));

        /**
         * Read the comparison and reduce it to one entry per dataset item,
         * carrying the trace-sourced fields that pruning would blank.
         *
         * The row shape is asserted on the way through: exactly one experiment
         * item per row, naming the trace the seed paired it with. A row that
         * came back with none would otherwise reduce to `undefined` and fail
         * somewhere less informative.
         */
        const readRows = async (label: string) => {
          const answer = await backendClient.compareItemsTraceData({
            datasetId: seed.datasetId,
            experimentIds: [seed.experimentId],
            page: 1,
            size: 100,
          });
          expect(answer.total, `${label}: rows in the comparison`).toBe(seed.items.length);
          expect(answer.rows, `${label}: rows returned`).toHaveLength(seed.items.length);
          return answer.rows.map((row) => {
            expect(
              row.experimentItems.map((ei) => ei.traceId),
              `${label}: row ${row.id} must carry exactly the trace it was seeded with`,
            ).toEqual([expectedById.get(row.id)?.traceId]);
            const ei = row.experimentItems[0];
            return {
              id: row.id,
              input: ei.input,
              output: ei.output,
              duration: ei.duration,
              cost: ei.totalEstimatedCost,
            };
          });
        };

        /** What each row must carry: its own seeded payload, and real numbers. */
        const expectRowsIntact = (
          rows: Awaited<ReturnType<typeof readRows>>,
          label: string,
        ) => {
          const blanks = rows.filter(
            (row) =>
              row.input === null ||
              row.output === null ||
              row.duration === null ||
              row.cost === null,
          );
          // Named as a collection, so a failure lists every row the pruning
          // took rather than stopping at the first.
          expect(
            blanks.map((row) => row.id),
            `${label}: rows whose trace data came back empty — the signature of a target-projects ` +
              'set built from experiment_items.project_id instead of from the traces',
          ).toEqual([]);

          // Not merely non-empty: the row's OWN output. A pruning that
          // returned some other row's trace would satisfy a presence check.
          for (const row of rows) {
            const seeded = expectedById.get(row.id);
            expect(seeded, `${label}: row ${row.id} is one of the seeded items`).toBeDefined();
            expect(row.output, `${label}: row ${row.id}'s output`).toEqual(seeded?.output);
            expect(row.input, `${label}: row ${row.id}'s input`).toEqual(seeded?.input);
            expect(
              row.duration,
              `${label}: row ${row.id}'s duration must be a real elapsed time`,
            ).toBeGreaterThan(0);
            expect(
              row.cost,
              `${label}: row ${row.id}'s cost must resolve from its priced span`,
            ).toBeGreaterThan(0);
          }
        };

        const firstReadAt = Date.now();
        const fresh = await test.step('Read the comparison once, priming the cache', () =>
          readRows('fresh read'),
        );
        await test.step('Every row carries its own trace data', () => {
          expectRowsIntact(fresh, 'fresh read');
        });

        await test.step('Read it again inside the cache TTL, and it still does', async () => {
          const cached = await readRows('cached read');
          // The premise of this step, asserted rather than assumed: past the
          // TTL the entry has expired and the second read recomputed the set,
          // which is the same thing the first read did — so the step would be
          // a duplicate of the one above under this spec's name.
          const elapsed = Date.now() - firstReadAt;
          expect(
            elapsed,
            `the second read finished ${elapsed}ms after the first; past ` +
              `${TARGET_PROJECTS_TTL_MS}ms the target-projects entry may have expired, so the ` +
              'CACHED set is no longer what was exercised',
          ).toBeLessThan(TARGET_PROJECTS_TTL_MS);
          expectRowsIntact(cached, 'cached read');
        });

        await test.step('The Results grid renders the same rows with data in them', async () => {
          // The API and the grid are two projections of one query, and the
          // grid is where a person would draw the wrong conclusion from empty
          // cells — so the render is asserted, not inferred.
          const compare = new CompareExperimentsPage(
            page,
            seed.tracesProjectId,
            seed.datasetId,
            [seed.experimentId],
          );
          await compare.gotoResults();
          await compare.waitForResultsReady();
          await compare.expectRenderedRowCount(seed.items.length);

          for (const item of seed.items) {
            expect(
              await compare.readSingleExperimentCellText(item.datasetItemId, 'output_output'),
              `the output cell for row ${item.datasetItemId}`,
            ).toContain(item.output.output);
            // Duration and cost too: they come from the trace and the SPAN
            // respectively, which the pruning reaches through different joins,
            // so an output cell alone would not notice one of them being
            // blanked. Asserted as "not the empty placeholder" rather than
            // against a formatted string, since the display format is not this
            // spec's subject.
            for (const columnId of ['duration', 'total_estimated_cost']) {
              const rendered = await compare.readSingleExperimentCellText(
                item.datasetItemId,
                columnId,
              );
              expect(
                rendered,
                `the ${columnId} cell for row ${item.datasetItemId} must carry a value, not the ` +
                  'empty placeholder a pruned trace leaves behind',
              ).not.toBe('-');
              expect(
                rendered,
                `the ${columnId} cell for row ${item.datasetItemId} must not be blank`,
              ).not.toBe('');
            }
          }
        });
      },
    );
  },
);
