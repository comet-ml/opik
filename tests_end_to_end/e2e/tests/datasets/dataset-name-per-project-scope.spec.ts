import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { SameNameProjectRef } from '@e2e/fixtures';
import type { BackendDatasetRef } from '@e2e/core/backend';

/**
 * A dataset name is unique per PROJECT, not per workspace (opik#9659, migration
 * 000113). So two projects may each hold a dataset called the same thing, and
 * every address that carries a project has to resolve to that project's own.
 *
 * Nothing in the estate could vary this before: every dataset spec names its
 * datasets inside the run prefix, so two of them can never collide, and the
 * Create-dataset sidebar was never driven twice with one name. A regression to
 * workspace-wide uniqueness is also quiet from the API's point of view — the
 * second create simply answers 409, which the sidebar renders as an inline
 * "this name is taken" under the Name field. A user is told their colleague's
 * dataset in another project is their problem, and no error reaches a log.
 *
 * Driven through the UI because that is where the relaxed constraint actually
 * lands: the create sidebar posts `project_id: activeProjectId`
 * (`useDatasetForm.ts`), and the PR has no frontend diff at all, so the Datasets
 * page is the surface that changed behaviour without changing.
 *
 * The two tests are the two halves of one claim, and they fail for opposite
 * reasons — the first that the second project's create is ACCEPTED, the second
 * that a repeat inside one project is still REFUSED. Without the second, a
 * regression that dropped the unique key altogether would pass the first.
 */

/** `useDatasetForm`'s own 409 message, rendered under the Name field. */
const NAME_CONFLICT_MESSAGE = 'This name already exists';

interface ProjectCopy {
  project: SameNameProjectRef;
  own: BackendDatasetRef;
  other: BackendDatasetRef;
}

test.describe(
  'Datasets — the same name in two projects',
  { tag: ['@t2-cuj', '@area:datasets'] },
  () => {
    test(
      'The same name created from the sidebar in two projects lists and opens as two datasets',
      { tag: ['@cap:datasets.per-project-name-scope'] },
      async ({ sameNameProjects, backendClient, registerDatasetCleanup, page }) => {
        const { datasetName, projectA, projectB } = sameNameProjects;
        const datasets = new DatasetsPage(page);

        const descriptionOf = (project: SameNameProjectRef): string =>
          `created in ${project.name}`;

        /**
         * Create `datasetName` in `project` through the sidebar and resolve what
         * it made.
         *
         * The read-back is project-SCOPED (`project_name`), which is the only
         * address that can answer this question once both projects hold the
         * name: a name-only read is ambiguous by then and is refused outright.
         */
        const createThroughSidebar = async (
          project: SameNameProjectRef,
        ): Promise<BackendDatasetRef> =>
          test.step(`Create "${datasetName}" in project ${project.name}`, async () => {
            await datasets.goto(project.id);
            await datasets.waitForReady();
            await datasets.clickCreateDataset();
            await datasets.fillCreateDialog({
              name: datasetName,
              description: descriptionOf(project),
            });
            // Waits for the sidebar to slide away, which a refused create never
            // does — so this step fails here, at the create, rather than later
            // on a missing row.
            await datasets.submitCreateDialog();
            await expect(
              datasets.datasetRow(datasetName),
              `the create was accepted and ${project.name} lists the dataset`,
            ).toHaveCount(1);

            const created = await backendClient.findDatasetByName(datasetName, project.name);
            expect(created, `"${datasetName}" resolves inside project ${project.name}`).not.toBeNull();
            registerDatasetCleanup(created!.id, `${datasetName} in ${project.name}`);
            return created!;
          });

        const inA = await createThroughSidebar(projectA);
        const inB = await createThroughSidebar(projectB);

        await test.step('The two creates made two different datasets, each with its own description', async () => {
          expect(
            inB.id,
            "project B's dataset is a new dataset, not project A's resolved again",
          ).not.toBe(inA.id);
          // The description is what distinguishes the two rows on screen below,
          // and it is also the cheapest proof that each read resolved the
          // dataset its own project created rather than the other one.
          expect(inA.description, "project A's description").toBe(descriptionOf(projectA));
          expect(inB.description, "project B's description").toBe(descriptionOf(projectB));
        });

        const copies: ProjectCopy[] = [
          { project: projectA, own: inA, other: inB },
          { project: projectB, own: inB, other: inA },
        ];

        await test.step('Each project lists exactly its own copy and nothing else', async () => {
          for (const { project, own } of copies) {
            const { total, rows } = await backendClient.listDatasetSummaries({
              projectId: project.id,
            });
            // The whole answer, not "mine is in it": a list that leaked the
            // other project's same-named dataset is precisely the failure this
            // test exists for, and it would render as two identical rows.
            expect(total, `project ${project.name} holds exactly one dataset`).toBe(1);
            expect(
              rows.map((row) => row.id),
              `the dataset project ${project.name} holds`,
            ).toEqual([own.id]);
            expect(
              rows.map((row) => row.name),
              `the name project ${project.name} holds it under`,
            ).toEqual([datasetName]);
          }
        });

        for (const { project, own, other } of copies) {
          await test.step(`Opening the row in project ${project.name} opens that project's dataset`, async () => {
            await datasets.goto(project.id);
            await datasets.waitForReady();

            const row = datasets.datasetRow(datasetName);
            await expect(row, `the name is listed once in ${project.name}`).toHaveCount(1);
            // The row's identity before the click: `data-row-id` is the dataset
            // id, so this is the page claiming which dataset the row stands for.
            await expect(
              row,
              `the row in ${project.name} is that project's own dataset`,
            ).toHaveAttribute('data-row-id', own.id);

            const items = await datasets.openDatasetByName(datasetName);
            await items.waitForReady();
            expect(
              page.url(),
              `${project.name}'s row opened that project's dataset`,
            ).toContain(`/datasets/${own.id}/items`);
            expect(
              page.url(),
              'and not the identically-named dataset in the other project',
            ).not.toContain(other.id);
          });
        }
      },
    );

    test(
      'A repeat of the name inside one project is still refused, and nothing is created or changed',
      { tag: ['@cap:datasets.per-project-name-scope'] },
      async ({ sameNameDatasets, backendClient, page }) => {
        const { datasetName, a, b, bystanderInB } = sameNameDatasets;
        const datasets = new DatasetsPage(page);

        await test.step("Project A already holds the name, once", async () => {
          await datasets.goto(a.project.id);
          await datasets.waitForReady();
          await expect(datasets.datasetRow(datasetName)).toHaveCount(1);
        });

        await test.step('The sidebar refuses the duplicate inline and stays open', async () => {
          await datasets.clickCreateDataset();
          await datasets.fillCreateDialog({
            name: datasetName,
            description: 'the duplicate that must be refused',
          });
          await datasets.clickCreateDialogSubmit();

          await expect(
            datasets.createDialogNameError(NAME_CONFLICT_MESSAGE),
            'the 409 is surfaced under the Name field rather than swallowed',
          ).toBeVisible();
          // Load-bearing beside the message: the panel is never unmounted, so a
          // refusal that closed it anyway would still show a visible error for
          // an instant while discarding what the user typed.
          await datasets.expectCreateDialogOpen();
        });

        await test.step('Project A still holds exactly the one dataset it did, with its own items', async () => {
          const { total, rows } = await backendClient.listDatasetSummaries({
            projectId: a.project.id,
          });
          expect(total, 'no second dataset was created in project A').toBe(1);
          expect(rows.map((row) => row.id)).toEqual([a.datasetId]);

          const items = await backendClient.getDatasetItems(a.datasetId);
          expect(
            items.map((item) => String(item.data.input)).sort(),
            "project A's items are untouched by the refused create",
          ).toEqual([...a.inputs].sort());
        });

        await test.step('Project B, which holds the same name, is untouched', async () => {
          // The refusal has to be scoped to project A. A unique key that had
          // regressed to the workspace could equally have answered 409 here
          // while something in project B had been resolved, updated or removed.
          const { total, rows } = await backendClient.listDatasetSummaries({
            projectId: b.project.id,
          });
          expect(total, 'project B still holds its own two datasets').toBe(2);
          expect(rows.map((row) => row.id).sort()).toEqual(
            [b.datasetId, bystanderInB.datasetId].sort(),
          );

          const items = await backendClient.getDatasetItems(b.datasetId);
          expect(
            items.map((item) => String(item.data.input)).sort(),
            "project B's copy still holds its own items",
          ).toEqual([...b.inputs].sort());
        });
      },
    );
  },
);
