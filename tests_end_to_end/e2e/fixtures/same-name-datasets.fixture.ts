import { test as baseTest } from './experiments-tab-split.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { BackendClient } from '../core/backend';

/**
 * Item payloads per copy of the shared name. Deliberately different in CONTENT
 * and in COUNT: a project-scoped read that resolved the wrong project's dataset
 * would come back with the wrong rows, and if the two copies held the same rows
 * nothing could tell the difference.
 */
const A_INPUTS = ['a-one', 'a-two'];
const B_INPUTS = ['b-one'];
const BYSTANDER_INPUTS = ['bystander-one'];
const SOLO_INPUTS = ['solo-one'];

export interface SameNameProjectRef {
  id: string;
  name: string;
}

export interface SameNameProjectsRef {
  /**
   * The dataset name both projects are to hold. Inside the run prefix, so
   * whatever creates it — including the UI, which is the point for the create
   * flow — lands in the namespace `global-teardown` sweeps.
   */
  datasetName: string;
  projectA: SameNameProjectRef;
  projectB: SameNameProjectRef;
}

/** One dataset seeded by this fixture, and the project it belongs to. */
export interface SameNameCopyRef {
  project: SameNameProjectRef;
  /** The dataset's own name — shared with another copy, or its own. */
  name: string;
  datasetId: string;
  /** The `input` values this copy's items carry, as seeded. */
  inputs: string[];
}

export interface SameNameDatasetsRef {
  /** The name held by BOTH `a` and `b`, and by nothing else in the workspace. */
  datasetName: string;
  /** Project A's copy — two items. */
  a: SameNameCopyRef;
  /** Project B's copy — one item, so the two copies differ in size as well as content. */
  b: SameNameCopyRef;
  /**
   * A differently-named dataset in project B, which no spec addresses.
   *
   * The bystander for a delete scoped to project B: asserting only that B's copy
   * of the shared name is gone would read the same if the delete had taken
   * everything in project B with it.
   */
  bystanderInB: SameNameCopyRef;
  /**
   * A project holding no dataset at all — the "wrong project" to address a name
   * in. Addressing a name that exists elsewhere through this project is what
   * drives the workspace-wide fallback, and it must still hold nothing
   * afterwards.
   */
  emptyProject: SameNameProjectRef;
  /**
   * A name held in exactly ONE project, in its own project — the control for
   * every name-only call the shared name is refused on.
   *
   * In a project of its own rather than in A or B so that the sets of datasets
   * those two hold stay exactly the shared name (and B's bystander): the
   * project-scoped list assertions are closed ("these rows and no others"), and
   * a control dataset sitting in one of them would have to be excused in every
   * one of them.
   */
  solo: SameNameCopyRef;
}

export interface SameNameProjectsFixtures {
  sameNameProjects: SameNameProjectsRef;
}

export interface SameNameDatasetsFixtures {
  sameNameDatasets: SameNameDatasetsRef;
}

/**
 * Asserts that a name is held by exactly `expected` datasets in the whole
 * workspace, and returns their ids.
 *
 * This is the premise of every spec built on this fixture, and it is a premise
 * that can silently fail to hold: before opik#9659 the second create would have
 * been refused outright, and a seed that quietly ended up with one dataset
 * instead of two would leave a UI assertion comparing one row against one row
 * and passing while asserting nothing. The search is workspace-wide and by
 * prefix, so it also catches a copy filed under a project nobody expected.
 */
const requireNameHeldBy = async (
  backendClient: BackendClient,
  name: string,
  expected: number,
): Promise<string[]> => {
  const matches = (await backendClient.listDatasetsWithPrefix(name)).filter(
    (dataset) => dataset.name === name,
  );
  if (matches.length !== expected) {
    throw new Error(
      `[sameNameDatasets fixture] the workspace holds ${matches.length} dataset(s) named ` +
        `'${name}', expected ${expected}: [${matches.map((d) => d.id).join(', ')}]`,
    );
  }
  return matches.map((dataset) => dataset.id);
};

/**
 * Two projects and a dataset name neither of them holds yet.
 *
 * The seed for the create flow itself: the specs that use this one create the
 * datasets through the Create-dataset sidebar, because creating the same name in
 * a second project IS what they are testing. Seeding them here would make the
 * assertion about the API instead.
 *
 * Project A is the shared `project` fixture, so only the second project is this
 * fixture's to tear down.
 */
export const test = baseTest
  .extend<SameNameProjectsFixtures>({
    sameNameProjects: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
      const projectB = await sdkClient.python.createProject({
        name: `${testNamespace}-proj-b`,
      });

      const ref: SameNameProjectsRef = {
        datasetName: `${testNamespace}-dup`,
        projectA: { id: project.id, name: project.name },
        projectB: { id: projectB.id, name: projectB.name },
      };
      await testInfo.attach('opik.sameNameProjects', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      try {
        await use(ref);
      } finally {
        if (!shouldLeaveArtifacts(testInfo)) {
          try {
            await backendClient.deleteProject(projectB.id);
          } catch (err) {
            console.warn(`[sameNameProjects fixture] delete warning for ${projectB.name}:`, err);
          }
        }
      }
    },
  })
  .extend<SameNameDatasetsFixtures>({
    /**
     * The same dataset name in two projects, each copy holding its own items,
     * plus the three things an assertion about that state needs to be able to
     * fail: a bystander dataset in project B, a project holding nothing, and the
     * same scenario's control — a name held in exactly one project.
     *
     * Seeded through the SDK rather than the UI: what these specs are about is
     * how a name RESOLVES once two projects hold it, not how it got there.
     *
     * Every dataset is created with its items in one `create_dataset` call, so
     * each copy has exactly one version and the item sets below are the whole of
     * what it holds.
     */
    sameNameDatasets: async (
      { sdkClient, backendClient, sameNameProjects, testNamespace },
      use,
      testInfo,
    ) => {
      const datasetIds: string[] = [];
      const projectIds: string[] = [];

      const seed = async (
        project: SameNameProjectRef,
        name: string,
        inputs: string[],
        description: string,
      ): Promise<SameNameCopyRef> => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name,
          description,
          items: inputs.map((input) => ({ input, expected_output: `${input}-output` })),
        });
        datasetIds.push(created.id);

        const stored = await backendClient.getDatasetItems(created.id);
        const storedInputs = stored.map((item) => String(item.data.input)).sort();
        if (storedInputs.join('|') !== [...inputs].sort().join('|')) {
          throw new Error(
            `[sameNameDatasets fixture] dataset '${name}' in project '${project.name}' holds ` +
              `items [${storedInputs.join(', ')}], expected [${[...inputs].sort().join(', ')}]`,
          );
        }
        return { project, name, datasetId: created.id, inputs };
      };

      try {
        const { datasetName, projectA, projectB } = sameNameProjects;

        const a = await seed(projectA, datasetName, A_INPUTS, 'the copy in project A');
        const b = await seed(projectB, datasetName, B_INPUTS, 'the copy in project B');
        if (a.datasetId === b.datasetId) {
          throw new Error(
            `[sameNameDatasets fixture] both projects resolved to the same dataset ` +
              `${a.datasetId} — the second create did not make a new one`,
          );
        }
        // The whole workspace holds exactly these two datasets under this name.
        // A third would make every "refused because it is ambiguous" assertion
        // below pass for a reason the spec did not intend.
        const held = await requireNameHeldBy(backendClient, datasetName, 2);
        if ([...held].sort().join('|') !== [a.datasetId, b.datasetId].sort().join('|')) {
          throw new Error(
            `[sameNameDatasets fixture] the two datasets named '${datasetName}' are ` +
              `[${held.join(', ')}], expected [${a.datasetId}, ${b.datasetId}]`,
          );
        }

        const bystanderInB = await seed(
          projectB,
          `${testNamespace}-bystander`,
          BYSTANDER_INPUTS,
          'never addressed by the specs — must survive',
        );

        const emptyProjectCreated = await sdkClient.python.createProject({
          name: `${testNamespace}-proj-empty`,
        });
        projectIds.push(emptyProjectCreated.id);
        const emptyProject: SameNameProjectRef = {
          id: emptyProjectCreated.id,
          name: emptyProjectCreated.name,
        };
        const emptyList = await backendClient.listDatasetSummaries({
          projectId: emptyProject.id,
        });
        if (emptyList.total !== 0) {
          throw new Error(
            `[sameNameDatasets fixture] project '${emptyProject.name}' already holds ` +
              `${emptyList.total} dataset(s); it must hold none for "nothing was created ` +
              `in it" to mean anything`,
          );
        }

        const soloProjectCreated = await sdkClient.python.createProject({
          name: `${testNamespace}-proj-solo`,
        });
        projectIds.push(soloProjectCreated.id);
        const soloName = `${testNamespace}-solo`;
        const solo = await seed(
          { id: soloProjectCreated.id, name: soloProjectCreated.name },
          soloName,
          SOLO_INPUTS,
          'held in one project only — the control',
        );
        await requireNameHeldBy(backendClient, soloName, 1);

        const ref: SameNameDatasetsRef = {
          datasetName,
          a,
          b,
          bystanderInB,
          emptyProject,
          solo,
        };
        await testInfo.attach('opik.sameNameDatasets', {
          body: JSON.stringify(ref, null, 2),
          contentType: 'application/json',
        });

        await use(ref);
      } finally {
        if (!shouldLeaveArtifacts(testInfo)) {
          const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
            try {
              await fn();
            } catch (err) {
              console.warn(`[sameNameDatasets fixture] delete warning for ${what}:`, err);
            }
          };
          // Datasets before the projects they belong to, and reversed so a
          // partial seed tears down in the order it was built. A dataset a spec
          // deleted on purpose is already gone; `deleteDataset` swallows its 404.
          for (const id of [...datasetIds].reverse()) {
            await safe(`dataset ${id}`, () => backendClient.deleteDataset(id));
          }
          for (const id of [...projectIds].reverse()) {
            await safe(`project ${id}`, () => backendClient.deleteProject(id));
          }
        }
      }
    },
  });

export { expect } from './experiments-tab-split.fixture';
