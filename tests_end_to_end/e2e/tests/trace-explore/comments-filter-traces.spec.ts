import { test, expect } from '@e2e/fixtures';
import type { BackendFilter } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The Traces tab's `comments` filter (opik#8724) keeps exactly the traces
 * carrying a comment of their OWN entity type.
 *
 * `FilterQueryBuilder` renders `TraceField.COMMENTS` as
 * `id IN (SELECT entity_id FROM comments WHERE workspace_id = … AND
 * entity_type = 'trace')`. The `entity_type` predicate is the whole subject:
 * drop it and the subquery returns every commented entity id in the workspace,
 * so a trace whose SPAN carries the only comment starts matching — wrong rows
 * in a plausible number, which is the quiet failure this spec exists for.
 *
 * ## The discriminator
 *
 * `span-commented` in the fixture is a trace with no comment of its own whose
 * single span has one. It must be EXCLUDED. Nothing else in the seed
 * distinguishes a correctly scoped lookup from one that joined the comments
 * table on entity id alone.
 *
 * ## Why exactness, and why both halves
 *
 * Every assertion pins the surviving id SET and the envelope's `total`, not
 * just a count of rows: a filter that returned the right number of wrong rows
 * is the regression, and the listing and the count are separate queries over
 * the same clause, so one can leak without the other. `is_empty` is asserted
 * alongside `is_not_empty` because the two are different operator mappings
 * (`NOT (…)` and `(…)`) and a build that inverted one would leave the other
 * correct.
 *
 * ## Not covered here
 *
 * The Threads tab's own `comments` filter — a different clause
 * (`thread_model_id IN … entity_type = 'thread'`) on a different endpoint,
 * covered by `comments-filter-threads.spec.ts` under `@area:threads`. And the
 * one-click apply behaviour of picking the chip out of the All filters manager,
 * which is `filter-manager-no-value-chip.spec.ts`: this spec uses that path
 * only as the way to reach the filter, and asserts what the filter RETURNS.
 *
 * Also not covered: `contains` / `=` over comment text. `COMMENTS_OPERATORS`
 * exposes only `is_not_empty` and `is_empty`, so no operator reads the text.
 */

/**
 * The filter exactly as the chip bar sends it.
 *
 * `type: 'list'` and the empty `value` are what `QueryBuilderChip` serialises
 * for this chip (`columnType: COLUMN_TYPE.list`, a no-value operator), so the
 * API half of this spec asks the byte-identical question the UI half does. A
 * read that omitted them would still work — the backend validates against
 * `TraceField.COMMENTS`'s own declared type — and would quietly stop being a
 * comparison between the two surfaces.
 */
const commentsFilter = (operator: 'is_not_empty' | 'is_empty'): BackendFilter[] => [
  { field: 'comments', operator, value: '', type: 'list' },
];

/** The whole seed is seconds old, so a 24-hour window holds all of it. */
const TIME_RANGE = 'past24hours';

test.describe('Trace filters — comments', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'the comments filter returns exactly the traces carrying a TRACE comment, and its complement returns exactly the rest',
    { tag: ['@cap:traces.filter-traces'] },
    async ({ commentedEntities, project, backendClient }) => {
      // No page. The subject is which rows the clause selects; the table
      // renders whatever came back, and the UI half is the second test.
      const seed = commentedEntities;

      await test.step('Every seeded trace is queryable before any filter is applied', async () => {
        // The baseline that makes both filtered answers subsets rather than
        // coincidences, and the ingestion wait for the whole spec: an
        // unfiltered read short of six traces is a write still landing, not an
        // answer about comments.
        await expect
          .poll(
            async () => (await backendClient.listTracePage({ projectId: project.id })).ids.length,
            {
              timeout: 60_000,
              intervals: [500, 1_000, 2_000],
              message:
                `all ${seed.traces.length} seeded traces of project ${project.id} to become ` +
                'queryable — a count stuck below that is trace ingestion still catching up',
            },
          )
          .toBe(seed.traces.length);

        const unfiltered = await backendClient.listTracePage({ projectId: project.id });
        expect(
          unfiltered.ids.slice().sort(),
          'the project holds exactly the seeded traces and nothing else',
        ).toEqual(seed.traces.map((t) => t.id).sort());
        expect(unfiltered.total, 'and the envelope counts them all').toBe(seed.traces.length);
      });

      await test.step('`comments is_not_empty` keeps the two traces with a trace comment, and nothing else', async () => {
        // The exactness assertion, and where the span-comment control earns its
        // place: that trace has a comment in the workspace's comments table,
        // just not one of type `trace`, so an unscoped subquery returns three
        // ids here instead of two.
        const page = await backendClient.listTracePage({
          projectId: project.id,
          filters: commentsFilter('is_not_empty'),
        });
        expect(
          page.ids.slice().sort(),
          'exactly the traces carrying a TRACE comment — the trace whose only comment is on its ' +
            'SPAN must be excluded, which is what says the entity_type scope is still applied',
        ).toEqual(seed.tracesWithTraceComment.slice().sort());
        expect(
          page.total,
          'and the count query agrees with the listing — they are separate queries over the ' +
            'same clause, so one can leak without the other',
        ).toBe(seed.tracesWithTraceComment.length);
      });

      await test.step('`comments is_empty` keeps exactly the complementary four', async () => {
        // A separate operator mapping (`NOT (…)`), not the inverse of the step
        // above by construction — so a build that broke one would leave the
        // other correct. Asserted as the full complement, which is also what
        // says the two answers PARTITION the project: nothing is in both and
        // nothing is in neither.
        const page = await backendClient.listTracePage({
          projectId: project.id,
          filters: commentsFilter('is_empty'),
        });
        expect(
          page.ids.slice().sort(),
          'exactly the traces carrying no trace comment, the span-commented one among them',
        ).toEqual(seed.tracesWithoutTraceComment.slice().sort());
        expect(page.total, 'and the count agrees').toBe(seed.tracesWithoutTraceComment.length);

        expect(
          [...seed.tracesWithTraceComment, ...seed.tracesWithoutTraceComment].sort(),
          'the two answers must partition the project — no trace in both, none in neither',
        ).toEqual(seed.traces.map((t) => t.id).sort());
      });
    },
  );

  test(
    'the Traces table narrows to exactly the trace-commented rows when the Comments filter is applied',
    { tag: ['@cap:traces.filter-traces'] },
    async ({ commentedEntities, project, backendClient, page }) => {
      const seed = commentedEntities;
      const logs = new LogsPage(page);

      await test.step('The server really answers the filtered question before the page is read for it', async () => {
        // A UI assertion over a seed whose comments never landed is a test that
        // cannot fail: the table would narrow to nothing and the expected rows
        // would simply never appear, indistinguishable from a rendering bug.
        await expect
          .poll(
            async () =>
              (
                await backendClient.listTracePage({
                  projectId: project.id,
                  filters: commentsFilter('is_not_empty'),
                })
              ).ids.length,
            {
              timeout: 60_000,
              intervals: [500, 1_000, 2_000],
              message:
                'the server to return the two trace-commented traces — a timeout here is the ' +
                'seed still landing, not the table rendering the wrong rows',
            },
          )
          .toBe(seed.tracesWithTraceComment.length);
      });

      await test.step('Open the Traces tab and confirm every seeded trace is listed', async () => {
        // `gotoTraces` rather than `goto`: the active tab is persisted per
        // project, and a bare /logs resolves to Threads on a fresh profile. The
        // time range is stated for the same reason — it is persisted too, and
        // it decides whether the read is windowed at all.
        await logs.gotoTraces(project.id, { timeRange: TIME_RANGE, size: 100 });
        await logs.waitForReady();
        expect(await logs.activeLogsTab(), 'the Traces table is the one on screen').toBe('traces');
        await expect(logs.traceRows).toHaveCount(seed.traces.length);
        expect(
          (await logs.readRowIdsOnPage()).slice().sort(),
          'the unfiltered table shows exactly the seeded traces',
        ).toEqual(seed.traces.map((t) => t.id).sort());
      });

      await test.step('Pick Comments from the All filters manager', async () => {
        // The chip is not pinned by default (`TRACE_DEFAULT_PINNED_CHIPS` is
        // with_errors / tags / metadata), so the manager is the only way a user
        // reaches it. Settled on the URL carrying the filter — the param is
        // what the next listing request is built from — rather than on a row
        // count, which is the assertion below.
        await logs.pinFilterChip('Comments');
        const filters = await logs.waitForUrlFilters(
          'traces',
          (applied) =>
            applied.length === 1 &&
            applied[0].field === 'comments' &&
            applied[0].operator === 'is_not_empty',
        );
        expect(
          filters,
          'exactly one filter, on `comments` with the no-value operator the chip defaults to',
        ).toHaveLength(1);
        // The wire shape, not just the field: `type` is what the backend reads
        // to pick an operator mapping, and a chip that sent the wrong one would
        // 400 rather than filter.
        expect(filters[0].type, 'the chip serialises its list column type').toBe('list');
        expect(filters[0].value, 'a no-value operator sends no value').toBe('');

        // Picking from the manager also OPENS the chip's popover
        // (`onRequestOpenChip`), and `FilterChipPopover` mounts it `modal` — so
        // it swallows every pointer event outside itself until it is dismissed.
        // Asserted rather than silently worked around, because the open popover
        // is part of what the manager pick does; then dismissed, so the steps
        // below can actually reach the page.
        await expect(
          logs.filterChipPopover,
          'the manager pick leaves the chip it applied open',
        ).toBeVisible();
        await logs.closeFilterChip();
      });

      await test.step('The table shows exactly the two trace-commented rows', async () => {
        // Pinned as the rendered `data-row-id` SET, not as a count: a filter
        // that returned the wrong rows in the right number is the regression,
        // and the span-commented trace appearing here is precisely what a
        // dropped entity_type scope looks like on the page.
        await expect(logs.traceRows).toHaveCount(seed.tracesWithTraceComment.length);
        expect(
          (await logs.readRowIdsOnPage()).slice().sort(),
          'the rendered rows are exactly the API\'s answer to the same question',
        ).toEqual(seed.tracesWithTraceComment.slice().sort());
        for (const id of seed.tracesWithoutTraceComment) {
          await expect(
            logs.traceRow(id),
            `trace ${id} carries no trace comment and must not be listed`,
          ).toBeHidden();
        }
        // The chip reports the filter it is holding, and the bar reports that
        // one filter is applied. Both are what a user reads to know the table
        // is narrowed at all.
        await expect(logs.filterChip('comments')).toHaveText(/Comments:\s*not empty/);
        await expect(logs.clearAllFiltersButton).toHaveText('Clear all (1)');
      });

      await test.step('Clearing the filter restores the full set', async () => {
        await logs.clearAllFilters();
        await expect(logs.traceRows).toHaveCount(seed.traces.length);
        expect(
          (await logs.readRowIdsOnPage()).slice().sort(),
          'every seeded trace is back',
        ).toEqual(seed.traces.map((t) => t.id).sort());
      });
    },
  );
});
