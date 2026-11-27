import { test as baseTest } from './mixed-suite-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface PromptMixedSourceExperimentRef {
  id: string;
  name: string;
  /** The dataset or test suite the experiment was recorded against. */
  sourceId: string;
  sourceName: string;
}

export interface PromptMixedSourceExperimentsRef {
  projectId: string;
  projectName: string;
  promptId: string;
  promptName: string;
  promptVersionId: string;
  /** The experiment on a plain dataset — `evaluation_method: 'dataset'`. */
  datasetExperiment: PromptMixedSourceExperimentRef;
  /** The experiment on a test suite — `evaluation_method: 'evaluation_suite'`. */
  suiteExperiment: PromptMixedSourceExperimentRef;
}

export interface PromptMixedSourceExperimentsFixtures {
  promptMixedSourceExperiments: PromptMixedSourceExperimentsRef;
}

/**
 * One prompt version with two experiments run from it: one over a plain
 * dataset, one over a test suite.
 *
 * This is the only shape that puts a dataset experiment and a test-suite
 * experiment in ONE selectable list. The project Experiments page cannot: its
 * two tabs each filter on `evaluation_method` (`experimentsTabs.ts`), so the
 * Datasets tab only ever holds non-suite rows and the Test suites tab only
 * suite rows. The prompt detail page's Experiments tab queries by prompt id
 * with no such filter, which makes it the one host where the Compare guard's
 * third tooltip branch — "item source", for a selection mixing the two types —
 * is reachable at all (opik#9747).
 *
 * The link is to the prompt VERSION, not the prompt: the tab reads the
 * experiment ↔ prompt-version join table, so an experiment seeded without it
 * lands in the project's Experiments page and leaves this tab empty.
 *
 * The prompt is project-scoped (`projectId` on the version write) because the
 * tab is reached at `/projects/{projectId}/prompts/{promptId}`.
 *
 * Seeded over REST rather than by running anything: the panel reads only the
 * selected rows' `dataset_id` and `evaluation_method`, so a real evaluation
 * would add minutes and LLM cost and change nothing it looks at.
 *
 * Teardown deletes the experiments, then the prompt, then both sources. None
 * of the four cascades with the project, and the prompt id comes from
 * `findPromptIdByName` rather than from the create response — that answers
 * with the VERSION id, which `DELETE /v1/private/prompts/{id}` rejects with a
 * 404, so teardown registered with it would leak in silence.
 */
export const test = baseTest.extend<PromptMixedSourceExperimentsFixtures>({
  promptMixedSourceExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const promptName = `${testNamespace}-mixsrc-prompt`;
    const datasetName = `${testNamespace}-mixsrc-ds`;
    const suiteName = `${testNamespace}-mixsrc-suite`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'mixed item sources on a prompt: the plain dataset',
      items: [{ question: 'dataset question', answer: 'dataset answer' }] as unknown as Array<
        Record<string, unknown>
      >,
    });
    const suite = await sdkClient.python.createTestSuite({
      project_name: project.name,
      name: suiteName,
      description: 'mixed item sources on a prompt: the test suite',
      items: [{ data: { question: 'suite question' } }],
    });

    const version = await backendClient.createPromptVersion({
      name: promptName,
      template: 'Answer {{question}}.',
      projectId: project.id,
      changeDescription: 'seeded for the mixed-item-source Compare guard',
    });
    const promptId = await backendClient.findPromptIdByName(promptName, project.id);
    if (!promptId) {
      throw new Error(
        `[promptMixedSourceExperiments fixture] no prompt id resolved for '${promptName}'`,
      );
    }

    const datasetExperiment: PromptMixedSourceExperimentRef = {
      id: uuid7(),
      name: `${testNamespace}-mixsrc-dsexp`,
      sourceId: dataset.id,
      sourceName: datasetName,
    };
    const suiteExperiment: PromptMixedSourceExperimentRef = {
      id: uuid7(),
      name: `${testNamespace}-mixsrc-suexp`,
      sourceId: suite.id,
      sourceName: suiteName,
    };

    try {
      await backendClient.createExperiment({
        id: datasetExperiment.id,
        name: datasetExperiment.name,
        datasetName,
        projectName: project.name,
        promptVersionIds: [version.id],
        evaluationMethod: 'dataset',
      });
      await backendClient.createExperiment({
        id: suiteExperiment.id,
        name: suiteExperiment.name,
        datasetName: suiteName,
        projectName: project.name,
        promptVersionIds: [version.id],
        evaluationMethod: 'evaluation_suite',
      });

      // Prove the seed can discriminate before any browser opens. Three facts
      // the spec's whole premise rests on, each of which fails silently:
      //
      //  - the two methods really differ, which is what makes the tooltip say
      //    "item source" instead of naming one type;
      //  - the two sources really are different rows, which is what
      //    `hasMixedItemSources` reads (it compares `dataset_id`, not the
      //    method);
      //  - both experiments carry the prompt-version link, without which the
      //    tab renders an empty table and nothing can be selected.
      const expectedMethods: Array<[PromptMixedSourceExperimentRef, string]> = [
        [datasetExperiment, 'dataset'],
        [suiteExperiment, 'evaluation_suite'],
      ];
      for (const [experiment, expected] of expectedMethods) {
        const rendered = await backendClient.getExperimentRenderFields(experiment.id);
        if (rendered.evaluationMethod !== expected) {
          throw new Error(
            `[promptMixedSourceExperiments fixture] experiment ${experiment.name} stored ` +
              `evaluation_method=${rendered.evaluationMethod}, expected '${expected}' — the ` +
              'selection would not mix item sources at all',
          );
        }
        const linked = await backendClient.getExperimentPromptVersionsRaw(experiment.id);
        const linkedIds = (linked.promptVersions ?? []).map((v) => String(v.id));
        if (!linkedIds.includes(version.id)) {
          throw new Error(
            `[promptMixedSourceExperiments fixture] experiment ${experiment.name} links ` +
              `prompt versions ${JSON.stringify(linkedIds)}, which does not include ` +
              `${version.id} — the prompt's Experiments tab would not list it`,
          );
        }
      }
      if (datasetExperiment.sourceId === suiteExperiment.sourceId) {
        throw new Error(
          '[promptMixedSourceExperiments fixture] both experiments share source ' +
            `${datasetExperiment.sourceId}; hasMixedItemSources compares dataset_id and ` +
            'would read the selection as homogeneous',
        );
      }

      const ref: PromptMixedSourceExperimentsRef = {
        projectId: project.id,
        projectName: project.name,
        promptId,
        promptName,
        promptVersionId: version.id,
        datasetExperiment,
        suiteExperiment,
      };

      await testInfo.attach('opik.promptMixedSourceExperiments', {
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
            console.warn(
              `[promptMixedSourceExperiments fixture] delete warning for ${what}:`,
              err,
            );
          }
        };
        for (const experiment of [datasetExperiment, suiteExperiment]) {
          await safe(`experiment ${experiment.name}`, () =>
            backendClient.deleteExperiment(experiment.id),
          );
        }
        await safe(`prompt ${promptName}`, () => backendClient.deletePrompt(promptId));
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        await safe(`test suite ${suiteName}`, () => backendClient.deleteDataset(suite.id));
      }
    }
  },
});

export { expect } from './mixed-suite-experiments.fixture';
