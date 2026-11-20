import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * `POST /v1/private/datasets/delete` takes a NAME, optionally scoped to a
 * project. Since opik#9659 a name can live in two projects at once, which makes
 * the scope of that delete the highest-consequence line in the diff: the DAO's
 *
 *   DELETE FROM datasets WHERE workspace_id = :workspace_id AND name = :name
 *
 * was replaced with a delete of the one resolved dataset's id. Under the old
 * statement, deleting "eval-set" in one project would have taken the "eval-set"
 * of every other project in the workspace — silently, with a 204, and visible
 * only to whoever next opened one of those other projects.
 *
 * So the assertion is not "the dataset is gone": it is that exactly one of two
 * identically-named datasets is gone, and the other still holds its own items,
 * by id. Project B also holds a differently-named bystander dataset the test
 * never addresses, because "B's copy disappeared" would read the same if the
 * delete had taken everything in project B.
 *
 * The last step is the other half of the same change: with the name no longer
 * ambiguous, a name-only address resolves again rather than being refused —
 * which is what makes the refusal while ambiguous a scoping rule and not a
 * permanent loss of the endpoint.
 */
test.describe(
  'Datasets — delete by name is scoped to one project',
  { tag: ['@t2-cuj', '@area:datasets'] },
  () => {
    test(
      "Deleting the name in project B leaves project A's identically-named dataset whole",
      { tag: ['@cap:datasets.delete-by-name-project-scope'] },
      async ({ sameNameDatasets, backendClient, page }) => {
        const { datasetName, a, b, bystanderInB } = sameNameDatasets;
        const datasets = new DatasetsPage(page);

        const itemsBefore = await test.step(
          'Both copies resolve by id, each holding its own items',
          async () => {
            for (const copy of [a, b, bystanderInB]) {
              const status = await backendClient.datasetStatus(copy.datasetId);
              expect(
                status.status,
                `${copy.name} in ${copy.project.name} exists before the delete`,
              ).toBe(200);
            }
            const items = await backendClient.getDatasetItems(a.datasetId);
            // Captured by id as well as by content: the after state is compared
            // against these exact rows, so a delete that took A's items and
            // re-created them would not pass as "untouched".
            expect(
              items.map((item) => String(item.data.input)).sort(),
              "project A's items before the delete",
            ).toEqual([...a.inputs].sort());
            return items;
          },
        );

        await test.step(`Delete "${datasetName}" scoped to project ${b.project.name}`, async () => {
          const deleted = await backendClient.deleteDatasetByName({
            datasetName,
            projectName: b.project.name,
          });
          expect(
            deleted.status,
            `the scoped delete was accepted: ${deleted.message}`,
          ).toBe(204);
        });

        await test.step("Project B's copy is gone and project A's is not", async () => {
          expect(
            (await backendClient.datasetStatus(b.datasetId)).status,
            "project B's copy is deleted",
          ).toBe(404);
          expect(
            (await backendClient.datasetStatus(a.datasetId)).status,
            "project A's identically-named copy survives",
          ).toBe(200);
          expect(
            (await backendClient.datasetStatus(bystanderInB.datasetId)).status,
            'the bystander dataset in project B survives, so the delete took one row and not a project',
          ).toBe(200);
        });

        await test.step("Project A's dataset still holds exactly its own items", async () => {
          const items = await backendClient.getDatasetItems(a.datasetId);
          expect(
            items.map((item) => item.id).sort(),
            "project A's item ids are the ones it held before the delete",
          ).toEqual(itemsBefore.map((item) => item.id).sort());
          expect(
            items.map((item) => String(item.data.input)).sort(),
            "and their content is unchanged — none of project B's items arrived here either",
          ).toEqual([...a.inputs].sort());
        });

        await test.step('Each project lists exactly what it should now hold', async () => {
          const listA = await backendClient.listDatasetSummaries({ projectId: a.project.id });
          expect(listA.total, 'project A still holds its one dataset').toBe(1);
          expect(listA.rows.map((row) => row.id)).toEqual([a.datasetId]);

          const listB = await backendClient.listDatasetSummaries({ projectId: b.project.id });
          expect(listB.total, 'project B is down to the bystander alone').toBe(1);
          expect(listB.rows.map((row) => row.id)).toEqual([bystanderInB.datasetId]);
          expect(
            listB.rows.map((row) => row.name),
            'and no row in project B carries the deleted name any more',
          ).toEqual([bystanderInB.name]);
        });

        await test.step(`Project ${a.project.name}'s Datasets page still lists the row`, async () => {
          await datasets.goto(a.project.id);
          await datasets.waitForReady();
          const row = datasets.datasetRow(datasetName);
          await expect(row, 'the row is still listed, exactly once').toHaveCount(1);
          await expect(
            row,
            "and it is still project A's own dataset",
          ).toHaveAttribute('data-row-id', a.datasetId);
        });

        await test.step(`Project ${b.project.name}'s Datasets page no longer lists it`, async () => {
          await datasets.goto(b.project.id);
          await datasets.waitForReady();
          // The bystander first: it is what proves the table has rendered its
          // rows, so the absence asserted next is a real absence rather than a
          // page that had not finished loading.
          await expect(
            datasets.datasetRow(bystanderInB.name),
            'the bystander row is rendered',
          ).toHaveCount(1);
          await expect(
            datasets.datasetRow(datasetName),
            'the deleted name is no longer listed in project B',
          ).toHaveCount(0);
        });

        await test.step('The name is unambiguous again, so a name-only address resolves', async () => {
          const resolved = await backendClient.retrieveDatasetByName({ datasetName });
          expect(
            resolved.status,
            `a name-only retrieve is no longer refused: ${resolved.message}`,
          ).toBe(200);
          expect(resolved.id, 'and it resolves to the surviving dataset').toBe(a.datasetId);
          expect(resolved.projectId, "which is project A's").toBe(a.project.id);
          expect(
            resolved.workspaceFallback,
            'a name-only address names no project, so nothing fell back to the workspace',
          ).toBeNull();
        });
      },
    );
  },
);
