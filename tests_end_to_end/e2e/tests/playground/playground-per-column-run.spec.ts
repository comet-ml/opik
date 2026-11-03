import { test, expect } from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import type { BackendClient } from '@e2e/core/backend';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * OPIK-3268 — a variant's own Run button runs THAT variant against the loaded
 * dataset, and the experiment it creates carries that variant's letter.
 *
 * `playground-experiment-name.spec.ts` covers the same naming contract for the
 * header's Run-all, and cannot stand in for this: Run-all fans out over every
 * prompt id, so a suffix that reached the wrong variant still produces the same
 * SET of names, and the per-column path (`runSingle` → one prompt id) is a
 * different call entirely. The failure this catches is silent by construction —
 * a dropped or mispaired name is a server-generated name in the Experiments
 * list, not an error — which is why every assertion below is on the name and
 * not on the run having happened.
 *
 * Deliberately driven through the UI and read back through the API. The typed
 * name never leaves the frontend as typed: `buildExperimentName` composes the
 * `_b` from the variant's index in the prompt list, so the binding under test —
 * WHICH variant a suffix lands on — is only observable by clicking one
 * variant's button and reading what the resulting `/experiments/execute`
 * request carried.
 *
 * The provider is a `custom-llm` one that fails every call. The model has to be
 * selectable for Run to enable, but its output is not under test: `execute`
 * creates the named experiment before any item reaches the provider, so it
 * persists whatever the provider answers. That is what keeps this
 * deterministic and free of a provider key.
 */
test.describe('Playground — per-column run', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  interface ExecuteRequest {
    dataset_name?: string;
    prompts?: {
      experiment_name?: string;
      /**
       * The variant's own messages: the only field on the request that says
       * which variant ran, and so the only way to tell "B's suffix reached B"
       * from "B's suffix reached whichever variant ran".
       */
      messages?: unknown[];
    }[];
  }

  /**
   * Collect every `/experiments/execute` request the page makes, from before
   * the run starts. A dataset run creates its experiments server-side from
   * this one request, one per prompt entry, under the names it carries.
   */
  const collectExecuteRequests = (page: Page): ExecuteRequest[] => {
    const executed: ExecuteRequest[] = [];
    page.on('request', (request) => {
      if (request.method() !== 'POST') return;
      if (!/\/v1\/private\/experiments\/execute$/.test(new URL(request.url()).pathname)) return;
      executed.push((request.postDataJSON() ?? {}) as ExecuteRequest);
    });
    return executed;
  };

  /**
   * List the dataset's experiments, registering each id for teardown the first
   * time it appears.
   *
   * Registering from inside the poll rather than after it is the point. The ids
   * do not exist until the run creates them, and the assertion this feeds is
   * precisely the one that fails when the run created something unexpected — a
   * server-named experiment carries no run prefix, so `global-teardown.ts`'s
   * sweep can never reach it. Registering only after the assertion passes would
   * leak exactly the rows a failure left behind.
   */
  const listerRegistering = (
    backendClient: BackendClient,
    datasetId: string,
    registerExperimentCleanup: (id: string, name: string) => void,
  ) => {
    const seen = new Set<string>();
    return async () => {
      const found = await backendClient.listExperimentsForDataset(datasetId);
      for (const experiment of found) {
        if (seen.has(experiment.id)) continue;
        seen.add(experiment.id);
        registerExperimentCleanup(experiment.id, experiment.name);
      }
      return found;
    };
  };

  test(
    "Running one variant's column creates only that variant's experiment, under its own suffix",
    { tag: ['@cap:playground.run-against-dataset'] },
    async ({
      project,
      dataset,
      providerKeys,
      backendClient,
      registerExperimentCleanup,
      testNamespace,
      page,
    }) => {
      test.setTimeout(240_000);

      const runName = `${testNamespace}-col`;
      const nameA = `${runName}_a`;
      const nameB = `${runName}_b`;
      // A literal only variant B's prompt carries, so B's prompt entry is
      // identifiable as B's from its body alone.
      const variantBMarker = 'second-variant-marker';
      const modelDisplayName = 'unreachable-model';

      await test.step('Seed a selectable provider that refuses every connection', async () => {
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-provider`,
          modelName: modelDisplayName,
        });
      });

      const playground = new PlaygroundPage(page, project.id);
      const executed = collectExecuteRequests(page);
      const listExperiments = listerRegistering(
        backendClient,
        dataset.id,
        registerExperimentCleanup,
      );

      await test.step('Open the Playground on the seeded dataset with two variants', async () => {
        // Before goto: the recorder is an init script, and the toast this test
        // reads is raised about a second after the run starts and gone five
        // seconds later.
        await playground.startRecordingToasts();
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: '{{input}}',
          modelDisplayName,
        });
        await playground.duplicateVariant(0);
        await playground.configureVariant(1, { userPrompt: `${variantBMarker} {{input}}` });
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
        await playground.waitForRunReady({ expectedRows: 3 });
      });

      await test.step('Naming the run previews the names a full run would create', async () => {
        await expect(playground.experimentNamePreview()).toBeHidden();
        await playground.setExperimentName(runName);
        // The preview is a whole-run preview — it lists what Run-all would
        // create — and it is computed by the same `buildExperimentName` the
        // per-column run below uses. Asserted here so that the single name that
        // POST carries can be compared against what the user was promised.
        await expect(playground.experimentNamePreview()).toContainText(`Creates: ${nameA}`);
        await expect(playground.experimentNamePreview()).toContainText('+1 more');
      });

      await test.step("Run variant B's column only, and wait for it to announce", async () => {
        await playground.clickVariantRun(1);
        // The recorded toast is the run's own completion signal, so waiting on
        // it is what makes the "exactly one request" assertion below a closed
        // statement about a finished run rather than a snapshot of one in
        // progress.
        await expect
          .poll(() => playground.recordedRunCompletionToasts(), {
            timeout: 180_000,
            intervals: [500, 1000, 2000],
          })
          .toHaveLength(1);
      });

      // Before the assertions, not after. Everything below can fail, and the
      // run has already written its experiments by the time the toast fires —
      // so registering only in the final step (as this spec used to) left
      // exactly the rows a failing run created unswept. `global-teardown.ts`'s
      // prefix sweep rescues the ones carrying the run prefix, but not a
      // server-named one, which is the very extra this test hunts for.
      await test.step('Register whatever the run created, before asserting on it', async () => {
        await listExperiments();
      });

      await test.step('Exactly one experiment was requested, carrying B\'s suffix and B\'s prompt', async () => {
        // Exactly one, not "at least one": a per-column run that also created
        // the other variant's experiment is the same class of defect as one
        // that named it wrongly, and is invisible to a lookup by name.
        expect(executed).toHaveLength(1);
        expect(executed[0].dataset_name).toBe(dataset.name);
        expect(executed[0].prompts).toHaveLength(1);
        const [prompt] = executed[0].prompts!;
        expect(prompt.experiment_name).toBe(nameB);
        // The pairing, not just the string: a build that composed the suffix
        // from the clicked position instead of the variant's own index would
        // send `_a` here, and a build that ran the wrong column would send B's
        // name with A's prompt.
        expect(JSON.stringify(prompt.messages ?? [])).toContain(variantBMarker);
      });

      await test.step('The toast names B and only B', async () => {
        const [toast] = await playground.recordedRunCompletionToasts();
        expect(toast).toContain('1 experiment created');
        expect(toast).toContain(nameB);
        // The assertion that separates this from a Run-all: naming `_a` too
        // would mean the other column ran as well.
        expect(toast).not.toContain(nameA);
      });

      await test.step("Only B's column produced output", async () => {
        // Three cells, not six: the dataset has three items and the page has
        // two variants, so a run that leaked into column A would double this.
        // A refused connection still writes its failure into the cell as the
        // cell's value, which is what makes B's three countable at all.
        await expect
          .poll(() => playground.countCompletedOutputCells(), {
            timeout: 60_000,
            intervals: [500, 1000, 2000],
          })
          .toBe(3);
      });

      await test.step('The field advances itself so a re-run cannot collide', async () => {
        // A per-column run consumes the typed name exactly as a full run does —
        // re-running with the field untouched would otherwise post `{runName}_b`
        // a second time, and two experiments under one name are
        // indistinguishable in the list.
        expect(await playground.readExperimentName()).toBe(`${runName}_02`);
      });

      await test.step('The dataset carries that one experiment and nothing else', async () => {
        // The full set for this dataset, not a `find()` of the expected name: a
        // run that also wrote a second, auto-named experiment would satisfy a
        // lookup-by-name and is exactly the regression worth failing on. The
        // dataset is fixture-seeded, so nothing else writes to it.
        await expect
          .poll(async () => (await listExperiments()).map((e) => e.name), {
            timeout: 60_000,
            intervals: [500, 1000, 2000, 5000],
          })
          .toEqual([nameB]);
      });
    },
  );
});
