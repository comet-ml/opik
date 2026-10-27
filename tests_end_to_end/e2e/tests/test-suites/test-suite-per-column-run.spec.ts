import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * OPIK-3268 — running ONE variant's column against a loaded test suite puts
 * that variant's composed name on that variant's entry of the execute request.
 *
 * Suite mode is a different code path from dataset mode, not a skin on it:
 * `runSingleViaBackend` sends the run to the server as a single
 * `POST /v1/private/experiments/execute` carrying every variant it is running,
 * where dataset mode's `runSingleViaFrontend` posts each experiment separately
 * from the browser. So `playground/playground-per-column-run.spec.ts` cannot
 * stand in for this, and neither can `test-suite-experiment-name.spec.ts`,
 * which covers the Run-all shape of the same request.
 *
 * The binding worth asserting here is positional: with one column running, the
 * request must carry exactly one prompt entry, and that entry must carry the
 * suffix belonging to the variant that was clicked — `_b` for the second
 * variant, even though it is the request's first and only entry. A build that
 * composed the suffix from the request position instead of the variant's own
 * index sends `_a` here and is otherwise indistinguishable.
 *
 * The provider is a `custom-llm` one whose base URL refuses every connection.
 * The model has to be selectable for Run to enable, but its output is not under
 * test: `createExperiments` runs to completion — and the names are decided —
 * strictly before any LLM call.
 */
test.describe(
  'Test Suites — per-column run from the Playground',
  { tag: ['@t2-cuj', '@area:test-suites'] },
  () => {
    test(
      "Running one variant's column names that variant's entry of the execute request",
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
        test.setTimeout(240_000);

        const runName = `${testNamespace}-col`;
        const nameA = `${runName}_a`;
        const nameB = `${runName}_b`;
        // A literal only variant B's prompt carries, so the one entry in the
        // request body is identifiable as B's from the body alone — otherwise
        // "the entry carried `_b`" and "the entry was B's" are two claims and
        // only the first is asserted.
        const variantBMarker = 'second-variant-marker';
        const modelDisplayName = 'unreachable-model';

        /** The execute request bodies the page sent, in the shape that matters here. */
        const executed: Array<{
          prompts?: Array<{
            experiment_name?: string;
            messages?: Array<{ content?: string }>;
          }>;
        }> = [];
        page.on('request', (request) => {
          if (request.method() !== 'POST') return;
          if (!/\/v1\/private\/experiments\/execute$/.test(new URL(request.url()).pathname)) return;
          executed.push((request.postDataJSON() ?? {}) as (typeof executed)[number]);
        });

        await test.step('Seed a selectable provider that refuses every connection', async () => {
          await providerKeys.createUnreachable({
            providerName: `${testNamespace}-provider`,
            modelName: modelDisplayName,
          });
        });

        const playground = new PlaygroundPage(page, project.id);

        /**
         * List the suite's experiments, registering each id for teardown the
         * first time it appears. The ids do not exist until the run creates
         * them, so a fixture cannot know them up front.
         */
        const seen = new Set<string>();
        const listSuiteExperiments = async () => {
          const found = await backendClient.listExperimentsForDataset(testSuite.id);
          for (const experiment of found) {
            if (seen.has(experiment.id)) continue;
            seen.add(experiment.id);
            registerExperimentCleanup(experiment.id, experiment.name);
          }
          return found;
        };

        await test.step('Open the Playground on the seeded suite with two variants', async () => {
          // Before goto: the recorder is an init script, and the toast this
          // test reads is dismissed by Radix five seconds after it appears.
          await playground.startRecordingToasts();
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            userPrompt: '{{question}}',
            modelDisplayName,
          });
          await playground.duplicateLastVariant();
          await playground.configureVariant(1, {
            userPrompt: `${variantBMarker} {{question}}`,
          });
          await playground.clickRunExperiment();
          await playground.selectRunExperimentSource({
            mode: 'test_suite',
            entityName: testSuite.name,
          });
          await expect(playground.loadedSourcePill()).toBeVisible();
          await playground.waitForRunReady({ expectedRows: 3 });
        });

        await test.step('Name the run, then run variant B\'s column only', async () => {
          await playground.setExperimentName(runName);
          await expect(playground.experimentNamePreview()).toContainText(`Creates: ${nameA}`);
          await playground.clickVariantRun(1);
          await expect
            .poll(() => executed.length, { timeout: 180_000, intervals: [500, 1000, 2000] })
            .toBeGreaterThanOrEqual(1);
        });

        // Before the assertions, not after. The execute request has already
        // created the experiment by the time it returns, and everything below
        // can fail — registering only in the final step (as this spec used to)
        // left exactly the rows a failing run created unswept. Observed for
        // real: when the toast step below timed out, the experiment survived
        // the test and only `global-teardown.ts`'s prefix sweep caught it,
        // which would not have reached a server-named one.
        await test.step('Register whatever the run created, before asserting on it', async () => {
          await listSuiteExperiments();
        });

        await test.step('One request, one prompt entry, carrying B\'s suffix on B\'s prompt', async () => {
          // Asserted on the request rather than only on what was stored: suite
          // mode sends every variant it runs in ONE body, so "which entry
          // carries which name" is the whole binding and it is only visible
          // here. Exactly one request and exactly one entry — a per-column run
          // that also submitted the other column is the defect next door.
          expect(executed).toHaveLength(1);
          expect(executed[0].prompts?.map((p) => p.experiment_name)).toEqual([nameB]);
          const messages = executed[0].prompts?.[0].messages ?? [];
          expect(messages.map((m) => m.content ?? '').join('\n')).toContain(variantBMarker);
        });

        await test.step('The run announces one experiment, naming B and not A', async () => {
          const toasts = await test.step('wait for the completion toast', async () => {
            await expect
              .poll(() => playground.recordedRunCompletionToasts(), {
                timeout: 180_000,
                intervals: [500, 1000, 2000],
              })
              .toHaveLength(1);
            return playground.recordedRunCompletionToasts();
          });
          expect(toasts[0]).toContain('1 experiment created');
          expect(toasts[0]).toContain(nameB);
          expect(toasts[0]).not.toContain(nameA);
        });

        await test.step('The field advances itself so a re-run cannot collide', async () => {
          expect(await playground.readExperimentName()).toBe(`${runName}_02`);
        });

        await test.step('The suite holds exactly that one experiment, under that name', async () => {
          // The full set for this suite, not a `find()` of the expected name: a
          // run that also wrote a second, auto-named experiment would satisfy a
          // lookup-by-name and is exactly the regression worth failing on. The
          // suite is fixture-seeded, so nothing else writes to it. Each id is
          // registered for teardown from inside the poll, so an extra the run
          // should not have written is swept even — especially — when it is the
          // thing that fails this assertion.
          await expect
            .poll(async () => (await listSuiteExperiments()).map((e) => e.name), {
              timeout: 60_000,
              intervals: [500, 1000, 2000, 5000],
            })
            .toEqual([nameB]);
        });
      },
    );
  },
);
