import { test, expect } from '@e2e/fixtures';

/**
 * A multi-prompt `POST /v1/private/experiments/execute` naming a project the
 * workspace has never seen (opik#9161).
 *
 * `resolveProjectIdOrCreate` used to run on the reactive path, where N prompt
 * variants resolve the same `project_name` concurrently: one won, the rest hit
 * the unique-key clash, and a reactive `handleProjectCreationError` was
 * supposed to recover the winner. In practice the caller saw a 409 "Project
 * already exists" and lost every variant but one. opik#9161 moved the resolve
 * onto the blocking `getOrCreate` and DELETED that conflict handler.
 *
 * Nothing in the estate covered this, and it says so out loud:
 * `test-suite-experiment-name.spec.ts` seeds the project first, with a comment
 * naming this exact 409 as "a pre-existing defect" it is deliberately dodging.
 * So the behaviour is uncovered BY DESIGN over there, which means no automated
 * test would ever report it fixed — or re-broken. This spec is the one that
 * drives the undodged path, and it is deliberately a sibling of that file
 * rather than an edit to it: that spec's subject is naming, and widening it
 * would make a naming failure and a project-resolution failure the same red.
 *
 * SCOPE — API only, and that is the right level rather than a compromise.
 * `createExperiments` resolves the project and writes every experiment strictly
 * before the first LLM call, so the whole contract (how many experiments, which
 * project, how many projects) is observable with no provider key, no paid model
 * and no model output anywhere in the assertion. The Playground's suite-mode
 * form and Optimization Studio's new-run form share
 * `resolveProjectIdOrCreate`, but what they contribute is the line that BUILDS
 * the request — which `test-suite-experiment-name.spec.ts` already drives
 * through the Playground. The server-side resolve is what changed here.
 */
test.describe(
  'Test Suites — multi-prompt execute into a project that does not exist yet',
  { tag: ['@t2-cuj', '@area:test-suites'] },
  () => {
    const MODEL = 'gpt-4o-mini';
    const MESSAGES = [{ role: 'user', content: '{{question}}' }];
    /**
     * Six variants, not two.
     *
     * The bug was a race between the variants resolving one project name, so
     * the number of concurrent resolvers is the thing under test. Two variants
     * reproduce it only sometimes; six put five losers against one winner on
     * every run, which is what makes a reverted fix fail here reliably rather
     * than occasionally.
     */
    const PROMPT_COUNT = 6;

    test(
      'six prompt variants naming one unseen project all succeed, in a single auto-created project',
      { tag: ['@cap:test-suites.run-suite-playground'] },
      async ({
        testSuite,
        backendClient,
        registerExperimentCleanup,
        registerProjectCleanup,
        testNamespace,
      }) => {
        // `testSuite` seeds its own project; this is a DIFFERENT name, one no
        // project uses, which is the entire premise.
        const freshProjectName = `${testNamespace}-unseen`;
        const prompts = Array.from({ length: PROMPT_COUNT }, () => ({
          model: MODEL,
          messages: MESSAGES,
        }));

        // Registered before the write that would create it: the test can only
        // hand over a NAME, and only the backend knows the id. Registering
        // after the assertions would skip cleanup on exactly the failing runs
        // that leave the most behind.
        registerProjectCleanup(freshProjectName);

        await test.step('No project holds that name yet', async () => {
          // The premise, asserted rather than assumed. If a previous run had
          // left the project behind, every assertion below would still pass —
          // against the already-exists path, which is not the one opik#9161
          // changed.
          const existing = await backendClient.listProjectsWithPrefix(freshProjectName);
          expect(
            existing.map((p) => p.name),
            'the workspace has never seen this project name',
          ).toEqual([]);
        });

        const firstRun = await test.step('Execute six unnamed variants against the suite', async () => {
          const result = await backendClient.executeExperiments({
            datasetName: testSuite.name,
            datasetId: testSuite.id,
            projectName: freshProjectName,
            prompts,
          });

          // The 409 is the regression, so the status and the message are both
          // part of the assertion: a reverted fix answers 409 "Project already
          // exists" here, and a reader of the failure should see which.
          expect(result.status, `execute answered ${result.status}: ${result.message}`).toBe(202);
          // Exactly six, not "at least one". The defect's signature is that
          // some variants survive and the rest are lost, which an "at least"
          // assertion cannot see.
          expect(result.experiments).toHaveLength(PROMPT_COUNT);
          expect(result.experiments.map((e) => e.promptIndex)).toEqual([0, 1, 2, 3, 4, 5]);

          for (const entry of result.experiments) {
            registerExperimentCleanup(entry.experimentId, `execute-prompt-${entry.promptIndex}`);
          }
          return result.experiments;
        });

        const projectId = await test.step('Exactly one project was created for the name', async () => {
          // The half a per-experiment check cannot see. The deleted conflict
          // handler existed because concurrent resolvers can each create a row;
          // a backend that created six projects and pointed one experiment at
          // each would answer 202 with six experiments and look perfectly
          // healthy from the response alone.
          const created = await backendClient.listProjectsWithPrefix(freshProjectName);
          const exact = created.filter((p) => p.name === freshProjectName);
          expect(exact, `projects named "${freshProjectName}"`).toHaveLength(1);
          return exact[0].id;
        });

        await test.step('All six experiments point at that one project', async () => {
          // Polled: the experiments are written asynchronously after the 202,
          // so a read taken immediately can race the insert. The assertion is
          // on the whole set at once — six identical ids — rather than
          // per-experiment, because the failure worth catching is a SPLIT, and
          // a diff of six values names it directly.
          await expect(async () => {
            const stored = await Promise.all(
              firstRun.map((entry) => backendClient.getExperiment(entry.experimentId)),
            );
            expect(
              stored.map((e) => e.projectId),
              'every variant resolved the same project',
            ).toEqual(firstRun.map(() => projectId));
            expect(
              stored.map((e) => e.datasetId),
              'every variant ran against the seeded suite',
            ).toEqual(firstRun.map(() => testSuite.id));
          }).toPass({ timeout: 30_000, intervals: [250, 500, 1_000, 2_000] });
        });

        await test.step('Re-running into the now-existing project resolves it rather than clashing', async () => {
          // The other branch of `getOrCreate`, and not a formality: the fix
          // made the resolve read-then-create, so a second run exercises the
          // read while the first exercised the create. A fix that only handled
          // the create would pass everything above and 409 here.
          const result = await backendClient.executeExperiments({
            datasetName: testSuite.name,
            datasetId: testSuite.id,
            projectName: freshProjectName,
            prompts,
          });
          expect(result.status, `re-run answered ${result.status}: ${result.message}`).toBe(202);
          expect(result.experiments).toHaveLength(PROMPT_COUNT);
          for (const entry of result.experiments) {
            registerExperimentCleanup(entry.experimentId, `re-run-prompt-${entry.promptIndex}`);
          }

          const created = await backendClient.listProjectsWithPrefix(freshProjectName);
          expect(
            created.filter((p) => p.name === freshProjectName).map((p) => p.id),
            'the re-run reused the project rather than creating a second',
          ).toEqual([projectId]);

          await expect(async () => {
            const stored = await Promise.all(
              result.experiments.map((entry) => backendClient.getExperiment(entry.experimentId)),
            );
            expect(
              stored.map((e) => e.projectId),
              'the re-run’s variants resolved the same project',
            ).toEqual(result.experiments.map(() => projectId));
          }).toPass({ timeout: 30_000, intervals: [250, 500, 1_000, 2_000] });
        });

        await test.step('The suite holds all twelve experiments and nothing else', async () => {
          // A closed set: the suite is fixture-seeded and untouched by anything
          // else in the run, so its full experiment list is assertable. This is
          // what rules out the other direction of the defect — an execute that
          // fanned out an EXTRA experiment while recovering from a clash.
          const forSuite = await backendClient.listExperimentsForDataset(testSuite.id);
          expect(forSuite, 'experiments against the seeded suite').toHaveLength(PROMPT_COUNT * 2);
        });
      },
    );
  },
);
