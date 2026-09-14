import { test as baseTest } from './cached-token-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { DashboardRef, InsightsViewRef, ProjectRef } from '../core/backend';

export interface ProjectScopedDashboardRef {
  /** The project-scoped dashboard, attached to `ownerProject`. */
  view: InsightsViewRef;
  /**
   * The title of the one section `view` carries.
   *
   * The section is what makes "this dashboard is on screen" readable: the
   * default template renders its own section titles, so a page showing this one
   * is showing this dashboard and not the fallback.
   */
  viewSectionTitle: string;
  /** The project `view` belongs to — the `project` fixture's own project. */
  ownerProject: ProjectRef;
  /** A second project, which must never see `view`. */
  otherProject: ProjectRef;
  /**
   * A workspace-scoped dashboard, seeded into the OTHER collection
   * (`/v1/private/dashboards`).
   *
   * The positive control for the workspace Dashboards list: asserting only that
   * the project-scoped view is absent there would pass just as well against a
   * list that was broken, empty, or never loaded.
   */
  workspaceDashboard: DashboardRef;
}

export interface ProjectScopedDashboardFixtures {
  projectScopedDashboard: ProjectScopedDashboardRef;
}

/**
 * The shape a dashboard-scoping assertion needs: one dashboard in one project,
 * a second project that must not see it, and a workspace-scoped dashboard that
 * must stay visible where it belongs.
 *
 * Seeded here rather than in the test because the scoping runs in both
 * directions, and every one of the four entities has to exist before any of the
 * four assertions means anything. It is also the only way the second project and
 * the two dashboards get torn down on a failure: nothing cascades to a dashboard
 * (neither collection hangs off a project), and `global-teardown`'s prefix sweep
 * knows the workspace `/dashboards` collection but not `insights-views`.
 *
 * The second project is created through the bridge like the `project` fixture's
 * own, so both are namespaced by run and swept the same way.
 */
export const test = baseTest.extend<ProjectScopedDashboardFixtures>({
  projectScopedDashboard: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const otherProjectName = `${testNamespace}-proj-other`;
    const created = await sdkClient.python.createProject({ name: otherProjectName });
    const otherProject: ProjectRef = { id: created.id, name: created.name };

    const viewName = `${testNamespace}-scoped-view`;
    const viewSectionTitle = `${testNamespace}-scoped-section`;

    let view: InsightsViewRef | null = null;
    let workspaceDashboard: DashboardRef | null = null;

    try {
      view = await backendClient.createInsightsView({
        name: viewName,
        projectId: project.id,
        sections: [{ id: `${testNamespace}-section-1`, title: viewSectionTitle, widgets: [] }],
      });

      workspaceDashboard = await backendClient.createDashboard({
        name: `${testNamespace}-workspace-dash`,
        description: 'workspace-scoped control for the project-scoping spec',
      });

      const ref: ProjectScopedDashboardRef = {
        view,
        viewSectionTitle,
        ownerProject: project,
        otherProject,
        workspaceDashboard,
      };

      await testInfo.attach('opik.projectScopedDashboard', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        // Each delete in its own try: one that fails must not orphan the rest,
        // and none of them may rethrow over whichever assertion actually broke
        // the test. Dashboards first, project second — a dashboard outlives the
        // project it names, so the reverse order would leak on an early failure.
        if (view !== null) {
          try {
            await backendClient.deleteInsightsView(view.id);
          } catch (err) {
            console.warn(`[projectScopedDashboard fixture] view delete warning:`, err);
          }
        }
        if (workspaceDashboard !== null) {
          try {
            await backendClient.deleteDashboard(workspaceDashboard.id);
          } catch (err) {
            console.warn(`[projectScopedDashboard fixture] dashboard delete warning:`, err);
          }
        }
        try {
          await backendClient.deleteProject(otherProject.id);
        } catch (err) {
          console.warn(
            `[projectScopedDashboard fixture] delete warning for ${otherProjectName}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './cached-token-spans.fixture';
