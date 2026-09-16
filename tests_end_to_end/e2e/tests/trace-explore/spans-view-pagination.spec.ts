import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Paging the Logs page's third entity view — Spans — and the two reads behind
 * it (OPIK-7791).
 *
 * Why this is worth a permanent spec. `traces.toggle-spans-view` is uncovered:
 * the estate drives the Traces and Threads toggles and never the Spans one. And
 * nothing anywhere pages spans. That matters more than an uncovered capability
 * usually does, because the failure it admits is silent — a wrong bound on the
 * cursor predicate drops rows from a page while the table still renders a
 * healthy-looking screenful, the right total in its footer, and no error.
 *
 * Two reads, not one. `GET /v1/private/spans` is offset-paged and
 * `POST /v1/private/spans/search` is cursor-paged (`last_retrieved_id`), and
 * they are separate queries rather than one wrapping the other — so a bound
 * fixed in one can still be wrong in the other.
 *
 * What each layer establishes:
 *
 * - The API tests compare a *set* to the exact ids the fixture minted. That is
 *   the assertion the UI cannot make: a table shows a page at a time, so
 *   "nothing was dropped and nothing repeated across the whole population" is
 *   only observable by collecting every page and comparing to the seed.
 * - The UI test drives the entity toggle and pages the rendered table, so the
 *   `@cap:` names something a browser exercised rather than a payload.
 *
 * Deterministic: the project is fresh, every id is minted by the fixture, and
 * nothing here reads the clock or depends on data that happens to exist.
 */

/** Page size for the offset-paged read — 130 spans over six pages, last one short. */
const API_PAGE_SIZE = 25;
/** Page size for the rendered table — three pages, the last one partial. */
const UI_PAGE_SIZE = 50;

/**
 * How long to let the table's own footer settle on the population it should
 * report.
 *
 * Generous on purpose, and not because the page is slow. The spans listing is
 * rate-limited per workspace (`getSpans:{workspaceId}`); on a shared
 * environment a browser fetch can be refused while this very spec is paging the
 * same endpoint, and the table then renders "No matching results" with no error
 * a user or a locator could see. `TracesSpansTab` refetches every 30s, so one
 * squeezed fetch heals itself — a budget that spans two of those intervals
 * turns an infrastructure blip into a delay instead of a failure, while a real
 * paging defect still fails, just later.
 */
const TABLE_SETTLE_MS = 90_000;

test.describe('Spans view pagination — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  /**
   * The fixture writes 130 spans and blocks until every one is queryable, and
   * the UI half then loads three pages. Declared on the describe so it covers
   * fixture setup too.
   */
  test.slow();

  test(
    'offset paging, cursor paging and the rendered Spans view each return every span exactly once',
    { tag: ['@cap:traces.toggle-spans-view'] },
    async ({ pagedSpans, project, backendClient, page }) => {
      await test.step('Offset paging to exhaustion returns the seeded set, no more and no less', async () => {
        const collected: string[] = [];
        const expectedPages = Math.ceil(pagedSpans.total / API_PAGE_SIZE);

        for (let pageNumber = 1; pageNumber <= expectedPages; pageNumber++) {
          const answer = await backendClient.listSpanIdsPage({
            projectId: project.id,
            page: pageNumber,
            size: API_PAGE_SIZE,
          });
          expect(answer.total, `page ${pageNumber} reports the whole population`).toBe(
            pagedSpans.total,
          );
          const expectedOnPage = Math.min(
            API_PAGE_SIZE,
            pagedSpans.total - (pageNumber - 1) * API_PAGE_SIZE,
          );
          // Per page, not only in the final set: a page that came back short
          // and a later page that repeated rows would cancel out in the union.
          expect(answer.ids.length, `rows on page ${pageNumber}`).toBe(expectedOnPage);
          collected.push(...answer.ids);
        }

        expect(new Set(collected).size, 'distinct ids across every page').toBe(collected.length);
        // The whole answer rather than "my ids are in there": an extra id would
        // mean the read reached beyond this project, which a subset check would
        // pass through in silence.
        expect([...collected].sort(), 'the union of every page').toEqual(pagedSpans.spanIds);

        // The page after the last must be empty, not a wrapped repeat of the
        // first — an off-by-one in the offset would otherwise never surface.
        const pastEnd = await backendClient.listSpanIdsPage({
          projectId: project.id,
          page: expectedPages + 1,
          size: API_PAGE_SIZE,
        });
        expect(pastEnd.ids, 'the page after the last').toEqual([]);
      });

      await test.step('Cursor paging returns the same set', async () => {
        const collected: string[] = [];
        const cursorLimit = 40;

        // Drained by cursor rather than by a single oversized request: the
        // cursor predicate is the thing under test, so it has to be applied
        // more than once.
        for (let request = 0; ; request++) {
          const ids = await backendClient.searchSpanIds({
            projectId: project.id,
            limit: cursorLimit,
            ...(collected.length ? { lastRetrievedId: collected[collected.length - 1] } : {}),
          });
          collected.push(...ids);
          if (ids.length < cursorLimit) break;
          // A cursor that stopped advancing would otherwise spin forever and
          // report as a test timeout rather than as the paging defect it is.
          expect(request, 'cursor requests needed to drain the project').toBeLessThan(
            Math.ceil(pagedSpans.total / cursorLimit) + 1,
          );
        }

        expect(new Set(collected).size, 'distinct ids across every cursor page').toBe(
          collected.length,
        );
        expect([...collected].sort(), 'the union of every cursor page').toEqual(
          pagedSpans.spanIds,
        );
      });

      const logs = new LogsPage(page);

      await test.step('The Spans toggle switches the table from traces to this project’s spans', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        // The Traces view of this project holds 5 rows; the Spans view holds
        // 130. The footer's population is therefore what says the toggle
        // actually changed which entity the table reads.
        //
        // Polled, not read once: the footer is absent until the list request
        // lands, and `waitForReady` is satisfied by the count card above the
        // table, which resolves from a different query.
        await expect
          .poll(() => logs.readPaginationTotal(), { timeout: TABLE_SETTLE_MS })
          .toBe(pagedSpans.traceIds.length);

        await logs.switchToSpans();

        // Polled rather than read once: the toggle flips before the refetch it
        // triggers has landed, so an immediate read can still see the traces
        // total.
        await expect
          .poll(() => logs.readPaginationTotal(), { timeout: TABLE_SETTLE_MS })
          .toBe(pagedSpans.total);
      });

      await test.step('Paging the rendered table covers every seeded span exactly once', async () => {
        await logs.gotoSpans(project.id, { size: UI_PAGE_SIZE });
        await expect
          .poll(() => logs.readPaginationTotal(), { timeout: TABLE_SETTLE_MS })
          .toBe(pagedSpans.total);

        const collected: string[] = [];
        const expectedPages = Math.ceil(pagedSpans.total / UI_PAGE_SIZE);

        for (let pageNumber = 1; pageNumber <= expectedPages; pageNumber++) {
          if (pageNumber > 1) await logs.goToNextPage();

          const expectedOnPage = Math.min(
            UI_PAGE_SIZE,
            pagedSpans.total - (pageNumber - 1) * UI_PAGE_SIZE,
          );
          const summary = await logs.readPaginationSummary();
          expect(summary.from, `first row shown on page ${pageNumber}`).toBe(
            (pageNumber - 1) * UI_PAGE_SIZE + 1,
          );
          expect(summary.to, `last row shown on page ${pageNumber}`).toBe(
            (pageNumber - 1) * UI_PAGE_SIZE + expectedOnPage,
          );

          // Rows, not just the footer: the footer's arithmetic comes from
          // `total` and the page number, so it would read correctly even if the
          // page rendered nothing.
          await expect(logs.traceRows, `rendered rows on page ${pageNumber}`).toHaveCount(
            expectedOnPage,
          );
          collected.push(...(await logs.readRowIdsOnPage()));
        }

        expect(new Set(collected).size, 'distinct rows across every rendered page').toBe(
          collected.length,
        );
        expect([...collected].sort(), 'the union of every rendered page').toEqual(
          pagedSpans.spanIds,
        );
      });
    },
  );

  test(
    'a span whose id is in the far future does not truncate the page that follows it',
    { tag: ['@cap:traces.toggle-spans-view'] },
    async ({ pagedSpans, farFuturePagedSpan, project, backendClient, page }) => {
      const withFarFuture = pagedSpans.total + 1;

      await test.step('The far-future span is in the project and carries its greatest id', async () => {
        // The premise the rest of the test rests on, asserted rather than
        // assumed. The cursor predicate is `id < :last_retrieved_id`, so using
        // this span as the cursor is only a meaningful test of the bound if
        // every other span really does sort below it.
        //
        // Note the listing's ORDER BY is `(workspace_id, project_id, trace_id,
        // id) DESC` — trace_id before id — so "greatest id" is deliberately not
        // stated as "first row": which span the table shows first depends on
        // which trace sorts first, not on this id.
        const everything = await backendClient.searchSpanIds({
          projectId: project.id,
          limit: withFarFuture + 10,
        });
        expect(everything.length, 'spans in the project').toBe(withFarFuture);
        // The whole answer, so that a read which had simply dropped the
        // far-future span cannot satisfy this.
        expect([...everything].sort(), 'every span in the project').toEqual(
          [...pagedSpans.spanIds, farFuturePagedSpan.id].sort(),
        );
        expect([...everything].sort().at(-1), 'the greatest id in the project').toBe(
          farFuturePagedSpan.id,
        );
      });

      await test.step('Paging past that cursor returns every ordinary span', async () => {
        // The regression this exists for: the bound on the cursor predicate has
        // to survive an id whose embedded instant is outside the range a 16-bit
        // date can hold. When it does not, this page comes back empty and the
        // read simply stops after the first row — no error, no gap a reader
        // notices.
        const afterCursor = await backendClient.searchSpanIds({
          projectId: project.id,
          limit: withFarFuture + 10,
          lastRetrievedId: farFuturePagedSpan.id,
        });
        expect([...afterCursor].sort(), 'the page after the far-future cursor').toEqual(
          pagedSpans.spanIds,
        );
      });

      await test.step('The offset-paged listing counts it too', async () => {
        // The other read, which pages by offset rather than by cursor and so
        // could disagree.
        const firstPage = await backendClient.listSpanIdsPage({
          projectId: project.id,
          page: 1,
          size: 1,
        });
        expect(firstPage.total, 'the listing’s own population').toBe(withFarFuture);
      });

      const logs = new LogsPage(page);

      await test.step('The Spans view still pages through every ordinary span with it present', async () => {
        // The far-future span IS in the table. A Logs preset that ends today
        // resolves to `intervalEnd: undefined` (`calculateIntervalStartAndEnd`)
        // and `TracesSpansTab` passes that straight through as `to_time`, so
        // the read carries a lower bound on the id and no upper one — nothing
        // excludes a mid-2200 id from the view a user sees.
        //
        // Which is exactly why this is the user-facing half of the fix: the row
        // is on screen, and the 130 ordinary spans have to remain reachable
        // around it rather than being truncated at the first page.
        await logs.gotoSpans(project.id, { size: UI_PAGE_SIZE });
        await expect
          .poll(() => logs.readPaginationTotal(), { timeout: TABLE_SETTLE_MS })
          .toBe(withFarFuture);

        const collected: string[] = [];
        const expectedPages = Math.ceil(withFarFuture / UI_PAGE_SIZE);
        for (let pageNumber = 1; pageNumber <= expectedPages; pageNumber++) {
          if (pageNumber > 1) await logs.goToNextPage();
          collected.push(...(await logs.readRowIdsOnPage()));
        }

        expect(new Set(collected).size, 'distinct rows across every rendered page').toBe(
          collected.length,
        );
        // Deliberately a set comparison and not a claim about which page the
        // far-future row lands on: the listing orders by trace_id before id, so
        // its position follows from which trace it hangs off — an ordering
        // detail this spec has no reason to pin.
        expect([...collected].sort(), 'the union of every rendered page').toEqual(
          [...pagedSpans.spanIds, farFuturePagedSpan.id].sort(),
        );
      });
    },
  );
});
