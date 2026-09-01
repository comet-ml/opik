import { test, expect } from '@e2e/fixtures';

/**
 * `POST /v1/private/traces/search` paged with a far-future cursor
 * (OPIK-7791, PR #8096).
 *
 * The commit message singles this out as the failure a lower-bound-only fix
 * would miss. `last_retrieved_id` is a cursor lifted from a row the stream just
 * returned, and it is paired with `toMonday(id_at) <= toMonday(:cursor)`. When
 * the cursor is far-future its Monday wraps into the past, every ordinary row's
 * honest week is later, and the `<=` excludes all of them: the next page comes
 * back **empty** and pagination stops silently. Nothing errors — the caller just
 * believes it has read the whole project.
 *
 * The upstream guard for it runs against the legacy pre-cutover table, and
 * nothing in this estate drives `last_retrieved_id` at all. API-level by design:
 * this is a cursor on a streaming read that no page in the product exposes, and
 * a UI proxy for it would be both weaker and slower.
 *
 * Assertions compare the whole stream, in order. `id < :last_received_id` is the
 * cursor predicate, so the stream descends by id and the expected page is exact
 * — a page that merely *contains* the right rows would hide a leak.
 */
test.describe('Traces stream cursor — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  // The fixture writes five traces and blocks until every one is queryable.
  test.slow();

  test(
    'a far-future cursor pages on to the ordinary traces instead of collapsing the stream',
    { tag: ['@cap:traces.list-traces'] },
    async ({ farFutureTraces, project, backendClient }) => {
      const { farFuture, present, allIdsNewestFirst } = farFutureTraces;
      const presentIds = present.map((p) => p.id);

      await test.step('The uncursored stream returns every seeded trace, far-future row first', async () => {
        const { status, message, ids } = await backendClient.searchTraceIds({
          projectId: project.id,
        });
        expect(status, `traces/search rejected with: ${message}`).toBe(200);
        // Order is asserted, not just membership: the far-future id is the
        // largest, so a descending stream must lead with it — and the next step
        // uses exactly that row as its cursor.
        expect(ids, 'the whole project streams back in descending id order').toEqual(
          allIdsNewestFirst,
        );
      });

      await test.step('Paging from the far-future row returns the remaining ordinary traces', async () => {
        const { status, message, ids } = await backendClient.searchTraceIds({
          projectId: project.id,
          lastRetrievedId: farFuture.id,
        });
        expect(status, `traces/search rejected with: ${message}`).toBe(200);
        // The regression, stated directly. Before the fix this was `[]`.
        expect(ids, 'the page after the far-future cursor').toEqual(presentIds);
        expect(ids.length, 'the page after a far-future cursor is not empty').toBeGreaterThan(0);
      });

      await test.step('An ordinary cursor still pages normally', async () => {
        // The control. Without it, "the far-future cursor returned four rows"
        // would be equally consistent with a stream that had started ignoring
        // its cursor altogether.
        const { status, ids } = await backendClient.searchTraceIds({
          projectId: project.id,
          lastRetrievedId: presentIds[0],
        });
        expect(status).toBe(200);
        expect(ids, 'the page after the newest ordinary trace').toEqual(presentIds.slice(1));
      });

      await test.step('The last cursor in the project returns an empty page', async () => {
        // The other end of the same control: an empty page is the correct
        // answer here, which is what makes it wrong in the step above.
        const { status, ids } = await backendClient.searchTraceIds({
          projectId: project.id,
          lastRetrievedId: presentIds[presentIds.length - 1],
        });
        expect(status).toBe(200);
        expect(ids, 'nothing follows the oldest seeded trace').toEqual([]);
      });
    },
  );
});
