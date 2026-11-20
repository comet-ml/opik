import { test as baseTest } from './ollie-table-widget.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import type { BackendClient, ProjectExperimentRef } from '../core/backend';

/** One seeded experiment, as the SERVER stored it. */
export interface TabSplitExperimentRef {
  id: string;
  name: string;
  /**
   * The `evaluation_method` read back off the list endpoint — never the value
   * that was requested. The two can legitimately differ: a create that names no
   * method at all is stored as `dataset` by `ExperimentDAO`, and that defaulting
   * is the whole reason the Datasets tab filters with `!=` rather than `=`.
   */
  evaluationMethod: string;
  /** `dataset_version_summary.version_name`, which the tab's version column renders. */
  datasetVersionName: string;
  datasetId: string;
  datasetName: string;
}

/** A seeded project and the experiments it holds. */
export interface TabSplitProjectRef {
  projectId: string;
  projectName: string;
  experiments: TabSplitExperimentRef[];
}

export interface ExperimentsTabSplitRef {
  /**
   * One project holding one experiment of each kind the two tabs have to
   * separate: an `evaluation_suite` one, a `dataset` one, and one created with
   * NO `evaluation_method` at all.
   *
   * The third is not a duplicate of the second. It is the only legacy shape
   * reachable on a fresh install — the backend defaults a missing method to
   * `dataset` on insert, and `unknown` is written only by the aggregates DAO,
   * which the list query does not read. What it pins is that an experiment
   * nobody ever classified is still listed by one of the two tabs rather than
   * by neither, which is the silent-wrongness case the split introduced: an
   * experiment that falls out of both tabs leaves two perfectly healthy-looking
   * tables between them.
   */
  mixed: TabSplitProjectRef & {
    suiteExperiment: TabSplitExperimentRef;
    datasetExperiment: TabSplitExperimentRef;
    legacyExperiment: TabSplitExperimentRef;
    /**
     * The trace feedback score on `datasetExperiment`'s item, and therefore the
     * one per-score column the project's experiments table can offer. The
     * Datasets tab must offer it and the Test suites tab must not — with no
     * score seeded at all, "the Test suites picker lists no score column" is
     * satisfied by a project that has no scores to list, and the assertion
     * cannot fail.
     */
    feedbackScoreName: string;
  };
  /**
   * A project whose only experiments are `evaluation_suite`, so the tab a bare
   * URL resolves to is decided by its data and nothing else.
   */
  suiteOnly: TabSplitProjectRef & { experiment: TabSplitExperimentRef };
  /**
   * The mirror image: dataset-method only. The pair is what makes the default
   * tab assertable at all — one project alone cannot tell "resolved from this
   * project's data" from "always opens on the same tab".
   */
  datasetOnly: TabSplitProjectRef & { experiment: TabSplitExperimentRef };
}

export interface ExperimentsTabSplitFixtures {
  experimentsTabSplit: ExperimentsTabSplitRef;
}

const SUITE_ITEMS = [{ data: { question: 'tab split suite question' } }];
const DATASET_ITEMS = [{ input: 'tab split input', expected_output: 'tab split output' }];

const FEEDBACK_SCORE_VALUE = 0.75;

/**
 * Both tabs read `dataset_version_summary` off the experiment, which the
 * backend fills from the item source's LATEST version at create time — so the
 * items have to be in place before the experiment is written, or the row comes
 * back version-less and the version column renders a dash for a reason that has
 * nothing to do with the tab.
 */
async function requireStored(
  backendClient: BackendClient,
  projectId: string,
  expected: Array<{ id: string; evaluationMethod: string }>,
): Promise<Map<string, ProjectExperimentRef>> {
  const { total, experiments } = await backendClient.listExperimentsForProject({ projectId });
  if (total !== expected.length || experiments.length !== expected.length) {
    throw new Error(
      `[experimentsTabSplit fixture] project ${projectId} holds total=${total} / ` +
        `${experiments.length} listed experiment(s), expected ${expected.length} — ` +
        'a tab partition assertion over this project would not be a closed set',
    );
  }
  const byId = new Map(experiments.map((e) => [e.id, e]));
  for (const want of expected) {
    const row = byId.get(want.id);
    if (!row) {
      throw new Error(
        `[experimentsTabSplit fixture] experiment ${want.id} is not in project ${projectId}'s list`,
      );
    }
    if (row.evaluationMethod !== want.evaluationMethod) {
      throw new Error(
        `[experimentsTabSplit fixture] experiment '${row.name}' was stored under ` +
          `evaluation_method '${row.evaluationMethod}', expected '${want.evaluationMethod}' — ` +
          'the seed does not hold the state the spec is about',
      );
    }
    if (!row.datasetVersionName) {
      throw new Error(
        `[experimentsTabSplit fixture] experiment '${row.name}' carries no ` +
          'dataset_version_summary.version_name — its items were not in place before it ' +
          'was created, so the version column has nothing to render',
      );
    }
  }
  return byId;
}

/**
 * The seed behind the Test suites / Datasets tab split (opik#9611): three
 * projects whose experiments differ only in `evaluation_method`.
 *
 * Seeded over REST rather than through `evaluate()` or a real suite run, because
 * the method is the single variable here and the SDK's evaluate path only ever
 * writes `dataset`. Reaching `evaluation_suite` any other way means running a
 * test suite, which an LLM judges — neither deterministic nor free.
 *
 * Every experiment is read back before the browser opens: what it was stored
 * under, and that it ended up linked to a dataset version. A UI assertion over
 * a seed that silently failed to set up is a test that cannot fail, and reads as
 * coverage forever.
 */
export const test = baseTest.extend<ExperimentsTabSplitFixtures>({
  experimentsTabSplit: async (
    { sdkClient, backendClient, project, testNamespace, registerProjectTracesCleanup },
    use,
    testInfo,
  ) => {
    /** Ids to delete at teardown, newest first — experiments before their item sources. */
    const experimentIds: string[] = [];
    const datasetIds: string[] = [];
    /** Only the projects this fixture created; `project` tears itself down. */
    const projectIds: string[] = [];

    const createSuiteSource = async (projectName: string, name: string) => {
      const created = await sdkClient.python.createTestSuite({
        project_name: projectName,
        name,
        items: SUITE_ITEMS,
      });
      datasetIds.push(created.id);
      return { id: created.id, name: created.name };
    };

    const createDatasetSource = async (projectName: string, name: string) => {
      const created = await sdkClient.python.createDataset({
        project_name: projectName,
        name,
        items: DATASET_ITEMS,
      });
      datasetIds.push(created.id);
      return { id: created.id, name: created.name };
    };

    const createExperiment = async (args: {
      projectName: string;
      name: string;
      source: { id: string; name: string };
      /** Omitted deliberately for the legacy row — see `legacyExperiment`. */
      evaluationMethod?: 'dataset' | 'evaluation_suite';
    }): Promise<{ id: string; name: string; source: { id: string; name: string } }> => {
      const id = uuid7();
      await backendClient.createExperiment({
        id,
        name: args.name,
        datasetName: args.source.name,
        projectName: args.projectName,
        ...(args.evaluationMethod === undefined
          ? {}
          : { evaluationMethod: args.evaluationMethod }),
      });
      experimentIds.push(id);
      return { id, name: args.name, source: args.source };
    };

    try {
      // ---- the mixed project: all three methods, on the shared `project` ----
      const mixedSuiteSource = await createSuiteSource(project.name, `${testNamespace}-mixed-suite`);
      const mixedDatasetSource = await createDatasetSource(
        project.name,
        `${testNamespace}-mixed-ds`,
      );

      const suiteSeed = await createExperiment({
        projectName: project.name,
        name: `${testNamespace}-mixed-suite-exp`,
        source: mixedSuiteSource,
        evaluationMethod: 'evaluation_suite',
      });
      const datasetSeed = await createExperiment({
        projectName: project.name,
        name: `${testNamespace}-mixed-dataset-exp`,
        source: mixedDatasetSource,
        evaluationMethod: 'dataset',
      });
      const legacySeed = await createExperiment({
        projectName: project.name,
        name: `${testNamespace}-mixed-legacy-exp`,
        source: mixedDatasetSource,
      });

      // One trace, one experiment item and one score on the dataset-method
      // experiment. The score is what gives the project a per-score column to
      // offer, and the item is what makes that score reachable through the
      // experiments score-names endpoint rather than only the traces one.
      const feedbackScoreName = 'tab_split_accuracy';
      const datasetItemIds = (await backendClient.getDatasetItems(mixedDatasetSource.id)).map(
        (item) => item.id,
      );
      if (datasetItemIds.length !== DATASET_ITEMS.length) {
        throw new Error(
          `[experimentsTabSplit fixture] dataset '${mixedDatasetSource.name}' stored ` +
            `${datasetItemIds.length} item(s), expected ${DATASET_ITEMS.length}`,
        );
      }
      const traceId = uuid7();
      registerProjectTracesCleanup(project.id);
      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: [
          {
            id: traceId,
            name: `${testNamespace}-mixed-dataset-exp-trace`,
            input: { question: DATASET_ITEMS[0].input },
            output: { answer: DATASET_ITEMS[0].expected_output },
          },
        ],
      });
      await backendClient.createExperimentItems([
        { experimentId: datasetSeed.id, datasetItemId: datasetItemIds[0], traceId },
      ]);
      await backendClient.setTraceFeedbackScores({
        projectName: project.name,
        scores: [{ traceId, name: feedbackScoreName, value: FEEDBACK_SCORE_VALUE }],
      });

      const mixedRows = await requireStored(backendClient, project.id, [
        { id: suiteSeed.id, evaluationMethod: 'evaluation_suite' },
        { id: datasetSeed.id, evaluationMethod: 'dataset' },
        // Created with no method at all; the backend's own default is what this
        // asserts, and it is the premise of the Datasets tab's `!=` filter.
        { id: legacySeed.id, evaluationMethod: 'dataset' },
      ]);

      // The score has to be visible to the endpoint the per-score columns are
      // built from, or the column assertions in the columns spec compare two
      // empty lists.
      const scoreNames = await backendClient.listExperimentFeedbackScoreNames(project.id);
      if (scoreNames.length !== 1 || scoreNames[0] !== feedbackScoreName) {
        throw new Error(
          `[experimentsTabSplit fixture] project ${project.id} reports experiment feedback ` +
            `score names [${scoreNames.join(', ')}], expected exactly ['${feedbackScoreName}']`,
        );
      }

      // ---- the two single-method projects ----
      const suiteOnlyProject = await sdkClient.python.createProject({
        name: `${testNamespace}-suiteonly-proj`,
      });
      projectIds.push(suiteOnlyProject.id);
      const suiteOnlySource = await createSuiteSource(
        suiteOnlyProject.name,
        `${testNamespace}-suiteonly-suite`,
      );
      const suiteOnlySeed = await createExperiment({
        projectName: suiteOnlyProject.name,
        name: `${testNamespace}-suiteonly-exp`,
        source: suiteOnlySource,
        evaluationMethod: 'evaluation_suite',
      });
      const suiteOnlyRows = await requireStored(backendClient, suiteOnlyProject.id, [
        { id: suiteOnlySeed.id, evaluationMethod: 'evaluation_suite' },
      ]);

      const datasetOnlyProject = await sdkClient.python.createProject({
        name: `${testNamespace}-dsonly-proj`,
      });
      projectIds.push(datasetOnlyProject.id);
      const datasetOnlySource = await createDatasetSource(
        datasetOnlyProject.name,
        `${testNamespace}-dsonly-ds`,
      );
      const datasetOnlySeed = await createExperiment({
        projectName: datasetOnlyProject.name,
        name: `${testNamespace}-dsonly-exp`,
        source: datasetOnlySource,
        evaluationMethod: 'dataset',
      });
      const datasetOnlyRows = await requireStored(backendClient, datasetOnlyProject.id, [
        { id: datasetOnlySeed.id, evaluationMethod: 'dataset' },
      ]);

      const toRef = (
        rows: Map<string, ProjectExperimentRef>,
        seed: { id: string; name: string; source: { id: string; name: string } },
      ): TabSplitExperimentRef => {
        const row = rows.get(seed.id)!;
        return {
          id: seed.id,
          name: seed.name,
          // Non-null by construction: `requireStored` threw above for either.
          evaluationMethod: row.evaluationMethod!,
          datasetVersionName: row.datasetVersionName!,
          datasetId: seed.source.id,
          datasetName: seed.source.name,
        };
      };

      const suiteExperiment = toRef(mixedRows, suiteSeed);
      const datasetExperiment = toRef(mixedRows, datasetSeed);
      const legacyExperiment = toRef(mixedRows, legacySeed);
      const suiteOnlyExperiment = toRef(suiteOnlyRows, suiteOnlySeed);
      const datasetOnlyExperiment = toRef(datasetOnlyRows, datasetOnlySeed);

      const ref: ExperimentsTabSplitRef = {
        mixed: {
          projectId: project.id,
          projectName: project.name,
          experiments: [suiteExperiment, datasetExperiment, legacyExperiment],
          suiteExperiment,
          datasetExperiment,
          legacyExperiment,
          feedbackScoreName,
        },
        suiteOnly: {
          projectId: suiteOnlyProject.id,
          projectName: suiteOnlyProject.name,
          experiments: [suiteOnlyExperiment],
          experiment: suiteOnlyExperiment,
        },
        datasetOnly: {
          projectId: datasetOnlyProject.id,
          projectName: datasetOnlyProject.name,
          experiments: [datasetOnlyExperiment],
          experiment: datasetOnlyExperiment,
        },
      };

      await testInfo.attach('opik.experimentsTabSplit', {
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
            console.warn(`[experimentsTabSplit fixture] delete warning for ${what}:`, err);
          }
        };
        // Experiments before the datasets they reference, datasets before the
        // projects they belong to. Reversed so a partial seed tears down in the
        // order it was built.
        for (const id of [...experimentIds].reverse()) {
          await safe(`experiment ${id}`, () => backendClient.deleteExperiment(id));
        }
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

export { expect } from './ollie-table-widget.fixture';
