import { test, expect } from '@e2e/fixtures';
import { DashboardsPage } from '@e2e/pom/dashboards.page';
import {
  ProjectDashboardsPage,
  DEFAULT_PROJECT_VIEW_ID,
  DEFAULT_PROJECT_VIEW_NAME,
  DEFAULT_PROJECT_VIEW_SECTION,
} from '@e2e/pom/project-dashboards.page';

/**
 * Project dashboards stay in the project they were made in, and a `dashboardId`
 * from somewhere else falls back to the built-in template (OPIK-7791, shipped in
 * 2.2.60 by opik#8240).
 *
 * A project dashboard lives in a second collection — `insights-views`, keyed on
 * `project_id` — while the workspace Dashboards list reads `dashboards`, keyed on
 * nothing but the workspace. Every existing spec in this area drives the
 * workspace page, so nothing has ever exercised the project-scoped collection or
 * the page that renders it.
 *
 * Both failure directions cost something real, which is why each is asserted
 * from both ends. Scoping that is too loose leaks one project's view into another
 * — and since reading a view *by id* is not project-scoped, a shared link is the
 * path that does it. Scoping that is too tight takes people's dashboards away
 * from them. The fallback is the half with no coverage anywhere: the page has to
 * notice that the id it was handed belongs to another project and quietly land
 * on the default template instead of rendering someone else's view.
 *
 * The seed carries a workspace-scoped dashboard nobody touches as well, because
 * "the project view is not in the workspace list" would pass just as well against
 * a workspace list that was broken or empty.
 */
test.describe(
  'Project dashboard scoping',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    // The built-in template renders a dozen widgets, each of which issues its own
    // metrics read on mount. Declared on the describe so it covers the fixture's
    // four seed writes too.
    test.slow();

    test(
      'the scoped read returns the view to its own project and to no other, and the workspace collection never sees it',
      { tag: ['@cap:dashboards.list-dashboards'] },
      async ({ projectScopedDashboard, backendClient, testNamespace }) => {
        const { view, ownerProject, otherProject, workspaceDashboard } = projectScopedDashboard;

        await test.step('The seeded view really is attached to its project', async () => {
          // Without this the scoping assertions below could pass over a view the
          // backend attached to no project at all: absent from the other
          // project's list for the wrong reason, and absent from its own too.
          expect(view.projectId, 'the view names the project it was created in').toBe(
            ownerProject.id,
          );
          expect(view.scope, 'the view sits in the project-scoped collection').toBe('insights');
        });

        await test.step("The owner project's scoped read returns it, exactly once", async () => {
          const mine = (await backendClient.listInsightsViews({ projectId: ownerProject.id }))
            .filter((d) => d.name.startsWith(testNamespace))
            .map((d) => d.name);
          // Narrowed to this run's prefix and then compared as a whole set, not
          // searched: a read that also handed back the workspace-scoped
          // dashboard, or a second run's view, would still contain this one.
          expect(mine, "this run's views under the owner project").toEqual([view.name]);
        });

        await test.step("The other project's scoped read returns nothing of this run's", async () => {
          const theirs = await backendClient.listInsightsViews({ projectId: otherProject.id });
          expect(
            theirs.filter((d) => d.name.startsWith(testNamespace)).map((d) => d.name),
            "this run's views under a project that owns none",
          ).toEqual([]);

          // And nothing belonging to any other project either. The prefix filter
          // above only speaks for this run; this speaks for the scoping itself,
          // which is what a leak would break. Views carrying no project at all
          // are the documented legacy carve-out and are allowed through.
          const foreign = theirs.filter(
            (d) => d.projectId !== null && d.projectId !== otherProject.id,
          );
          expect(
            foreign.map((d) => ({ name: d.name, projectId: d.projectId })),
            'views belonging to another project must not appear in this one',
          ).toEqual([]);
        });

        await test.step('The workspace collection carries the workspace dashboard and not the project view', async () => {
          const workspaceNames = (await backendClient.listDashboardsWithPrefix(testNamespace)).map(
            (d) => d.name,
          );
          // The positive control and the negative claim in one assertion: the
          // workspace-scoped dashboard is there, the project-scoped view is not,
          // and an empty or broken list satisfies neither.
          expect(workspaceNames, "this run's workspace-scoped dashboards").toEqual([
            workspaceDashboard.name,
          ]);
        });
      },
    );

    test(
      'the view picker offers the dashboard in its own project and opens it, and does not offer it in another',
      { tag: ['@cap:dashboards.open-dashboard'] },
      async ({ projectScopedDashboard, page }) => {
        const { view, viewSectionTitle, ownerProject, otherProject } = projectScopedDashboard;

        const owner = new ProjectDashboardsPage(page, ownerProject.id);

        await test.step('A fresh project opens on the built-in template', async () => {
          await owner.goto();
          await owner.waitForReady();
          expect(owner.currentDashboardId(), 'the default view a project resolves to').toBe(
            DEFAULT_PROJECT_VIEW_ID,
          );
        });

        await test.step("The picker offers this project's own view", async () => {
          await owner.openViewPicker(DEFAULT_PROJECT_VIEW_NAME);
          await expect(
            owner.viewOption(view.name),
            "the view created in this project is offered in this project's picker",
          ).toHaveCount(1);
        });

        await test.step('Selecting it opens that dashboard rather than the template', async () => {
          await owner.selectView(view.name, view.id);
          await expect(
            owner.viewPickerShowing(view.name),
            'the picker now shows the selected view',
          ).toBeVisible();
          // Which dashboard is actually rendered, not merely which id is in the
          // URL: the seeded view carries a section of its own, and the template
          // carries "At a glance". Asserting both directions is what separates
          // "the right dashboard opened" from "the id changed and the page kept
          // drawing the template".
          await expect(
            owner.sectionTitle(viewSectionTitle),
            "the opened dashboard renders its own section",
          ).toBeVisible();
          await expect(
            owner.sectionTitle(DEFAULT_PROJECT_VIEW_SECTION),
            'the built-in template is no longer the dashboard on screen',
          ).toBeHidden();
        });

        const other = new ProjectDashboardsPage(page, otherProject.id);

        await test.step("The other project's picker does not offer it", async () => {
          await other.goto();
          await other.waitForReady();
          expect(other.currentDashboardId(), 'a project with no views of its own').toBe(
            DEFAULT_PROJECT_VIEW_ID,
          );

          // `openViewPicker` asserts the built-in option is listed before this
          // reads the absence, so an empty or still-loading popover fails there
          // rather than passing here.
          await other.openViewPicker(DEFAULT_PROJECT_VIEW_NAME);
          await expect(
            other.viewOption(view.name),
            "another project's view must not be offered here",
          ).toHaveCount(0);
          await other.dismissViewPicker();
        });
      },
    );

    test(
      "a dashboardId belonging to another project falls back to the built-in template",
      { tag: ['@cap:dashboards.open-dashboard'] },
      async ({ projectScopedDashboard, page }) => {
        const { view, viewSectionTitle, otherProject } = projectScopedDashboard;

        const other = new ProjectDashboardsPage(page, otherProject.id);

        await test.step("Open the other project's Dashboards page carrying the foreign id", async () => {
          // The shape a shared link has. Reading a view by id is not project
          // scoped server-side, so the page receives the foreign dashboard and
          // has to decline it — this is the assertion the release added.
          await other.goto({ dashboardId: view.id });
          await other.waitForReady();
        });

        await test.step('The URL settles on the default template', async () => {
          // Polled rather than read once: the page resolves the id, discovers it
          // belongs elsewhere and rewrites the param, so the foreign id is
          // legitimately in the URL for a render or two first.
          await expect
            .poll(() => other.currentDashboardId(), {
              message: 'the foreign dashboardId must be replaced by the default template id',
              timeout: 30_000,
            })
            .toBe(DEFAULT_PROJECT_VIEW_ID);
        });

        await test.step('The default template is what renders, with no trace of the foreign dashboard', async () => {
          await expect(
            other.sectionTitle(DEFAULT_PROJECT_VIEW_SECTION),
            'the built-in template rendered',
          ).toBeVisible();
          await expect(
            other.viewPickerShowing(DEFAULT_PROJECT_VIEW_NAME),
            'the picker shows the built-in view',
          ).toBeVisible();
          // The leak assertion, and the reason it is page-wide rather than
          // scoped: the failure being excluded is another project's dashboard
          // rendering here, whether that shows up as its name in the picker, its
          // section, or one of its widgets.
          await expect(
            other.anyMentionOf(view.name),
            "the foreign dashboard's name must not appear anywhere on this page",
          ).toHaveCount(0);
          await expect(
            other.anyMentionOf(viewSectionTitle),
            "the foreign dashboard's section must not render here",
          ).toHaveCount(0);
        });
      },
    );

    test(
      'the workspace Dashboards list shows the workspace dashboard and not the project one',
      { tag: ['@cap:dashboards.list-dashboards'] },
      async ({ projectScopedDashboard, page, testNamespace }) => {
        const { view, workspaceDashboard } = projectScopedDashboard;
        const dashboards = new DashboardsPage(page);

        await dashboards.goto();
        await dashboards.waitForReady();

        // One filter on the run's own prefix, which both seeded names share, so
        // the two claims below are read off the same rendered list. Filtering
        // twice would leave the second read depending on whether the POM had
        // replaced the first filter row or appended to it.
        await dashboards.applyListFilter('Name', 'contains', testNamespace);

        await test.step('The workspace-scoped dashboard is listed', async () => {
          // The positive control. Without it "the project view is absent" would
          // also be satisfied by a list that rendered nothing at all — the table
          // shows a failed request as "No matching results", not as an error.
          await expect(dashboards.row(workspaceDashboard.name)).toBeVisible();
          await expect(page.getByText('No matching results')).toBeHidden();
        });

        await test.step('The project-scoped view is not', async () => {
          await expect(
            dashboards.row(view.name),
            'a project-scoped dashboard must not appear in the workspace list',
          ).toHaveCount(0);
        });
      },
    );
  },
);
