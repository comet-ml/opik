import { test, expect } from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import type { BackendClient } from '@e2e/core/backend';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * OPIK-3268 — the Playground's output-actions strip names the experiments a run
 * creates, and leaving it empty keeps today's server-generated names.
 *
 * One name for the whole run, not one per variant: the user types a name, and
 * `buildExperimentName` appends the variant's own letter (`_a`, `_b`, …), so a
 * two-variant run creates two experiments under one typed name. After the run
 * the field advances itself to `{name}_02` so a re-run does not collide.
 *
 * `playground-smoke.spec.ts` already covers running against a dataset, but it
 * matches the experiment it created by `datasetId`, explicitly "since the name
 * is auto-generated server-side". That was true before this change and is the
 * reason a dropped name, a suffix attached to the wrong variant, or a blank
 * name forwarded verbatim all still pass it. This spec joins the two halves:
 * the name a user typed, and the names that run's experiments end up carrying.
 *
 * Deliberately driven through the UI. The typed name never leaves the frontend
 * as typed — `getExperimentNameForPrompt` composes it per prompt, and the run's
 * one `/experiments/execute` request carries it as each prompt's
 * `experiment_name`, or leaves it out — and neither is observable from a
 * hand-built request.
 *
 * The provider is a `custom-llm` one that fails every call. The model has to be
 * selectable for Run to enable, but its output is not under test: `execute`
 * creates the named experiments before any item reaches the provider, so they
 * persist whatever the provider answers. That is what keeps this deterministic
 * and free of a provider key.
 */
test.describe('Playground — experiment naming', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  interface ExecuteRequest {
    dataset_name?: string;
    prompts?: {
      experiment_name?: string;
      /**
       * The variant's own messages: the only field on the request that says
       * which variant a prompt entry is, and so the only way to assert that a
       * suffix reached the variant it belongs to rather than *some* variant.
       */
      messages?: unknown[];
    }[];
  }

  /**
   * List the dataset's experiments, registering each id for teardown the first
   * time it appears.
   *
   * Registering from inside the poll rather than after it is the point. The ids
   * do not exist until the run creates them, and the assertions these polls
   * feed are precisely the ones that fail when the run created something
   * unexpected — a server-named experiment carries no run prefix, so
   * `global-teardown.ts`'s sweep can never reach it. Registering only after the
   * assertion passes would leak exactly the rows a failure left behind.
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

  test(
    'One typed name fans out to one suffixed experiment per variant',
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

      const runName = `${testNamespace}-run`;
      const nameA = `${runName}_a`;
      const nameB = `${runName}_b`;
      // A literal only variant B's prompt carries, so that B's experiment POST
      // is identifiable as B's no matter which order the two POSTs land in.
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

      await test.step('Open the Playground on the seeded dataset with two variants', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: '{{input}}',
          modelDisplayName,
        });
        await playground.duplicateVariant(0);
        // Give B a prompt of its own. Two byte-identical variants post two
        // indistinguishable prompt entries, and then only their order separates
        // them. With a marker the suffix-to-variant pairing below is
        // assertable, and a build that swapped `_a` and `_b` between prompt ids
        // fails instead of passing on a set comparison.
        await playground.configureVariant(1, { userPrompt: `${variantBMarker} {{input}}` });
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
        await playground.waitForRunReady({ expectedRows: 3 });
      });

      await test.step('Naming the run previews the names both variants will get', async () => {
        await expect(playground.experimentNamePreview()).toBeHidden();
        await playground.setExperimentName(runName);
        // The preview is the user's only pre-run sight of the generated suffix,
        // and it is computed by the same `buildExperimentName` the run uses —
        // so a preview that disagrees with what is posted below is a real
        // defect, not a cosmetic one.
        await expect(playground.experimentNamePreview()).toContainText(`Creates: ${nameA}`);
        await expect(playground.experimentNamePreview()).toContainText('+1 more');
      });

      await test.step('Run, and verify each prompt carries its own variant\'s suffix', async () => {
        await playground.clickReRun();
        await expect
          .poll(() => executed.length, { timeout: 30_000, intervals: [500, 1000, 2000] })
          .toBeGreaterThanOrEqual(1);
        expect(executed).toHaveLength(1);
        const [request] = executed;
        expect(request.dataset_name).toBe(dataset.name);
        expect(request.prompts).toHaveLength(2);
        // Keyed by name rather than compared as two sorted lists: the pairing
        // is what matters. A build that gave B's variant the `_a` suffix is
        // what `promptIds.indexOf(promptId)` exists to prevent, and a set
        // comparison cannot see it — the sorted names match either way. The
        // messages each entry carries are what tell them apart.
        const promptByName = new Map(
          (request.prompts ?? []).map((p) => [p.experiment_name, JSON.stringify(p.messages ?? [])]),
        );
        expect([...promptByName.keys()].sort()).toEqual([nameA, nameB].sort());
        expect(promptByName.get(nameB)).toContain(variantBMarker);
        expect(promptByName.get(nameA)).not.toContain(variantBMarker);
      });

      await test.step('The completion toast names both experiments', async () => {
        // Asserted here, before any backend polling: the toast auto-dismisses
        // after Radix's 5s default, and `waitForRunSettled` returns on the same
        // React commit that raises it, so this is the only point in the test
        // where it is reliably on screen.
        await playground.waitForRunSettled();
        const toast = playground.completionToast();
        await expect(toast).toContainText('2 experiments created');
        await expect(toast).toContainText(nameA);
        await expect(toast).toContainText(nameB);
      });

      await test.step('The field advances itself so a re-run cannot collide', async () => {
        // The whole reason the suggestion exists: re-running with the field
        // untouched would otherwise post `{runName}_a` a second time, and two
        // experiments under one name are indistinguishable in the list.
        expect(await playground.readExperimentName()).toBe(`${runName}_02`);
        await expect(playground.experimentNamePreview()).toContainText(
          `Creates: ${runName}_02_a`,
        );
      });

      const experiments = await test.step('The dataset carries exactly those two experiments', async () => {
        const listExperiments = listerRegistering(
          backendClient,
          dataset.id,
          registerExperimentCleanup,
        );

        // The full set for this dataset, not a `find()` of the two expected
        // names: a run that also wrote a third, auto-named experiment would
        // satisfy a lookup-by-name and is exactly the regression worth failing
        // on. The dataset is fixture-seeded, so nothing else writes to it.
        await expect
          .poll(async () => (await listExperiments()).map((e) => e.name).sort(), {
            timeout: 60_000,
            intervals: [500, 1000, 2000, 5000],
          })
          .toEqual([nameA, nameB].sort());

        return listExperiments();
      });

      await test.step('Both names render on the project Experiments page', async () => {
        const experimentsPage = new ExperimentsPage(page);
        await experimentsPage.goto(project.id);
        await experimentsPage.waitForReady();
        for (const experiment of experiments) {
          await experimentsPage.expectExperimentNameInList(experiment.id, experiment.name);
        }
      });
    },
  );

  test(
    'An unset name is posted as no name at all, and the experiment is auto-named',
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

      const modelDisplayName = 'unreachable-model';

      await test.step('Seed a selectable provider that refuses every connection', async () => {
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-provider`,
          modelName: modelDisplayName,
        });
      });

      const playground = new PlaygroundPage(page, project.id);
      const executed = collectExecuteRequests(page);

      await test.step('Open the Playground on the seeded dataset', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: '{{input}}',
          modelDisplayName,
        });
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
        await playground.waitForRunReady({ expectedRows: 3 });
      });

      await test.step('The field offers a placeholder and previews nothing', async () => {
        // Read back before running: the run below asserts what an untouched
        // field does, which is only meaningful if the field really is empty
        // rather than pre-filled with something the preview is hiding.
        expect(await playground.readExperimentName()).toBe('');
        await expect(playground.experimentNameEditor()).toContainText('Auto-generated name');
        await expect(playground.experimentNamePreview()).toBeHidden();
      });

      await test.step('Run, and verify the request omits the name entirely', async () => {
        await playground.clickReRun();
        await expect
          .poll(() => executed.length, { timeout: 30_000, intervals: [500, 1000, 2000] })
          .toBeGreaterThanOrEqual(1);
        expect(executed).toHaveLength(1);
        expect(executed[0].prompts).toHaveLength(1);
        // `undefined`, not `''`: the backend rejects a blank name with 422, so
        // a frontend that forwarded an empty string would fail the user's run
        // outright rather than falling back to a generated name. This is the
        // assertion that makes OPIK-3268 additive rather than a behaviour
        // change for everyone who never types a name.
        expect(executed[0].prompts![0].experiment_name).toBeUndefined();
      });

      await test.step('The field stays empty — an unset name has nothing to advance', async () => {
        // Before the backend poll below, for the same reason as the sibling
        // test: the toast lives for Radix's 5s default.
        await playground.waitForRunSettled();
        await expect(playground.completionToast()).toContainText('1 experiment created');
        expect(await playground.readExperimentName()).toBe('');
        await expect(playground.experimentNamePreview()).toBeHidden();
      });

      await test.step('The experiment landed under a generated name', async () => {
        // This is the one experiment in the spec that the run-prefix sweep in
        // `global-teardown.ts` can never reach: the whole point of the test is
        // that the server named it, so its name carries no run prefix to match
        // on. `listerRegistering` is what keeps it from outliving a failed run.
        const listExperiments = listerRegistering(
          backendClient,
          dataset.id,
          registerExperimentCleanup,
        );

        await expect
          .poll(async () => (await listExperiments()).length, {
            timeout: 60_000,
            intervals: [500, 1000, 2000, 5000],
          })
          .toBe(1);

        const [experiment] = await listExperiments();
        expect(experiment.name.trim()).not.toBe('');
        // The generated names are `<adjective>_<noun>_<digits>`; anything
        // carrying the run namespace would mean a name was composed and sent.
        expect(experiment.name).not.toContain(testNamespace);
      });
    },
  );
});
