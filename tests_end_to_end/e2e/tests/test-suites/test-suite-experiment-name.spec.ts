import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * OPIK-3268 — a Playground run against a test suite can name each prompt
 * variant's experiment, instead of every run landing under a generated name
 * like `nosy_hamster_5229`.
 *
 * Driven at `POST /v1/private/experiments/execute`, the write path the
 * Playground's test-suite mode posts to, with the resulting names read back
 * through the Experiments page a user would actually look at. The API is the
 * right level for the pairing assertion specifically: `createExperiments` runs
 * to completion — and the names are decided — strictly before any LLM call, so
 * the whole contract (which name reached which variant, what a blank one does,
 * what an omitted one does) is observable with no provider key, no paid model
 * and no model output anywhere in the assertion. The Playground UI that drives
 * this endpoint is covered from the other side by
 * `playground/playground-experiment-name.spec.ts`.
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
        const forSuite = (await backendClient.listExperimentsWithPrefix(''))
          .filter((e) => e.datasetId === testSuite.id)
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
        const forSuite = (await backendClient.listExperimentsWithPrefix('')).filter(
          (e) => e.datasetId === testSuite.id,
        );
        expect(forSuite).toHaveLength(0);
      });
    },
  );
});
