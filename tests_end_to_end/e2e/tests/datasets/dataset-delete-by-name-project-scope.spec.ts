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

    /**
     * opik#9704 (OPIK-9319), and the reason this is a second test rather than
     * more steps on the one above: it is about what the delete does when the
     * scoped lookup finds NOTHING, which the test above never reaches.
     *
     * Before the fix, `deleteDatasetByName` fell back to a workspace-wide
     * unique-name lookup whenever the named project held no such dataset. Both
     * steps below reach that fallback, and under it both DESTROYED ANOTHER
     * PROJECT'S DATASET — silently, answering 204:
     *
     *   1. Deleting a name in project A twice. After the first call project A
     *      holds nothing by that name, so the second fell through to the
     *      workspace, where project B's copy was now the only remaining match.
     *   2. Deleting the name scoped to a project that never held it at all.
     *
     * The fix scopes the lookup and keeps only a PROJECT-LESS fallback
     * (`project_id IS NULL`), which neither copy here is. So both calls must now
     * answer 404 with nothing deleted. The second is not a restatement of the
     * first: one arrives at the fallback by emptying the named project, the
     * other by naming a project that was always empty, and a fix that only
     * re-scoped the delete's own `WHERE` clause without re-scoping the lookup
     * would pass the first and fail the second.
     *
     * Project B's copy is the bystander for both, asserted by id and in the UI,
     * together with its differently-named neighbour — so "nothing else was
     * deleted" cannot be satisfied by a delete that took project B wholesale.
     */
    test(
      'A repeated delete, and one scoped to a project that never held the name, both 404 without touching another project',
      { tag: ['@cap:datasets.delete-by-name-project-scope'] },
      async ({ sameNameDatasets, backendClient, page }) => {
        const { datasetName, a, b, bystanderInB, emptyProject } = sameNameDatasets;
        const datasets = new DatasetsPage(page);

        const bInputsBefore = await test.step(
          "Project B's copy of the name holds its own items before anything is deleted",
          async () => {
            const items = await backendClient.getDatasetItems(b.datasetId);
            expect(
              items.map((item) => String(item.data.input)).sort(),
              "project B's items before the delete",
            ).toEqual([...b.inputs].sort());
            return items;
          },
        );

        await test.step(`The first delete of "${datasetName}" in ${a.project.name} is accepted`, async () => {
          const deleted = await backendClient.deleteDatasetByName({
            datasetName,
            projectName: a.project.name,
          });
          expect(deleted.status, `the scoped delete was accepted: ${deleted.message}`).toBe(204);
          expect(
            (await backendClient.datasetStatus(a.datasetId)).status,
            "project A's copy is gone, so project A now holds the name nowhere",
          ).toBe(404);
        });

        await test.step('The identical delete, repeated, is refused rather than falling back to the workspace', async () => {
          const repeated = await backendClient.deleteDatasetByName({
            datasetName,
            projectName: a.project.name,
          });
          // The whole fix. A 204 here is the regression: it would mean the
          // lookup left project A and found project B's copy instead.
          expect(
            repeated.status,
            `the repeated delete names a project that no longer holds "${datasetName}", ` +
              `so it must 404 rather than resolve elsewhere: ${repeated.message}`,
          ).toBe(404);
          expect(
            (await backendClient.datasetStatus(b.datasetId)).status,
            "project B's identically-named copy survives the repeated delete",
          ).toBe(200);
        });

        await test.step(`A delete scoped to ${emptyProject.name}, which never held the name, is also refused`, async () => {
          const wrongProject = await backendClient.deleteDatasetByName({
            datasetName,
            projectName: emptyProject.name,
          });
          expect(
            wrongProject.status,
            `"${datasetName}" has never existed in ${emptyProject.name}: ${wrongProject.message}`,
          ).toBe(404);
          expect(
            (await backendClient.datasetStatus(b.datasetId)).status,
            "and project B's copy survives that one too",
          ).toBe(200);
          const emptyList = await backendClient.listDatasetSummaries({
            projectId: emptyProject.id,
          });
          expect(
            emptyList.total,
            'the refused delete created nothing in the project it named either',
          ).toBe(0);
        });

        await test.step("Project B's copy still holds exactly the items it was seeded with", async () => {
          const items = await backendClient.getDatasetItems(b.datasetId);
          expect(
            items.map((item) => item.id).sort(),
            "project B's item ids are the ones it held before either refused delete",
          ).toEqual(bInputsBefore.map((item) => item.id).sort());
          expect(
            items.map((item) => String(item.data.input)).sort(),
            'and their content is unchanged',
          ).toEqual([...b.inputs].sort());
        });

        await test.step('Each project lists exactly what the two refusals should have left', async () => {
          const listA = await backendClient.listDatasetSummaries({ projectId: a.project.id });
          expect(listA.total, 'project A holds nothing: its own copy was deleted').toBe(0);

          const listB = await backendClient.listDatasetSummaries({ projectId: b.project.id });
          expect(listB.total, 'project B holds both of its datasets').toBe(2);
          expect(listB.rows.map((row) => row.id).sort()).toEqual(
            [b.datasetId, bystanderInB.datasetId].sort(),
          );
        });

        await test.step(`Project ${b.project.name}'s Datasets page still lists the row`, async () => {
          await datasets.goto(b.project.id);
          await datasets.waitForReady();
          const row = datasets.datasetRow(datasetName);
          await expect(row, 'the row is still listed, exactly once').toHaveCount(1);
          await expect(row, "and it is still project B's own dataset").toHaveAttribute(
            'data-row-id',
            b.datasetId,
          );
          await expect(
            datasets.datasetRow(bystanderInB.name),
            'as is the bystander beside it, so the delete took nothing in project B',
          ).toHaveCount(1);
        });

        await test.step(`Project ${a.project.name}'s Datasets page lists nothing under that name`, async () => {
          await datasets.goto(a.project.id);
          await datasets.waitForReady();
          // The empty state first, so the absence below is a rendered absence
          // rather than a table that had not finished loading — project A holds
          // no datasets at all once its own copy is gone.
          await expect(
            page.getByText('No datasets yet'),
            "project A's Datasets page has rendered, and rendered empty",
          ).toBeVisible();
          await expect(
            datasets.datasetRow(datasetName),
            'project A really did lose its own copy to the first, accepted delete',
          ).toHaveCount(0);
        });
      },
    );
  },
);
