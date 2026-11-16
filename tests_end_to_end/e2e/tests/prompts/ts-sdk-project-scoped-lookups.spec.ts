import { test, expect } from '@e2e/fixtures';

/**
 * The TypeScript SDK's by-name lookups stay inside the client's own project
 * (opik#9485, OPIK-8934).
 *
 * Prompt and dataset names are unique per PROJECT, so a lookup that names no
 * project does not mean "anywhere" — it means the Default Project. The TS client
 * had two calls that left the project out and papered over the consequence:
 *
 * - `searchPrompts()` listed the whole workspace and resolved each prompt's
 *   version with no project at all.
 * - `getDatasets()` resolved the project name to an id and, if that lookup threw
 *   for ANY reason, fell back to listing the workspace unfiltered.
 *
 * 9485 scopes both to one project — the given one, else the active context, else
 * the client's — and narrows the fallback: an empty result only for a project
 * that genuinely does not exist (a 404), with anything else rethrown so an
 * outage is not reported as a project with nothing in it.
 *
 * ## Why this is API-level, and why it is worth a spec at all
 *
 * This is a breaking change on a PUBLIC SDK surface that the estate never calls.
 * The only TypeScript specs that exist assert OpenAI/Gemini token usage on
 * traces; `searchPrompts`, `getDatasets` and `Prompt.getVersion` are called by
 * no test, and the release report says so in as many words. There is no UI in
 * the path — these are library calls — so driving a browser would observe
 * nothing the SDK does.
 *
 * The failure mode is what makes it worth pinning: both directions are SILENT.
 * A caller that starts reading the whole workspace gets more rows and no error;
 * a caller that starts reading nothing gets an empty array and no error. Neither
 * raises, so nothing but an assertion about WHICH rows came back can tell.
 *
 * ## Tagging
 *
 * `@cap:prompts.sdk-seeded-visible` is claimed for the prompt half only. The
 * dataset half is asserted in the same test — it is the same client, the same
 * seed and the same claim about project resolution, and splitting it would mean
 * building the two clients twice — but it is NOT tagged, because a `@cap:` has
 * to sit under the spec's own `@area:` and the dataset claim belongs to
 * `datasets`. Under-claiming is the safe direction: an untagged assertion still
 * fails the build when it breaks, it just does not count as coverage.
 */

/**
 * A project name no project holds.
 *
 * Inside the run namespace so it is obvious where it came from, and suffixed
 * rather than random so a failure message names the same string every time. The
 * test asserts it really is absent before relying on it.
 */
const MISSING_PROJECT_SUFFIX = '-no-such-project';

test.describe(
  'Prompts — the TypeScript SDK scopes by-name lookups to its project',
  { tag: ['@t2-cuj', '@area:prompts'] },
  () => {
    test(
      'each client sees only its own project\'s prompt and dataset',
      { tag: ['@cap:prompts.sdk-seeded-visible'] },
      async ({ projectScopedTsClients, backendClient }) => {
        // QA: failing on staging for 2.2.144, and NOT a product defect — the
        // behaviour asserted below is what opik#9485 shipped and what the
        // release pass verified by hand against a TypeScript SDK built from
        // `sdks/typescript`. What fails here is the ESTATE: `tests_end_to_end/
        // e2e/package-lock.json` pins `opik` at 2.2.131, thirteen versions
        // before the fix, so these calls run the pre-9485 client that lists the
        // whole workspace. Bumping that dependency is a cross-cutting change to
        // the suite's shared REST client (`core/backend/client.ts` is built on
        // the same package), so it is a reviewer's decision rather than
        // something to slip in beside a new spec — see the QA PR description.
        // Remove test.fail() once the estate's `opik` is >= 2.2.144;
        // Playwright reports it as an unexpected pass when it is.
        test.fail();

        const { a, b } = projectScopedTsClients;

        await test.step('The two projects really do hold one prompt each', async () => {
          // The discriminating precondition, asserted through the REST client
          // rather than the SDK under test: if the seed had put both prompts in
          // one project, the scoped read below would return one prompt and look
          // correct while proving nothing.
          for (const scoped of [a, b]) {
            const id = await backendClient.findPromptIdByName(scoped.promptName, scoped.projectId);
            expect(
              id,
              `${scoped.promptName} exists in ${scoped.projectName}, so a scoped search can find it`,
            ).not.toBeNull();
          }
          const crossed = await backendClient.findPromptIdByName(a.promptName, b.projectId);
          expect(
            crossed,
            "project A's prompt is not in project B — otherwise neither search below discriminates",
          ).toBeNull();
        });

        await test.step('searchPrompts returns the calling client\'s prompt and no other', async () => {
          // The whole answer, not just "mine is in it". A client that listed the
          // workspace would also return the other project's prompt, and that is
          // precisely the regression: `find()`-ing our own name in the result
          // would pass against the broken client too.
          const fromA = await a.client.searchPrompts();
          expect(
            fromA.map((prompt) => prompt.name).sort(),
            `a client on ${a.projectName} searches that project alone`,
          ).toEqual([a.promptName]);

          const fromB = await b.client.searchPrompts();
          expect(
            fromB.map((prompt) => prompt.name).sort(),
            `and a client on ${b.projectName} sees only its own — stated separately because a ` +
              'client that returned its own project for A and the workspace for B would be ' +
              'half-broken, which one direction cannot show',
          ).toEqual([b.promptName]);
        });

        await test.step('getDatasets returns the calling client\'s dataset and no other', async () => {
          // Same claim, other collection. Both are asserted because 9485 fixed
          // them through one shared helper (`findProjectIdByName`) but they
          // reach it from different call sites, so one can regress alone.
          const fromA = await a.client.getDatasets();
          expect(
            fromA.map((dataset) => dataset.name).sort(),
            `a client on ${a.projectName} lists that project's datasets alone, while the ` +
              "workspace also holds project B's",
          ).toEqual([a.datasetName]);

          const fromB = await b.client.getDatasets();
          expect(
            fromB.map((dataset) => dataset.name).sort(),
            `and a client on ${b.projectName} lists only its own`,
          ).toEqual([b.datasetName]);
        });
      },
    );

    test(
      'a project that does not exist has no datasets, rather than all of them',
      { tag: ['@cap:prompts.sdk-seeded-visible'] },
      async ({ projectScopedTsClients, backendClient, testNamespace }) => {
        // QA: failing on staging for 2.2.144 for the same reason as the test
        // above — the estate's pinned `opik` is 2.2.131, which predates
        // opik#9485 and falls back to listing the whole workspace when the
        // project lookup fails. The assertion is the shipped behaviour.
        // Remove test.fail() once the estate's `opik` is >= 2.2.144.
        test.fail();

        const { a } = projectScopedTsClients;
        const missingProject = `${testNamespace}${MISSING_PROJECT_SUFFIX}`;

        await test.step('The named project really is absent', async () => {
          // Asserted, not assumed: a project that happened to exist would make
          // an empty result below mean "exists and is empty", which is a
          // different fact and would pass over the bug.
          expect(
            await backendClient.listProjectsWithPrefix(missingProject),
            'no project holds the name the lookup is about to miss on',
          ).toEqual([]);
        });

        await test.step('getDatasets for it comes back empty', async () => {
          const datasets = await a.client.getDatasets(100, missingProject);
          expect(
            datasets.map((dataset) => dataset.name),
            'a 404 on the project resolves to no datasets — before 9485 this listed the whole ' +
              "workspace under the missing project's name, so a caller silently got every dataset",
          ).toEqual([]);
        });

        await test.step('And the client still works for a project that does exist', async () => {
          // The other side of the same branch: "empty" must be a statement
          // about the missing project, not a client that has stopped reading
          // anything. Without this, a build whose getDatasets always returned
          // [] would satisfy the step above.
          const datasets = await a.client.getDatasets(100, a.projectName);
          expect(
            datasets.map((dataset) => dataset.name).sort(),
            'the same client, pointed at a real project, still finds that project\'s dataset',
          ).toEqual([a.datasetName]);
        });
      },
    );
  },
);
