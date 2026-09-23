import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import {
  DEFAULT_PROJECT_VIEW_ID,
  DEFAULT_PROJECT_VIEW_NAME,
  DEFAULT_PROJECT_VIEW_SECTION,
  ProjectDashboardsPage,
} from '@e2e/pom/project-dashboards.page';

/**
 * The project-less insights view — the half of project scoping that
 * `project-dashboard-scoping.spec.ts` has no seed for (OPIK-8322 / #8240).
 *
 * That spec already covers the project-bound direction from both ends: a view is
 * returned to its own project and to no other, its picker offers it only where it
 * belongs, and a `?dashboardId=` from another project is declined. None of it is
 * repeated here.
 *
 * What it cannot cover is the view carrying no `project_id` at all. Every view
 * created before scoping shipped has that shape, and its rules run the opposite
 * way: it must stay visible from *every* project, and a link to it must be
 * honoured rather than replaced. Scoping that is too strict is as real a failure
 * as scoping that leaks, and it is the quieter one — nothing errors, people's
 * dashboards simply stop being offered. `backendClient.createInsightsView`
 * therefore takes an optional `projectId`, because this is the only seed in the
 * estate that needs it absent.
 *
 * The second project and the second bound view are the negative controls. Both
 * "the legacy view is offered here" assertions would pass just as well against a
 * selector that ignored `project_id` and listed the whole workspace, so each is
 * paired with the foreign view it must not list.
 *
 * SCOPE — the read path only: which views a project offers, and which view a
 * `dashboardId` link resolves to. Creating, renaming, duplicating and deleting a
 * view through the selector's own dialogs are untouched, and so is anything the
 * widgets themselves render.
 */
test.describe(
  'Project-less insights views stay visible from every project',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    // The built-in template renders a dozen widgets, each issuing its own metrics
    // read on mount, and this spec lands on it repeatedly. Declared on the
    // describe so it covers the fixture's four seed writes too.
    test.slow();

    test(
      "a project's selector offers its own view and the project-less one, and no other project's",
      { tag: ['@cap:dashboards.list-dashboards'] },
      async ({ projectInsightsViews, backendClient, page }) => {
        const { projectA, projectB, viewA, viewB, legacyView } = projectInsightsViews;

        const dashboardsA = new ProjectDashboardsPage(page, projectA.id);

        await test.step("Project A's selector offers A's view and the legacy one, and not B's", async () => {
          // `goto` waits for a `/insights-views` read carrying
          // `project_id=<projectA>` and asserts it succeeded, so reaching this
          // line is already evidence the selector asked for one project rather
          // than the workspace — and that the absence assertion below is being
          // made against a list that actually arrived.
          await dashboardsA.goto();
          await dashboardsA.waitForReady();
          // A project with no view restored opens on the built-in template.
          await dashboardsA.expectSelectedView(DEFAULT_PROJECT_VIEW_NAME);

          await dashboardsA.openViewPicker(DEFAULT_PROJECT_VIEW_NAME);
          await expect(
            dashboardsA.viewOption(legacyView.name),
            'the project-less view, which every project keeps',
          ).toHaveCount(1);
          await expect(dashboardsA.viewOption(viewA.name), "A's own view").toHaveCount(1);
          // The control that makes the assertion above mean something: a
          // selector listing the whole workspace would satisfy it too.
          await expect(
            dashboardsA.viewOption(viewB.name),
            "another project's view must not be offered",
          ).toHaveCount(0);
          await dashboardsA.dismissViewPicker();
        });

        const dashboardsB = new ProjectDashboardsPage(page, projectB.id);

        await test.step("Project B's selector offers the same legacy view, and not A's", async () => {
          // Mirrored rather than assumed. "Visible from every project" is the
          // claim, and one project is not every project — a view attached to A by
          // accident would pass the step above unchanged.
          await dashboardsB.goto();
          await dashboardsB.waitForReady();
          await dashboardsB.openViewPicker(DEFAULT_PROJECT_VIEW_NAME);

          await expect(
            dashboardsB.viewOption(legacyView.name),
            'the project-less view is offered from both projects, not just the first',
          ).toHaveCount(1);
          await expect(dashboardsB.viewOption(viewB.name), "B's own view").toHaveCount(1);
          await expect(
            dashboardsB.viewOption(viewA.name),
            "project A's view must not be offered here",
          ).toHaveCount(0);
          await dashboardsB.dismissViewPicker();
        });

        await test.step('The scoped read answers the same way, over every row and not just ours', async () => {
          // The UI assertions above name the rows this run seeded. This one is
          // exhaustive: every view a scoped read returns must belong to the
          // project asked for or to no project at all. A workspace shared with
          // other runs would defeat a count comparison, but it cannot defeat
          // this — one foreign, project-bound row is a failure however many
          // rows there are.
          //
          // Both directions, from one read each. Checking only A's list would
          // pass against a backend that had simply pinned every answer to A.
          for (const { project, ownView, otherView } of [
            { project: projectA, ownView: viewA, otherView: viewB },
            { project: projectB, ownView: viewB, otherView: viewA },
          ]) {
            const scoped = await backendClient.listInsightsViews({ projectId: project.id });
            expect(
              scoped
                .filter((view) => view.projectId !== null && view.projectId !== project.id)
                .map((view) => `${view.name} (${view.projectId})`),
              `views ${project.name} was offered that belong to another project`,
            ).toEqual([]);

            const ids = scoped.map((view) => view.id);
            expect(ids, `${project.name} is offered the project-less view`).toContain(
              legacyView.id,
            );
            expect(ids, `${project.name} is offered its own view`).toContain(ownView.id);
            expect(ids, `${project.name} is not offered the other project's view`).not.toContain(
              otherView.id,
            );
          }
        });
      },
    );

    test(
      'a link to a project-less view is honoured, and one to an unknown view is not',
      { tag: ['@cap:dashboards.open-dashboard'] },
      async ({ projectInsightsViews, page }) => {
        const { projectA, projectB, viewA, legacyView, sectionTitleOf } = projectInsightsViews;
        const dashboardsB = new ProjectDashboardsPage(page, projectB.id);

        await test.step('A link to a project-less view is honoured rather than replaced', async () => {
          await dashboardsB.goto({ dashboardId: legacyView.id });
          await dashboardsB.waitForReady();

          // Both halves, because the URL alone does not say what rendered. The
          // page writes the default template's id on mount and only replaces it
          // once the lookup resolves, so an id that merely *survived* could be a
          // snapshot taken before the fallback ran — while the view's own section
          // on screen is the page having committed to it.
          await dashboardsB.expectSelectedView(legacyView.name);
          await expect(
            dashboardsB.sectionTitle(sectionTitleOf(legacyView)),
            "the project-less view's own section is what renders",
          ).toBeVisible();
          await expect(
            dashboardsB.sectionTitle(DEFAULT_PROJECT_VIEW_SECTION),
            'the built-in template was not substituted for it',
          ).toBeHidden();
          expect(
            dashboardsB.currentDashboardId(),
            'a view belonging to no project stays open where it was linked',
          ).toBe(legacyView.id);
        });

        await test.step('A link to an id the workspace does not hold falls back to the template', async () => {
          // Any well-formed id the workspace does not hold. Minted rather than
          // hard-coded so it cannot collide with a seeded view; nothing about the
          // assertion depends on its value.
          const missingId = uuid7();
          await dashboardsB.goto({ dashboardId: missingId });
          await dashboardsB.waitForReady();

          // Polled: the unknown id is legitimately in the URL for a render or two
          // while the page resolves it and discovers there is nothing to show.
          await expect
            .poll(() => dashboardsB.currentDashboardId(), {
              message: 'an unknown dashboardId must be rewritten to the default template',
              timeout: 30_000,
            })
            .toBe(DEFAULT_PROJECT_VIEW_ID);
          await expect(
            dashboardsB.sectionTitle(DEFAULT_PROJECT_VIEW_SECTION),
            'the built-in template is what renders instead',
          ).toBeVisible();
        });

        await test.step("A project's own view still opens from its own project", async () => {
          // The control for the fallback above: a page that replaced *every* deep
          // link with the default template would pass it unchanged.
          const dashboardsA = new ProjectDashboardsPage(page, projectA.id);
          await dashboardsA.goto({ dashboardId: viewA.id });
          await dashboardsA.waitForReady();

          // A's own view opens under A.
          await dashboardsA.expectSelectedView(viewA.name);
          await expect(
            dashboardsA.sectionTitle(sectionTitleOf(viewA)),
            "and it is that view's dashboard that renders",
          ).toBeVisible();
          expect(dashboardsA.currentDashboardId(), "the link A owns is kept").toBe(viewA.id);
        });

        await test.step('The project-less view can also be opened from the selector, not only by link', async () => {
          // Every assertion above arrives by URL. This one opens the view the way
          // a user does — through the picker — so the capability is earned on the
          // interaction and not only on link resolution.
          //
          // The legacy view is the target, and project A is where it happens,
          // because the page is already on `viewA`: the selection therefore has
          // to *change*, which no amount of persisted selector state could fake.
          // Choosing a project-bound view this way is already covered by
          // `project-dashboard-scoping.spec.ts`; choosing a project-less one is
          // not, and it is the case that would break if scoping were tightened.
          const dashboardsA = new ProjectDashboardsPage(page, projectA.id);
          await dashboardsA.openViewPicker(viewA.name);
          await dashboardsA.selectView(legacyView.name, legacyView.id);

          // The picker now shows the project-less view.
          await dashboardsA.expectSelectedView(legacyView.name);
          await expect(
            dashboardsA.sectionTitle(sectionTitleOf(legacyView)),
            'and that view is the dashboard on screen',
          ).toBeVisible();
        });
      },
    );
  },
);
