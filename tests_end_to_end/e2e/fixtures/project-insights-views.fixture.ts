import { test as baseTest } from './bulk-tag-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { InsightsViewRef, ProjectRef } from '../core/backend';

export interface ProjectInsightsViewsRef {
  /** The `project` fixture's project, reused so this one seeds only what it adds. */
  projectA: ProjectRef;
  projectB: ProjectRef;
  /** A view bound to project A. Must be invisible from B. */
  viewA: InsightsViewRef;
  /** A view bound to project B. The decoy: it must be invisible from A. */
  viewB: InsightsViewRef;
  /** A view carrying no project at all — the pre-scoping shape. Visible from both. */
  legacyView: InsightsViewRef;
  /**
   * The section title each seeded view carries, by view id.
   *
   * A section is what makes "this dashboard is on screen" readable: the built-in
   * template renders `DEFAULT_PROJECT_VIEW_SECTION` and a custom view renders
   * its own, so the two can never be mistaken for one another. Asserting the
   * picker's label alone would not distinguish "the view opened" from "the id
   * changed and the page kept drawing the template".
   */
  sectionTitleOf: (view: InsightsViewRef) => string;
}

export interface ProjectInsightsViewsFixtures {
  projectInsightsViews: ProjectInsightsViewsRef;
}

/**
 * Two projects and the three insights views that make project scoping
 * observable: one bound to each project, and one bound to neither.
 *
 * Distinct from `projectScopedDashboard`, which seeds one project-bound view and
 * a workspace-scoped dashboard to separate the two collections. This one exists
 * for the case that fixture has no shape for: the **project-less** view. Every
 * view created before OPIK-8322 carries no `project_id`, and scoping that is too
 * strict would drop them out of the product with no error to report it — so the
 * legacy view has to be seeded, and it cannot be seeded by a helper that
 * requires a project.
 *
 * The second project is what gives the assertions a negative control. A seed of
 * one project and one view would be satisfied by a selector that ignored
 * `project_id` entirely and listed the whole workspace — the failure this
 * exists to catch.
 *
 * Seeded over REST rather than through the SDK bridge because the Python SDK
 * has no insights-view surface at all; `backendClient` wraps the same public
 * `Opik` client the rest of the estate seeds through.
 *
 * Teardown deletes the views explicitly. Deleting a project does not cascade to
 * them — an insights view outlives the project it was scoped to — and
 * `global-teardown`'s run-prefix sweep only knows about the workspace
 * `/dashboards` collection, which this one is not part of.
 */
export const test = baseTest.extend<ProjectInsightsViewsFixtures>({
  projectInsightsViews: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const created = await sdkClient.python.createProject({ name: `${testNamespace}-proj-b` });
    const projectB: ProjectRef = { id: created.id, name: created.name };

    const sections = new Map<string, string>();
    const seed = async (suffix: string, projectId?: string): Promise<InsightsViewRef> => {
      const title = `${testNamespace}-section-${suffix}`;
      const view = await backendClient.createInsightsView({
        name: `${testNamespace}-view-${suffix}`,
        ...(projectId ? { projectId } : {}),
        sections: [{ id: `${testNamespace}-section-id-${suffix}`, title, widgets: [] }],
      });
      sections.set(view.id, title);
      return view;
    };

    const viewA = await seed('a', project.id);
    const viewB = await seed('b', projectB.id);
    // No projectId: the legacy shape. Passing one and expecting the backend to
    // ignore it would test the wrong thing.
    const legacyView = await seed('legacy');

    // The seed's whole value is which project each view carries, and that is a
    // field the create call echoes back rather than one it proves. Read the
    // views again, unscoped, and check the stored value: a backend that
    // silently dropped `project_id` would otherwise leave every scoping
    // assertion below trivially satisfiable, and the spec would read as
    // coverage forever.
    const stored = await backendClient.listInsightsViews();
    const storedProjectId = (id: string): string | null => {
      const found = stored.find((view) => view.id === id);
      if (!found) {
        throw new Error(
          `[projectInsightsViews] seeded view ${id} is missing from the workspace's insights views`,
        );
      }
      return found.projectId;
    };
    const expectedOwners: Array<[string, InsightsViewRef, string | null]> = [
      ['view-a', viewA, project.id],
      ['view-b', viewB, projectB.id],
      ['view-legacy', legacyView, null],
    ];
    for (const [label, view, expected] of expectedOwners) {
      const actual = storedProjectId(view.id);
      if (actual !== expected) {
        throw new Error(
          `[projectInsightsViews] ${label} was stored with project_id ${actual}, expected ${expected}`,
        );
      }
    }

    const sectionTitleOf = (view: InsightsViewRef): string => {
      const title = sections.get(view.id);
      if (!title) {
        throw new Error(`[projectInsightsViews] no seeded section for view ${view.id}`);
      }
      return title;
    };

    await testInfo.attach('opik.project-insights-views', {
      body: JSON.stringify(
        {
          projectA: project,
          projectB,
          viewA,
          viewB,
          legacyView,
          sections: Object.fromEntries(sections),
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    await use({ projectA: project, projectB, viewA, viewB, legacyView, sectionTitleOf });

    if (shouldLeaveArtifacts(testInfo)) {
      console.warn(
        `[projectInsightsViews] leaving ${[viewA, viewB, legacyView]
          .map((v) => v.name)
          .join(', ')} and project ${projectB.name} for debugging`,
      );
      return;
    }

    for (const view of [viewA, viewB, legacyView]) {
      try {
        await backendClient.deleteInsightsView(view.id);
      } catch (err) {
        // A project-less view survives every other sweep in the estate, so say
        // so loudly enough that a leak is traceable to this run.
        console.warn(`[projectInsightsViews] delete warning for view ${view.name}:`, err);
      }
    }
    try {
      await backendClient.deleteProject(projectB.id);
    } catch (err) {
      console.warn(`[projectInsightsViews] delete warning for ${projectB.name}:`, err);
    }
  },
});

export { expect } from './bulk-tag-traces.fixture';
