import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';

/**
 * Three endpoints address a dataset by NAME with no project of their own —
 * `POST /datasets/retrieve`, `POST /datasets/delete` and
 * `PUT /datasets/items` — and all three resolve it through the same
 * `DatasetService.findSingleByName`. Since opik#9659 a name can belong to two
 * projects, so that lookup can match more than one dataset, and the PR's choice
 * is that it then refuses with 409 rather than picking one.
 *
 * "Rather than picking one" is why this is worth pinning. The intermediate
 * commit in the same PR made the workspace-wide lookup resolve to the oldest
 * match deterministically, and the final one replaced that with the refusal.
 * Drifting back is a one-line change that no existing test would notice, and
 * what it costs is not an error message: it is `PUT /datasets/items` writing a
 * user's rows into another project's dataset, and `POST /datasets/delete`
 * deleting it.
 *
 * So each refusal is asserted together with the thing that must NOT have
 * happened — both datasets still resolving by id with their own items, and the
 * project that was addressed still holding no dataset at all. A spec that only
 * checked the status would pass against a server that answered 409 after
 * writing.
 *
 * API-level throughout, deliberately. The ambiguity branch is reachable from
 * exactly one UI surface (the by-name redirect page, whose handling of the new
 * 409 the exploration of this PR filed as suspect — asserting what it does today
 * would be asserting that), and these are the SDK's own addresses: an
 * `opik.Opik` client resolves a dataset by name on every `get_dataset`,
 * `Dataset.insert` and `evaluate` call.
 *
 * The second test is the control, and it carries the whole weight of the
 * distinction being tested: with the name in only ONE project, the same lookup
 * still reaches across projects and resolves — announcing it with the
 * `X-Opik-Deprecation` header — so the refusals above are about ambiguity and
 * not about the endpoint having been narrowed to project-scoped addresses.
 */

/** `DatasetService.findSingleByName`'s own conflict message, verbatim. */
const ambiguousMessage = (name: string): string =>
  'Multiple datasets with this name exist in different projects, specify a project that ' +
  `contains it or use the dataset id. Dataset name: '${name}'`;

/** `RequestContext.WORKSPACE_FALLBACK_MESSAGE_TEMPLATE`, as the header carries it. */
const workspaceFallbackMessage = (name: string): string =>
  `Dataset '${name}' was found via workspace-wide search. In a future version, you will ` +
  'need to specify the project explicitly.';

test.describe(
  'Datasets — addressing an ambiguous name',
  { tag: ['@t3-nightly', '@area:datasets'] },
  () => {
    test(
      'Retrieve, delete and items-batch all refuse a name held by two projects, and mutate nothing',
      { tag: ['@cap:datasets.name-only-address-ambiguity'] },
      async ({ sameNameDatasets, backendClient }) => {
        const { datasetName, a, b, emptyProject } = sameNameDatasets;

        /**
         * Both copies still resolve by id, holding exactly their own items.
         *
         * Run after every refusal rather than once at the end: a delete that
         * went through before answering 409 and a batch that wrote into the
         * wrong dataset are different failures, and checking only at the end
         * would report whichever came first for all three.
         */
        const expectBothUntouched = async (after: string): Promise<void> => {
          for (const copy of [a, b]) {
            expect(
              (await backendClient.datasetStatus(copy.datasetId)).status,
              `${copy.project.name}'s copy still exists after ${after}`,
            ).toBe(200);
            const items = await backendClient.getDatasetItems(copy.datasetId);
            expect(
              items.map((item) => String(item.data.input)).sort(),
              `${copy.project.name}'s items are unchanged after ${after}`,
            ).toEqual([...copy.inputs].sort());
          }
        };

        await test.step("A name-only retrieve is refused with the server's own message", async () => {
          const retrieved = await backendClient.retrieveDatasetByName({ datasetName });
          expect(retrieved.status, 'retrieve by name alone').toBe(409);
          expect(
            retrieved.message,
            'the refusal says what the caller has to do about it',
          ).toBe(ambiguousMessage(datasetName));
          // Asserted explicitly rather than left implicit in the status: a
          // response that carried one of the two datasets alongside a 409 would
          // be read by a client as a resolution.
          expect(retrieved.id, 'no dataset was resolved').toBeNull();
          await expectBothUntouched('the refused retrieve');
        });

        await test.step('A name-only delete is refused, and deletes neither dataset', async () => {
          const deleted = await backendClient.deleteDatasetByName({ datasetName });
          expect(deleted.status, 'delete by name alone').toBe(409);
          expect(deleted.message).toBe(ambiguousMessage(datasetName));
          // The half that matters: under the DELETE-by-name this PR removed,
          // this call would have answered 204 having taken both.
          await expectBothUntouched('the refused delete');
        });

        await test.step('An items batch addressed through a third project is refused, and creates nothing in it', async () => {
          const written = await backendClient.writeDatasetItemsByName({
            datasetName,
            // A project that holds no dataset of this name, so the lookup falls
            // back to the workspace — where the name is ambiguous. This is the
            // only one of the three endpoints that would otherwise CREATE the
            // dataset it cannot find.
            projectName: emptyProject.name,
            items: [{ id: uuid7(), data: { input: 'must-not-be-written' } }],
          });
          expect(written.status, 'items batch by name + a project without it').toBe(409);
          expect(written.message).toBe(ambiguousMessage(datasetName));

          const list = await backendClient.listDatasetSummaries({ projectId: emptyProject.id });
          expect(
            list.total,
            `project ${emptyProject.name} still holds no dataset — the refused batch did not get-or-create one`,
          ).toBe(0);
          expect(list.rows, 'and so lists no rows').toEqual([]);
          await expectBothUntouched('the refused items batch');
        });
      },
    );

    test(
      'A name held by one project only still resolves from another project, and announces the fallback',
      { tag: ['@cap:datasets.name-only-address-ambiguity'] },
      async ({ sameNameDatasets, backendClient }) => {
        const { a, b, bystanderInB, emptyProject, solo } = sameNameDatasets;

        await test.step(`Retrieving "${solo.name}" through a project that does not hold it resolves it, with the deprecation header`, async () => {
          const retrieved = await backendClient.retrieveDatasetByName({
            datasetName: solo.name,
            projectName: emptyProject.name,
          });
          expect(
            retrieved.status,
            `the single-match workspace fallback still resolves: ${retrieved.message}`,
          ).toBe(200);
          expect(retrieved.id, 'and resolves to the one dataset of that name').toBe(
            solo.datasetId,
          );
          expect(
            retrieved.projectId,
            'which lives in its own project, not the one addressed',
          ).toBe(solo.project.id);
          expect(
            retrieved.workspaceFallback,
            'and the response says the project it was asked for did not hold it',
          ).toBe(workspaceFallbackMessage(solo.name));
        });

        await test.step('Retrieving it through its own project resolves without any fallback notice', async () => {
          // The discriminator for the header above: without this, a server that
          // set X-Opik-Deprecation on every by-name retrieve would pass.
          const retrieved = await backendClient.retrieveDatasetByName({
            datasetName: solo.name,
            projectName: solo.project.name,
          });
          expect(retrieved.status, `${retrieved.message}`).toBe(200);
          expect(retrieved.id).toBe(solo.datasetId);
          expect(
            retrieved.workspaceFallback,
            'nothing fell back, so nothing is deprecated about this call',
          ).toBeNull();
        });

        await test.step('An items batch through the same wrong project writes into the dataset it resolved', async () => {
          const itemId = uuid7();
          const written = await backendClient.writeDatasetItemsByName({
            datasetName: solo.name,
            projectName: emptyProject.name,
            items: [{ id: itemId, data: { input: 'written-through-the-fallback' } }],
          });
          expect(written.status, `the batch was accepted: ${written.message}`).toBe(204);

          const items = await backendClient.getDatasetItems(solo.datasetId);
          expect(
            items.map((item) => item.id),
            'the row landed in the dataset the name resolved to',
          ).toContain(itemId);
          expect(
            items.map((item) => String(item.data.input)).sort(),
            'which now holds its seeded item and this one, and nothing else',
          ).toEqual([...solo.inputs, 'written-through-the-fallback'].sort());

          const list = await backendClient.listDatasetSummaries({ projectId: emptyProject.id });
          expect(
            list.total,
            `project ${emptyProject.name} still holds no dataset of its own`,
          ).toBe(0);
        });

        await test.step('A name-only delete removes exactly that dataset', async () => {
          const deleted = await backendClient.deleteDatasetByName({ datasetName: solo.name });
          expect(deleted.status, `the unambiguous delete was accepted: ${deleted.message}`).toBe(
            204,
          );
          expect(
            (await backendClient.datasetStatus(solo.datasetId)).status,
            'the dataset it resolved to is gone',
          ).toBe(404);

          // The bystanders for a delete that resolved by name across projects:
          // the two sharing the other name, and the one in project B that
          // shares no name with anything.
          for (const copy of [a, b, bystanderInB]) {
            expect(
              (await backendClient.datasetStatus(copy.datasetId)).status,
              `${copy.name} in ${copy.project.name} is untouched`,
            ).toBe(200);
          }
        });
      },
    );
  },
);
