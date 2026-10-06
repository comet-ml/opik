import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';

/**
 * `POST /v1/private/annotation-queues/{id}/items/search` is new in this release
 * and backs the items table's Source column. It is a lookup, not a listing: the
 * caller renders its own rows from the traces API and then asks the queue "which
 * of these are yours, and how did they get here" — so its whole contract is that
 * ids which are NOT in the queue come back omitted.
 *
 * That is exactly the kind of endpoint that silently starts returning
 * everything. A caller cannot tell: it asked about ids it is already displaying,
 * so every extra row it got back is a row it can render. The visible symptom
 * would be a Source column claiming queue membership for traces that have none —
 * which is why the assertions below compare the whole response rather than
 * looking up the ids this spec expects inside it.
 *
 * API-level: nothing about this is a rendering claim, and driving the items
 * table to observe it second-hand would assert the table's own filtering.
 */

/** Six traces, of which exactly three are added to the queue. */
const TRACE_COUNT = 6;
const MEMBER_COUNT = 3;

test.describe('Annotation queues — item membership lookup', {
  tag: ['@t2-cuj', '@area:annotation-queues'],
}, () => {
  test(
    'items/search returns membership and source for queue members only',
    { tag: ['@cap:annotation-queues.add-traces-to-queue'] },
    async ({
      project,
      sdkClient,
      backendClient,
      registerAnnotationQueueCleanup,
      testNamespace,
    }) => {
      const traceIds = await test.step(`Seed ${TRACE_COUNT} traces`, async () => {
        const ids: string[] = [];
        for (let i = 0; i < TRACE_COUNT; i += 1) {
          const created = await sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-trace-${i}`,
            input: `input ${i}`,
            output: `output ${i}`,
          });
          ids.push(created.id);
        }
        return ids;
      });

      const members = traceIds.slice(0, MEMBER_COUNT);
      const nonMembers = traceIds.slice(MEMBER_COUNT);

      const queueId = uuid7();
      const queueName = `${testNamespace}-queue`;
      await test.step(`Create a trace-scope queue holding ${MEMBER_COUNT} of them`, async () => {
        registerAnnotationQueueCleanup(queueId, queueName);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: queueId,
          projectId: project.id,
          name: queueName,
          scope: 'trace',
        });
        expect(status, `creating the queue answered: ${message}`).toBe(201);
        await backendClient.addAnnotationQueueItems(queueId, members);
      });

      await test.step('The queue really holds exactly those three', async () => {
        // Asserted before anything is searched, so a search over a queue that
        // silently failed to take its items cannot pass by returning nothing
        // and agreeing with an expectation of nothing.
        //
        // Polled rather than read once: queue items are stored in the analytics
        // database, whose write is acknowledged before the row is readable. That
        // makes this the settle barrier for the searches below as well — without
        // it their emptiness would be a race, not a contract.
        await expect
          .poll(async () => (await backendClient.getAnnotationQueue(queueId))?.itemsCount ?? null, {
            message: `the queue should report ${MEMBER_COUNT} items once the add has settled`,
            timeout: 30_000,
          })
          .toBe(MEMBER_COUNT);
      });

      await test.step('A search mixing members and a non-member returns only the members', async () => {
        const asked = [members[0], members[1], nonMembers[0]];
        const { status, message, items } = await backendClient.searchAnnotationQueueItems(
          queueId,
          asked,
        );
        expect(status, `the search answered: ${message}`).toBe(200);

        // The whole answer, as a set: the order is the server's own and is not
        // part of the contract, but the membership is — and a response that
        // also carried the non-member would satisfy any assertion that merely
        // looked its own two ids up.
        //
        // Id and source compared as one row rather than as two parallel arrays,
        // so each source stays tied to the item it describes. Every row carries
        // how the item got in: `manual` is what an explicit add writes,
        // `automated` is what queue automation would write, and the column
        // exists to tell the two apart — which a positional check over a
        // server-ordered list cannot actually establish.
        const byId = (a: { id: string }, b: { id: string }) => a.id.localeCompare(b.id);
        expect(items.map((item) => ({ id: item.id, source: item.source })).sort(byId)).toEqual(
          [members[0], members[1]].map((id) => ({ id, source: 'manual' })).sort(byId),
        );
      });

      await test.step('A search for an id in no queue at all answers 200 with nothing', async () => {
        // A freshly minted id, not one of the seeded non-members: this is the
        // shape a caller sends when its page is showing traces from before the
        // queue existed, and answering 404 (or faulting) would break the column
        // rather than leave it blank.
        const { status, message, items } = await backendClient.searchAnnotationQueueItems(
          queueId,
          [uuid7()],
        );
        expect(status, `the unknown-id search answered: ${message}`).toBe(200);
        expect(items).toEqual([]);
      });
    },
  );
});
