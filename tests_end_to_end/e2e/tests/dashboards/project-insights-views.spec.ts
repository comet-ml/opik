import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import {
  DEFAULT_PROJECT_VIEW_ID,
  DEFAULT_PROJECT_VIEW_NAME,
  ProjectDashboardsPage,
} from '@e2e/pom/project-dashboards.page';

/**
 * A project's dashboard views belong to that project — OPIK-8322 / #8240.
 *
 * Before that change the project view selector listed every insights view in
 * the workspace, so a project's Dashboards tab offered views built for other
 * projects. Nothing errors when that regresses: the selector simply lists rows
 * it should not, and a shared deep link renders another project's dashboard
 * under this project's data. Both assertions below are therefore about what is
 * *absent*, which is why the fixture seeds a view in a second project as a
 * decoy — a spec seeding one project and one view would pass just as happily
 * against a selector that ignored `project_id` altogether.
 *
 * A view carrying no `project_id` is the other half of the contract. Views
 * created before the change have none, and scoping that is too strict would
 * drop them out of the product entirely, so the legacy view must stay visible
 * from every project.
 *
 * SCOPE — the read path only: which views a project offers, and which view a
 * `dashboardId` link resolves to. Creating, renaming, duplicating and deleting
 * a view through the selector's own dialogs are untouched here, and so is
 * anything the widgets themselves render.
 */
test.describe(
  'Project dashboard views are scoped to their project',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    test(
      "a project's selector lists its own views and the project-less ones, and no other project's",
      { tag: ['@cap:dashboards.list-dashboards'] },
      async ({ projectInsightsViews, backendClient, page }) => {
        const { projectA, projectB, viewA, viewB, legacyView } = projectInsightsViews;

        const dashboardsA = new ProjectDashboardsPage(page, projectA.id);

        await test.step("Project A's list request is scoped to project A", async () => {
          // Registered before the navigation: the page issues this once, on
          // mount. Matched on the pathname's tail rather than the whole URL
          // because the app is served under /api on some deployments and
          // /opik/api on others — but matched on the *collection* pathname
          // exactly, so the read-one `/insights-views/{id}` a deep link
          // triggers cannot answer this wait with a null `project_id`. The
          // collection endpoint carries a trailing slash, hence the strip.
          const listRequest = page.waitForRequest((request) =>
            new URL(request.url()).pathname.replace(/\/$/, '').endsWith('/v1/private/insights-views'),
          );
          await dashboardsA.goto();
          await dashboardsA.waitForReady();

          const requested = new URL((await listRequest).url());
          expect(
            requested.searchParams.get('project_id'),
            'the selector asks for one project, not the whole workspace',
          ).toBe(projectA.id);
        });

        await test.step("Project A's selector offers A's view and the legacy one, and not B's", async () => {
          await dashboardsA.expectSelectedView(DEFAULT_PROJECT_VIEW_NAME);
          await dashboardsA.openViewSelector(DEFAULT_PROJECT_VIEW_NAME);

          await expect(dashboardsA.viewOption(viewA.name), "A's own view").toHaveCount(1);
          await expect(
            dashboardsA.viewOption(legacyView.name),
            'the project-less view, which every project keeps',
          ).toHaveCount(1);
          // The leak this whole spec exists for. It is silent — a listed row
          // looks exactly like a row that belongs here.
          await expect(
            dashboardsA.viewOption(viewB.name),
            "another project's view must not be offered",
          ).toHaveCount(0);
        });

        const dashboardsB = new ProjectDashboardsPage(page, projectB.id);

        await test.step("Project B's selector offers B's view and the legacy one, and not A's", async () => {
          await dashboardsB.goto();
          await dashboardsB.waitForReady();
          await dashboardsB.expectSelectedView(DEFAULT_PROJECT_VIEW_NAME);
          await dashboardsB.openViewSelector(DEFAULT_PROJECT_VIEW_NAME);

          await expect(dashboardsB.viewOption(viewB.name), "B's own view").toHaveCount(1);
          await expect(
            dashboardsB.viewOption(legacyView.name),
            'the project-less view is visible from both projects',
          ).toHaveCount(1);
          await expect(
            dashboardsB.viewOption(viewA.name),
            "project A's view must not be offered here",
          ).toHaveCount(0);
        });

        await test.step('The API answers the same way, over the whole page and not just our rows', async () => {
          // The UI assertions above name the rows this run seeded. This one is
          // exhaustive: every view the scoped read returns must belong to the
          // project asked for or to no project at all. A workspace shared with
          // other runs would defeat a count comparison, but it cannot defeat
          // this — one foreign, project-bound row is a failure however many
          // rows there are.
          for (const project of [projectA, projectB]) {
            const scoped = await backendClient.findInsightsViews({ projectId: project.id });
            const foreign = scoped.filter(
              (view) => view.projectId !== null && view.projectId !== project.id,
            );
            expect(
              foreign.map((view) => `${view.name} (${view.projectId})`),
              `views ${project.name} was offered that belong to another project`,
            ).toEqual([]);

            const ids = scoped.map((view) => view.id);
            expect(ids, `${project.name} is offered the project-less view`).toContain(
              legacyView.id,
            );
          }

          const scopedToA = (await backendClient.findInsightsViews({ projectId: projectA.id })).map(
            (view) => view.id,
          );
          expect(scopedToA, "A is offered A's view").toContain(viewA.id);
          expect(scopedToA, "A is not offered B's view").not.toContain(viewB.id);
        });
      },
    );

    test(
      "a deep link to another project's view falls back to the default template",
      { tag: ['@cap:dashboards.open-dashboard'] },
      async ({ projectInsightsViews, page }) => {
        const { projectA, projectB, viewA, legacyView } = projectInsightsViews;
        const dashboardsB = new ProjectDashboardsPage(page, projectB.id);

        await test.step("A link to A's view, opened under B, renders the default template instead", async () => {
          await dashboardsB.goto({ dashboardId: viewA.id });
          await dashboardsB.waitForReady();

          // Reading a view by id is not project scoped, so the fallback is the
          // only thing standing between a shared link and another project's
          // dashboard rendering over B's data.
          await expect
            .poll(
              () => dashboardsB.selectedDashboardId(),
              { message: 'the query param is rewritten to the default template' },
            )
            .toBe(DEFAULT_PROJECT_VIEW_ID);
          await dashboardsB.expectSelectedView(DEFAULT_PROJECT_VIEW_NAME);
          // Not a race the poll above could have won: `viewA` must never be
          // what this page settles on.
          await expect(
            dashboardsB.viewSelector(viewA.name),
            "project A's view is not rendered under project B",
          ).toHaveCount(0);
        });

        await test.step('A link to a project-less view is honoured rather than replaced', async () => {
          await dashboardsB.goto({ dashboardId: legacyView.id });
          await dashboardsB.waitForReady();

          // The selector settling on the legacy view proves the page resolved
          // the id and ran the fallback effect — which is what makes the
          // unchanged query param below evidence rather than a snapshot taken
          // too early.
          await dashboardsB.expectSelectedView(legacyView.name);
          expect(
            dashboardsB.selectedDashboardId(),
            'a view belonging to no project stays open where it was linked',
          ).toBe(legacyView.id);
        });

        await test.step('A link to an id that does not exist falls back too', async () => {
          // Any well-formed id the workspace does not hold. Minted rather than
          // hard-coded so it cannot collide with a seeded view; nothing about
          // the assertion depends on its value.
          const missingId = uuid7();
          await dashboardsB.goto({ dashboardId: missingId });
          await dashboardsB.waitForReady();

          await expect
            .poll(
              () => dashboardsB.selectedDashboardId(),
              { message: 'an unknown id is rewritten to the default template' },
            )
            .toBe(DEFAULT_PROJECT_VIEW_ID);
          await dashboardsB.expectSelectedView(DEFAULT_PROJECT_VIEW_NAME);
        });

        await test.step("A project's own view opens from its own project", async () => {
          // The control for all three cases above: without it, a page that
          // replaced *every* deep link with the default template would pass
          // them all.
          const dashboardsA = new ProjectDashboardsPage(page, projectA.id);
          await dashboardsA.goto({ dashboardId: viewA.id });
          await dashboardsA.waitForReady();

          await dashboardsA.expectSelectedView(viewA.name);
          expect(
            dashboardsA.selectedDashboardId(),
            "A's own view opens under A",
          ).toBe(viewA.id);
        });
      },
    );
  },
);
