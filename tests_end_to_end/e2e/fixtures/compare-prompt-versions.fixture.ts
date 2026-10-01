import { test as baseTest, expect } from './readability-locale-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * Metadata every seeded experiment carries, so the compare Configuration table
 * holds ordinary rows beside the synthetic prompt-version one.
 *
 * Two keys rather than one, and neither containing the words "prompt version":
 * the search assertion ("searching the row's name leaves only that row") is
 * only meaningful if there are other rows for it to exclude, and only sound if
 * none of them match the needle by accident.
 */
export const CONFIG_METADATA_KEYS = ['model', 'run.seed'] as const;

export interface ComparePromptLink {
  /**
   * The prompt VERSION id. This is what `createExperiment({ promptVersionIds })`
   * links against and what the compare row diffs on — a prompt id here links
   * nothing at all.
   */
  versionId: string;
  promptId: string;
  promptName: string;
  /**
   * Exactly the string `formatPromptVersionLabel` renders —
   * `<prompt name> (<version_number>)` — built from the version number the
   * SERVER assigned rather than from the order this fixture wrote in, so a spec
   * comparing labels is comparing against the backend's own answer.
   */
  label: string;
}

export interface ComparePromptExperimentRef {
  id: string;
  name: string;
}

export interface ComparePromptVersionsRef {
  projectId: string;
  projectName: string;
  datasetId: string;
  datasetName: string;

  alphaV1: ComparePromptLink;
  alphaV2: ComparePromptLink;
  betaV1: ComparePromptLink;

  /**
   * The version id of a prompt this fixture created and then DELETED. The link
   * row survives the delete, so an experiment still carries this id while the
   * prompt behind it is gone — the shape the compare cell has to render as the
   * disabled "Deleted prompt" state.
   */
  deletedVersionId: string;
  deletedPromptName: string;

  /** Linked to alpha v1 only. */
  linkedToAlpha: ComparePromptExperimentRef;
  /** Linked to beta v1 only — the proof a column cannot leak its neighbour's version. */
  linkedToBeta: ComparePromptExperimentRef;
  /** Linked to no prompt at all; its cell must read "No value". */
  linkedToNothing: ComparePromptExperimentRef;

  /** Baseline of the diff pair: [alpha v1, beta v1]. */
  pairBaseline: ComparePromptExperimentRef;
  /** The same two versions as the baseline — the row must read as identical. */
  pairIdentical: ComparePromptExperimentRef;
  /** [alpha v2, beta v1] — one version apart from the baseline. */
  pairDiverged: ComparePromptExperimentRef;

  /** Linked to the deleted prompt's version. */
  orphaned: ComparePromptExperimentRef;
  /** Linked to a live prompt, alongside `orphaned`, so the deleted state is visibly the exception. */
  liveNeighbour: ComparePromptExperimentRef;
}

export interface ComparePromptVersionsFixtures {
  comparePromptVersions: ComparePromptVersionsRef;
}

/**
 * One dataset, three prompts and eight experiments linked to them in the
 * combinations the compare Configuration tab's "Prompt version (linked)" row
 * has to tell apart (OPIK-7964, opik#8468).
 *
 * Everything is seeded over REST rather than by running an evaluation: what
 * these specs assert is which version label lands in which column, which is
 * decided by the experiment→prompt-version link alone and is independent of
 * whether any item was ever scored. That keeps the fixture deterministic and
 * fast enough to seed eight experiments.
 *
 * The deleted prompt is deleted HERE rather than in the test body. It is a
 * precondition — the behaviour under test is how the cell renders a link whose
 * prompt is gone, not the deletion itself — and doing it in setup means the
 * experiments linked to it are torn down by this fixture whatever the test did.
 *
 * Teardown deletes the experiments, the prompts and the dataset explicitly.
 * None of them cascade with the project, and `global-teardown`'s run-prefix
 * sweep knows about experiments and datasets but not prompts at all.
 *
 * Prompt ids come from `findPromptIdByName`, never from the create response:
 * `createPromptVersion` answers with the VERSION id, and
 * `DELETE /v1/private/prompts/{id}` 404s for one of those, so teardown
 * registered with it would leak in silence.
 */
export const test = baseTest.extend<ComparePromptVersionsFixtures>({
  comparePromptVersions: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-ds`;
    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'compare configuration prompt versions',
      items: [{ question: 'seeded', answer: 'seeded' }] as unknown as Array<
        Record<string, unknown>
      >,
    });

    const commitPrompt = async (
      promptName: string,
      template: string,
    ): Promise<ComparePromptLink> => {
      const version = await backendClient.createPromptVersion({
        name: promptName,
        template,
        projectId: project.id,
        changeDescription: 'seeded for the compare Configuration tab',
      });
      const promptId = await backendClient.findPromptIdByName(promptName, project.id);
      if (!promptId) {
        throw new Error(
          `[comparePromptVersions fixture] no prompt id resolved for '${promptName}'`,
        );
      }
      // Asserted rather than defaulted: the label the page renders is
      // `<name> (<version_number>)`, and a backend that stopped sending the
      // number would render a bare name — which is a real answer this fixture
      // must not paper over by inventing "v1".
      if (!version.versionNumber) {
        throw new Error(
          `[comparePromptVersions fixture] prompt version ${version.id} of '${promptName}' carried no version_number`,
        );
      }
      return {
        versionId: version.id,
        promptId,
        promptName,
        label: `${promptName} (${version.versionNumber})`,
      };
    };

    const alphaName = `${testNamespace}-prompt-alpha`;
    const betaName = `${testNamespace}-prompt-beta`;
    const doomedName = `${testNamespace}-prompt-doomed`;

    const alphaV1 = await commitPrompt(alphaName, 'Alpha, first cut: {{question}}');
    const alphaV2 = await commitPrompt(alphaName, 'Alpha, second cut: {{question}}');
    const betaV1 = await commitPrompt(betaName, 'Beta: {{question}}');
    const doomedV1 = await commitPrompt(doomedName, 'Doomed: {{question}}');

    if (alphaV1.versionId === alphaV2.versionId) {
      throw new Error(
        `[comparePromptVersions fixture] alpha v1 and v2 share a version id (${alphaV1.versionId}); the diff row would compare equal for the wrong reason`,
      );
    }

    const experimentIds: string[] = [];
    const seedExperiment = async (
      suffix: string,
      promptVersionIds: string[],
      metadata: Record<string, unknown>,
    ): Promise<ComparePromptExperimentRef> => {
      const name = `${testNamespace}-exp-${suffix}`;
      const id = uuid7();
      await backendClient.createExperiment({
        id,
        name,
        datasetName,
        projectName: project.name,
        metadata,
        ...(promptVersionIds.length ? { promptVersionIds } : {}),
      });
      experimentIds.push(id);
      return { id, name };
    };

    const linkedToAlpha = await seedExperiment('alpha', [alphaV1.versionId], {
      model: 'seeded-model-alpha',
      run: { seed: 1 },
    });
    const linkedToBeta = await seedExperiment('beta', [betaV1.versionId], {
      model: 'seeded-model-beta',
      run: { seed: 2 },
    });
    const linkedToNothing = await seedExperiment('unlinked', [], {
      model: 'seeded-model-unlinked',
      run: { seed: 3 },
    });

    // The diff trio's metadata is deliberately half-identical: `run.seed` is
    // the same across all three and `model` differs on every one. That gives
    // "Show differences only" one row it must hide for a reason that has
    // nothing to do with prompts, and one it must keep — so a toggle that
    // emptied the whole table, or one that hid nothing, both fail, and
    // "the prompt row disappeared" cannot be satisfied by either.
    const pairBaseline = await seedExperiment(
      'pair-baseline',
      [alphaV1.versionId, betaV1.versionId],
      { model: 'seeded-model-baseline', run: { seed: 4 } },
    );
    const pairIdentical = await seedExperiment(
      'pair-identical',
      [alphaV1.versionId, betaV1.versionId],
      { model: 'seeded-model-identical', run: { seed: 4 } },
    );
    const pairDiverged = await seedExperiment(
      'pair-diverged',
      [alphaV2.versionId, betaV1.versionId],
      { model: 'seeded-model-diverged', run: { seed: 4 } },
    );

    const orphaned = await seedExperiment('orphaned', [doomedV1.versionId], {
      model: 'seeded-model-orphaned',
      run: { seed: 5 },
    });
    const liveNeighbour = await seedExperiment('live-neighbour', [betaV1.versionId], {
      model: 'seeded-model-live',
      run: { seed: 6 },
    });

    // The precondition the deleted-state assertions rest on, made true before
    // the browser opens. A prompt that failed to delete would leave every
    // "Deleted prompt" assertion asserting the live state instead.
    await backendClient.deletePrompt(doomedV1.promptId);
    if (await backendClient.promptExistsByName(doomedName, project.id)) {
      throw new Error(
        `[comparePromptVersions fixture] prompt '${doomedName}' survived its delete; the deleted-state assertions would be vacuous`,
      );
    }

    const ref: ComparePromptVersionsRef = {
      projectId: project.id,
      projectName: project.name,
      datasetId: dataset.id,
      datasetName,
      alphaV1,
      alphaV2,
      betaV1,
      deletedVersionId: doomedV1.versionId,
      deletedPromptName: doomedName,
      linkedToAlpha,
      linkedToBeta,
      linkedToNothing,
      pairBaseline,
      pairIdentical,
      pairDiverged,
      orphaned,
      liveNeighbour,
    };
    await testInfo.attach('opik.comparePromptVersions', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[comparePromptVersions fixture] delete warning for ${what}:`, err);
        }
      };
      for (const id of experimentIds) {
        await safe(`experiment ${id}`, () => backendClient.deleteExperiment(id));
      }
      // `doomedV1` is already gone; the other two are not, and nothing else
      // reclaims a prompt.
      await safe(`prompt ${alphaName}`, () => backendClient.deletePrompt(alphaV1.promptId));
      await safe(`prompt ${betaName}`, () => backendClient.deletePrompt(betaV1.promptId));
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect };
