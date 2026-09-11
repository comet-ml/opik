import { test as baseTest } from './summarised-datasets.fixture';
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
}

export interface ProjectInsightsViewsFixtures {
  projectInsightsViews: ProjectInsightsViewsRef;
}

/**
 * Two projects and the three insights views that make project scoping
 * observable: one bound to each project, and one bound to neither.
 *
 * The second project is what gives the assertions a negative control. A seed of
 * one project and one view would be satisfied by a selector that ignored
 * `project_id` entirely and listed the whole workspace — the failure this
 * exists to catch. The project-less view is the other half: scoping that is too
 * strict would hide it, and a view created before the scoping change would
 * vanish from the product with no error to report it.
 *
 * Seeded over REST rather than through the SDK bridge because the Python SDK
 * has no insights-view surface at all; `backendClient` wraps the same public
 * `Opik` client the rest of the estate seeds through.
 *
 * Teardown deletes the views explicitly. Deleting a project does not cascade to
 * them — an insights view outlives the project it was scoped to — and
 * `global-teardown`'s run-prefix sweep only knows about the workspace
 * `/dashboards` collection, which this one is not part of.
 *
 * Each resource is registered the moment it is created and released in a
 * `finally`, the same lifecycle `id-aged-traces.fixture` documents. That
 * matters more here than it does there: the ownership check below throws *by
 * design*, and cleanup that only ran after a complete seed would turn every
 * such throw into a permanent leak — an insights view no sweep in the estate
 * recovers, and a project-less one is then offered by every project's selector
 * for good.
 */
export const test = baseTest.extend<ProjectInsightsViewsFixtures>({
  projectInsightsViews: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const seededViews: InsightsViewRef[] = [];
    let projectB: ProjectRef | null = null;

    try {
      projectB = await sdkClient.python.createProject({ name: `${testNamespace}-proj-b` });

      const viewA = await backendClient.createInsightsView({
        name: `${testNamespace}-view-a`,
        projectId: project.id,
      });
      seededViews.push(viewA);
      const viewB = await backendClient.createInsightsView({
        name: `${testNamespace}-view-b`,
        projectId: projectB.id,
      });
      seededViews.push(viewB);
      // No projectId: the legacy shape. Passing one and expecting the backend to
      // ignore it would test the wrong thing.
      const legacyView = await backendClient.createInsightsView({
        name: `${testNamespace}-view-legacy`,
      });
      seededViews.push(legacyView);

      // The seed's whole value is which project each view carries, and that is a
      // field the create call echoes back rather than one it proves. Read the
      // views again, unscoped, and check the stored value: a backend that
      // silently dropped `project_id` would otherwise leave every scoping
      // assertion below trivially satisfiable, and the spec would read as
      // coverage forever.
      const stored = await backendClient.findInsightsViews();
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

      await testInfo.attach('opik.project-insights-views', {
        body: JSON.stringify(
          {
            projectA: project,
            projectB,
            viewA,
            viewB,
            legacyView,
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use({ projectA: project, projectB, viewA, viewB, legacyView });
    } finally {
      if (shouldLeaveArtifacts(testInfo)) {
        console.warn(
          `[projectInsightsViews] leaving ${
            seededViews.map((v) => v.name).join(', ') || 'no views'
          }${projectB ? ` and project ${projectB.name}` : ''} for debugging`,
        );
      } else {
        try {
          await backendClient.deleteInsightsViewsBatch(seededViews.map((view) => view.id));
        } catch (err) {
          // A project-less view survives every other sweep in the estate, so say
          // so loudly enough that a leak is traceable to this run.
          console.warn('[projectInsightsViews] insights view delete warning:', err);
        }
        if (projectB) {
          try {
            await backendClient.deleteProject(projectB.id);
          } catch (err) {
            console.warn(`[projectInsightsViews] delete warning for ${projectB.name}:`, err);
          }
        }
      }
    }
  },
});

export { expect } from './summarised-datasets.fixture';
