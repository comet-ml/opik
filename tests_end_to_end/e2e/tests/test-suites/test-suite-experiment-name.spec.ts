import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * OPIK-3268 — a Playground run against a test suite can name each prompt
 * variant's experiment, instead of every run landing under a generated name
 * like `nosy_hamster_5229`.
 *
 * Three of the four tests drive `POST /v1/private/experiments/execute`, the
 * write path the Playground's test-suite mode posts to, with the resulting
 * names read back through the Experiments page a user would actually look at.
 * The API is the right level for the server-side half specifically:
 * `createExperiments` runs to completion — and the names are decided — strictly
 * before any LLM call, so that whole contract (which name reached which
 * variant, what a blank one does, what an omitted one does) is observable with
 * no provider key, no paid model and no model output anywhere in the assertion.
 *
 * The fourth drives the Playground itself, because the frontend half of suite
 * mode is its own line of code: `useRunExperimentExecution` puts
 * `experiment_name` on each variant of the execute request, and
 * `playground/playground-experiment-name.spec.ts` cannot stand in for it — that
 * spec runs the Playground in DATASET mode, which posts experiments one at a
 * time through `createLogPlaygroundProcessor` and never reaches this endpoint.
 * Without the UI test, deleting the suite-mode line leaves the estate green.
 *
 * `test-suites-smoke.spec.ts` already runs a suite from the Playground, but it
 * only counts output rows, so nothing in the estate joins the name that was
 * sent to the name that was stored.
 */
test.describe('Test Suites — experiment naming from a suite run', { tag: ['@t2-cuj', '@area:test-suites'] }, () => {
  const MODEL = 'gpt-4o-mini';
  const MESSAGES = [{ role: 'user', content: '{{question}}' }];

  test(
    'experiment_name names each variant\'s experiment, and the Experiments page shows it',
    { tag: ['@cap:test-suites.run-suite-playground'] },
    async ({ project, testSuite, backendClient, registerExperimentCleanup, testNamespace, page }) => {
      const nameA = `${testNamespace}-variant-a`;
      const nameB = `${testNamespace}-variant-b`;

      const experiments = await test.step('Execute two named prompt variants against the suite', async () => {
        // `project` is a fixture, so the project already exists here. That is
        // load-bearing and not incidental: a multi-prompt execute against a
        // project name the workspace has never seen races itself creating it
        // and answers 409 (a pre-existing defect, not OPIK-3268's — it predates
        // the `.name(...)` line this spec covers). Seeding the project first
        // keeps this test on the behaviour it is about.
        const result = await backendClient.executeExperiments({
          datasetName: testSuite.name,
          datasetId: testSuite.id,
          projectName: project.name,
          prompts: [
            { model: MODEL, messages: MESSAGES, experimentName: nameA },
            { model: MODEL, messages: MESSAGES, experimentName: nameB },
          ],
        });

        expect(result.status, `execute answered ${result.status}: ${result.message}`).toBe(202);
        // Exactly two, not "at least two": an execute that fanned out an extra
        // experiment is the same class of bug as one that dropped a name.
        expect(result.experiments).toHaveLength(2);
        expect(result.experiments.map((e) => e.promptIndex)).toEqual([0, 1]);

        for (const entry of result.experiments) {
          registerExperimentCleanup(entry.experimentId, `execute-prompt-${entry.promptIndex}`);
        }
        return result.experiments;
      });

      await test.step('Each experiment is stored under the name sent for ITS variant', async () => {
        // Compared as a pair, in one assertion, rather than two independent
        // equality checks: the failure mode worth catching is the names being
        // swapped between variants, and asserting the whole [0], [1] mapping at
        // once names both sides of that in a single diff.
        await expect(async () => {
          const stored = await Promise.all(
            experiments.map((entry) => backendClient.getExperiment(entry.experimentId)),
          );
          expect(stored.map((e) => e.name)).toEqual([nameA, nameB]);
          expect(stored.map((e) => e.datasetId)).toEqual([testSuite.id, testSuite.id]);
        }).toPass({ timeout: 15_000, intervals: [250, 500, 1000] });
      });

      await test.step('The suite has those two experiments and nothing else', async () => {
        // The pairing check above would still pass if the execute had ALSO
        // written a third, auto-named experiment against the same suite. The
        // suite is fixture-seeded and untouched by anything else in the run, so
        // its full experiment list is a closed set this test can assert on.
        const forSuite = (await backendClient.listExperimentsForDataset(testSuite.id))
          .map((e) => e.name)
          .sort();
        expect(forSuite).toEqual([nameA, nameB].sort());
      });

      await test.step('Both names render on the project Experiments page', async () => {
        const experimentsPage = new ExperimentsPage(page);
        await experimentsPage.goto(project.id);
        await experimentsPage.waitForReady();
        await experimentsPage.expectExperimentNameInList(experiments[0].experimentId, nameA);
        await experimentsPage.expectExperimentNameInList(experiments[1].experimentId, nameB);
      });
    },
  );

  test(
    'omitting experiment_name falls back to a distinct generated name per variant',
    { tag: ['@cap:test-suites.run-suite-playground'] },
    async ({ project, testSuite, backendClient, registerExperimentCleanup }) => {
      const experiments = await test.step('Execute two unnamed prompt variants', async () => {
        const result = await backendClient.executeExperiments({
          datasetName: testSuite.name,
          datasetId: testSuite.id,
          projectName: project.name,
          prompts: [
            { model: MODEL, messages: MESSAGES },
            { model: MODEL, messages: MESSAGES },
          ],
        });
        expect(result.status, `execute answered ${result.status}: ${result.message}`).toBe(202);
        expect(result.experiments).toHaveLength(2);
        for (const entry of result.experiments) {
          registerExperimentCleanup(entry.experimentId, `execute-unnamed-${entry.promptIndex}`);
        }
        return result.experiments;
      });

      await test.step('Each got its own generated name', async () => {
        await expect(async () => {
          const stored = await Promise.all(
            experiments.map((entry) => backendClient.getExperiment(entry.experimentId)),
          );
          for (const experiment of stored) {
            expect(experiment.name.trim()).not.toBe('');
          }
          // Distinct, not merely present: making the name optional is only safe
          // if the fallback still produces one name per variant. Two variants
          // sharing a generated name would be indistinguishable in the list.
          expect(new Set(stored.map((e) => e.name)).size).toBe(2);
        }).toPass({ timeout: 15_000, intervals: [250, 500, 1000] });
      });
    },
  );

  test(
    'a whitespace-only experiment_name is rejected instead of silently auto-named',
    { tag: ['@cap:test-suites.run-suite-playground'] },
    async ({ project, testSuite, backendClient }) => {
      const result = await test.step('Execute one variant named with spaces only', async () => {
        return backendClient.executeExperiments({
          datasetName: testSuite.name,
          datasetId: testSuite.id,
          projectName: project.name,
          prompts: [{ model: MODEL, messages: MESSAGES, experimentName: '   ' }],
        });
      });

      await test.step('The request is rejected, naming the offending field', async () => {
        expect(result.status).toBe(422);
        expect(result.message).toMatch(/experimentName/);
        expect(result.message).toMatch(/must not be blank/i);
        // Nothing was created, so there is nothing to clean up — asserted
        // rather than assumed, since a partial fan-out that rejected one prompt
        // after creating another would leak an experiment this test never sees.
        expect(result.experiments).toHaveLength(0);
      });

      await test.step('No experiment landed against the suite', async () => {
        const forSuite = await backendClient.listExperimentsForDataset(testSuite.id);
        expect(forSuite).toHaveLength(0);
      });
    },
  );

  test(
    'a name typed into the Playground reaches the suite run\'s execute request',
    { tag: ['@cap:test-suites.run-suite-playground'] },
    async ({
      project,
      testSuite,
      providerKeys,
      backendClient,
      registerExperimentCleanup,
      testNamespace,
      page,
    }) => {
      test.setTimeout(180_000);

      const experimentName = `${testNamespace}-suite-ui`;
      const modelDisplayName = 'unreachable-model';

      /** The execute request bodies the page sent, in the shape that matters here. */
      const executed: Array<{ prompts?: Array<{ experiment_name?: string }> }> = [];
      page.on('request', (request) => {
        if (request.method() !== 'POST') return;
        if (!/\/v1\/private\/experiments\/execute$/.test(new URL(request.url()).pathname)) return;
        executed.push((request.postDataJSON() ?? {}) as { prompts?: Array<{ experiment_name?: string }> });
      });

      await test.step('Seed a selectable provider that refuses every connection', async () => {
        // The model has to be pickable for Run to enable, but nothing here
        // depends on its output: the experiments are named and created before
        // the first completion is attempted, so an unreachable provider keeps
        // this deterministic and free of a provider key.
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-provider`,
          modelName: modelDisplayName,
        });
      });

      const playground = new PlaygroundPage(page, project.id);

      await test.step('Open the Playground on the seeded suite', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: '{{question}}',
          modelDisplayName,
        });
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({
          mode: 'test_suite',
          entityName: testSuite.name,
        });
        await expect(playground.loadedSourcePill()).toBeVisible();
      });

      await test.step('Name the variant and re-run', async () => {
        await playground.setExperimentName(0, experimentName);
        await playground.clickReRun();
        await expect
          .poll(() => executed.length, { timeout: 120_000, intervals: [500, 1000, 2000] })
          .toBeGreaterThanOrEqual(1);
      });

      await test.step('The request carried the typed name on that variant', async () => {
        // Asserted on the request rather than only on what was stored: suite
        // mode sends every variant in ONE body, so the position of the name
        // inside `prompts` is the whole binding, and it is only visible here.
        expect(executed).toHaveLength(1);
        expect(executed[0].prompts?.map((p) => p.experiment_name)).toEqual([experimentName]);
      });

      await test.step('The suite holds exactly that one experiment, under that name', async () => {
        const seen = new Set<string>();
        await expect
          .poll(
            async () => {
              const found = await backendClient.listExperimentsForDataset(testSuite.id);
              for (const experiment of found) {
                if (seen.has(experiment.id)) continue;
                seen.add(experiment.id);
                // Registered from inside the poll: the run creates the id, and
                // an extra experiment the run should not have written is
                // exactly what fails the assertion below — it has to be swept
                // even, especially, when that happens.
                registerExperimentCleanup(experiment.id, experiment.name);
              }
              return found.map((e) => e.name);
            },
            { timeout: 60_000, intervals: [500, 1000, 2000, 5000] },
          )
          .toEqual([experimentName]);
      });
    },
  );
});
