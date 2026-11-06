import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Picking an UNKEYED chip whose default operator takes no value out of the
 * "All filters" manager applies it on that one click — and picking a chip of
 * any other shape applies nothing.
 *
 * opik#8724 added a branch to `FilterManagerPopover.handleSelectUnpinned`:
 *
 * ```ts
 * if (def.kind === "query-builder" && !def.key) {
 *   const operator = def.defaultOperator ?? def.operators[0];
 *   if (NO_VALUE_OPERATORS.includes(operator)) {
 *     onApplyValue(def.id, { rows: [createFilter({ operator })] });
 *   }
 * }
 * ```
 *
 * `FilterManagerPopover` is shared by every page in the product that has a
 * filter bar, so a regression that widened either condition would start
 * applying filters nobody asked for across all of them — silently, because a
 * narrowed table looks like a table.
 *
 * ## The negatives are the spec
 *
 * That a one-click chip applies is easy to see and hard to get wrong. What is
 * worth pinning is the two conditions that must keep holding, each represented
 * by a chip the manager offers right beside Comments:
 *
 *  - **Trace name** is `query-builder` and unkeyed, but its `defaultOperator`
 *    is `contains`, which takes a value. Applying it would filter on the empty
 *    string.
 *  - **Trace feedback scores** is `query-builder` with a `key`, and its default
 *    is `>=`. Applying it would filter on an unnamed score.
 *
 * Both must pin and open their popover, and apply NOTHING. "Nothing" is
 * asserted at its cause rather than only at its symptom: every listing request
 * the page issues is recorded and the chip's own field is looked for in the
 * `filters` payload, because the Traces tab always sends a `filters` param (it
 * carries the visibility and source scopes), so "a request with filters" is not
 * evidence of anything on its own.
 *
 * ## Not covered here
 *
 * What the comments filter RETURNS — `comments-filter-traces.spec.ts` and
 * `comments-filter-threads.spec.ts`. The `boolean` branch of the same function,
 * which also applies on select: every boolean chip on the Traces tab
 * (`with_errors`) is pinned by default, so the manager never offers one and this
 * path is unreachable from here; `trace-filters.spec.ts` covers the chip itself
 * on the bar. And the manager's search box.
 */

/** The whole seed is seconds old, so a 24-hour window holds all of it. */
const TIME_RANGE = 'past24hours';

/** The three chips under test, by the label the manager lists them under. */
const TRACE_NAME_CHIP = { label: 'Trace name', id: 'name', field: 'name' } as const;
const FEEDBACK_SCORES_CHIP = {
  label: 'Trace feedback scores',
  id: 'feedback_scores',
  field: 'feedback_scores',
} as const;
const COMMENTS_CHIP = { label: 'Comments', id: 'comments', field: 'comments' } as const;

test.describe(
  'Trace filters — the All filters manager applies only a no-value unkeyed default',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'picking Comments filters on one click, while picking a value-taking or keyed chip pins it and filters on nothing',
      { tag: ['@cap:traces.filter-traces'] },
      async ({ filterableTraces, project, page }) => {
        const logs = new LogsPage(page);
        const seeded = filterableTraces.all;

        // Every project-level traces LISTING the page issues, recorded so the
        // negatives can be asserted at their cause (no request carrying the
        // chip's field) and not only at their symptom (the table did not
        // visibly change).
        //
        // Matched on the pathname with any trailing slash removed, which is
        // load-bearing twice over. The FE's `TRACES_REST_ENDPOINT` ends in a
        // slash, so the listing really is requested at
        // `…/v1/private/traces/` and a bare `endsWith('/v1/private/traces')`
        // matches NOTHING — the first version of this spec failed here with
        // zero recorded requests while the filter was working perfectly. And
        // matching the path rather than a substring is what keeps
        // `/v1/private/traces/threads` and `/v1/private/traces/{id}`, which
        // share the prefix, out of the count.
        const listingRequests: string[] = [];
        page.on('request', (request) => {
          if (request.method() !== 'GET') return;
          let url: URL;
          try {
            url = new URL(request.url());
          } catch {
            return;
          }
          const path = url.pathname.replace(/\/+$/, '');
          if (path.endsWith('/v1/private/traces')) listingRequests.push(request.url());
        });

        /**
         * The listings so far that carried a filter on `field`.
         *
         * The payload is PARSED rather than substring-matched: the param is a
         * JSON array, and a chip id can appear in it as a `key` or inside a
         * value without being the field under test.
         */
        const listingsFilteredOn = (field: string): string[] =>
          listingRequests.filter((raw) => {
            const payload = new URL(raw).searchParams.get('filters');
            if (payload === null) return false;
            try {
              return (JSON.parse(payload) as Array<{ field?: string }>).some(
                (f) => f.field === field,
              );
            } catch {
              return false;
            }
          });

        await test.step('Open the Traces tab on a known row count', async () => {
          // `gotoTraces` rather than `goto`: the active tab and the time range
          // are both persisted per project, so a spec that inherited them could
          // be driving the wrong table over the wrong window.
          await logs.gotoTraces(project.id, { timeRange: TIME_RANGE, size: 100 });
          await logs.waitForReady();
          expect(await logs.activeLogsTab(), 'the Traces table is the one on screen').toBe(
            'traces',
          );
          await expect(logs.traceRows).toHaveCount(seeded.length);
          expect(
            (await logs.readRowIdsOnPage()).slice().sort(),
            'the unfiltered table shows exactly the seeded traces',
          ).toEqual(seeded.map((t) => t.id).sort());
          await expect(
            logs.clearAllFiltersButton,
            'nothing is applied yet, so the bar offers nothing to clear',
          ).toBeHidden();
          expect(
            await logs.readUrlFilters('traces'),
            'and the URL carries no traces filter at all — distinct from carrying an empty one',
          ).toBeNull();
        });

        // The two negatives first, so the "no filter has ever been applied"
        // precondition still holds for both: once Comments has been applied and
        // cleared, `traces_filters` is no longer absent and the null assertion
        // above could not be made again.
        for (const chip of [TRACE_NAME_CHIP, FEEDBACK_SCORES_CHIP]) {
          await test.step(`Picking "${chip.label}" pins it and applies nothing`, async () => {
            const before = listingsFilteredOn(chip.field).length;
            expect(
              before,
              `no listing had filtered on \`${chip.field}\` before this step`,
            ).toBe(0);

            await logs.pinFilterChip(chip.label);

            // The pick's non-filtering half of the contract: the chip is pinned
            // and its popover is opened. Asserted because it is what separates
            // "nothing happened" from "the right nothing happened" — a manager
            // that had stopped pinning would also apply no filter.
            await expect(
              logs.filterChipPopover,
              `the manager pick opens ${chip.label}'s own popover`,
            ).toBeVisible();
            await expect(logs.filterChip(chip.id)).toHaveAttribute('aria-expanded', 'true');
            expect(
              await logs.readPinnedChipIds('traces'),
              `${chip.label} is now pinned to the bar, even though it filtered nothing`,
            ).toContain(chip.id);

            // The filtering half: nothing. Three independent readings of it, so
            // a pass cannot rest on one of them being insensitive.
            expect(
              listingsFilteredOn(chip.field),
              `picking ${chip.label} must issue no listing filtered on \`${chip.field}\` — its ` +
                'default operator needs a value (or a key) that the user has not given yet',
            ).toEqual([]);
            expect(
              await logs.readUrlFilters('traces'),
              `and must write no ${chip.label} filter into the URL`,
            ).toBeNull();
            await expect(
              logs.filterChip(chip.id),
              `the chip reads as unapplied — BaseChip appends ": <summary>" only once a filter ` +
                'is on it',
            ).toHaveText(chip.label);
            await expect(
              logs.clearAllFiltersButton,
              'and the bar still offers nothing to clear',
            ).toBeHidden();

            // Leave the table as it was for the next pick: the popover is
            // `modal`, so it swallows pointer events until dismissed.
            await logs.closeFilterChip();
            await expect(logs.traceRows, 'the table never narrowed').toHaveCount(seeded.length);
          });
        }

        await test.step('Picking "Comments" applies `comments is_not_empty` on that one click', async () => {
          // The positive, and the behaviour opik#8724 added: `comments` is
          // unkeyed and its `defaultOperator` is `is_not_empty`, which is a
          // complete filter on its own — so there is nothing left for the user
          // to type and the pick commits it.
          expect(
            listingsFilteredOn(COMMENTS_CHIP.field),
            'no listing had filtered on `comments` before this step',
          ).toEqual([]);

          await logs.pinFilterChip(COMMENTS_CHIP.label);

          const applied = await logs.waitForUrlFilters(
            'traces',
            (filters) => filters.some((f) => f.field === COMMENTS_CHIP.field),
          );
          expect(applied, 'exactly one filter is applied').toHaveLength(1);
          expect(applied[0].field, 'on the comments field').toBe(COMMENTS_CHIP.field);
          expect(applied[0].operator, 'with the chip default, which takes no value').toBe(
            'is_not_empty',
          );
          expect(applied[0].value, 'and so carries no value').toBe('');

          // The request actually went out, which is the half the URL cannot
          // say: a chip that wrote the param without refetching would leave the
          // table showing the unfiltered answer under a filtered label.
          await expect
            .poll(() => listingsFilteredOn(COMMENTS_CHIP.field).length, {
              message:
                'the page to issue a traces listing carrying the comments filter — the URL param ' +
                'is written first, so this is the separate question of whether anything refetched',
              timeout: 15_000,
            })
            .toBeGreaterThan(0);

          await expect(logs.filterChip(COMMENTS_CHIP.id)).toHaveText(/^Comments:\s*not empty$/);
          await expect(
            logs.clearAllFiltersButton,
            'and the bar reports exactly one applied filter',
          ).toHaveText('Clear all (1)');

          await expect(
            logs.filterChipPopover,
            'the applied chip is left open, the same as every other pick',
          ).toBeVisible();
          await logs.closeFilterChip();
        });

        await test.step('The table narrowed to nothing, because no seeded trace carries a comment', async () => {
          // The observable consequence, and the reason this fixture is the
          // right one: `filterableTraces` seeds tags, errors and feedback
          // scores and no comments at all, so a correctly applied
          // `comments is_not_empty` empties the table. That is a far stronger
          // signal that the filter reached the server than a label is.
          await expect(logs.traceRows).toHaveCount(0);
          await expect(logs.noMatchingResults).toBeVisible();
        });

        await test.step('Clearing all leaves the Comments chip pinned but empty', async () => {
          await logs.clearAllFilters();
          await expect(logs.traceRows, 'every seeded trace is back').toHaveCount(seeded.length);
          await expect(
            logs.filterChip(COMMENTS_CHIP.id),
            'the chip stays on the bar once pinned, now with no value on it',
          ).toHaveText(COMMENTS_CHIP.label);
          expect(
            await logs.readPinnedChipIds('traces'),
            'and it is still pinned',
          ).toContain(COMMENTS_CHIP.id);
        });

        await test.step('Re-opening the now-empty pinned Comments chip applies nothing', async () => {
          // The last way the new branch could leak: it lives in the MANAGER's
          // select handler, so opening the chip from the bar must not re-apply
          // a default. A chip that did would make "Clear all" undoable only
          // until the user looked at it again.
          const before = listingsFilteredOn(COMMENTS_CHIP.field).length;

          await logs.openFilterChip(COMMENTS_CHIP.id);
          await expect(
            logs.filterChipPopover,
            'clicking the pinned chip opens its popover',
          ).toBeVisible();

          expect(
            listingsFilteredOn(COMMENTS_CHIP.field).length,
            'opening the chip from the bar must not issue a filtered listing',
          ).toBe(before);
          await expect(
            logs.filterChip(COMMENTS_CHIP.id),
            'and the chip still reads as unapplied',
          ).toHaveText(COMMENTS_CHIP.label);
          await expect(
            logs.clearAllFiltersButton,
            'with nothing for the bar to clear',
          ).toBeHidden();

          await logs.closeFilterChip();
          await expect(logs.traceRows, 'and the table is still the full set').toHaveCount(
            seeded.length,
          );
        });
      },
    );
  },
);
