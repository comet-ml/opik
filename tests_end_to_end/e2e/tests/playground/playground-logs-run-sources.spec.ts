import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PlaygroundLogsSidebarPage } from '@e2e/pom/playground-logs-sidebar.page';
import type { BackendFilter } from '@e2e/core/backend';

/**
 * The Playground logs sidebar lists dataset and test-suite runs as well as
 * free-mode ones, and still excludes SDK `evaluate()` traces (opik#8799).
 *
 * `generateLogsSourceFilter(LOGS_SOURCE.playground)` emits a PAIR of filters —
 * `source != sdk` AND `metadata.created_from = playground` — because a
 * playground run against a dataset or a test suite is logged
 * `source=experiment`, exactly as an SDK `evaluate()` run is. The source clause
 * alone would therefore leak SDK evaluations into a user's playground logs; the
 * `created_from` clause alone would admit an SDK trace that happened to carry
 * that key. Both failure directions are user-visible and silent: too broad
 * shows runs the user never made in the Playground, too narrow hides the ones
 * they did.
 *
 * `playground.playground-logs-sidebar` already read `covered: true`, but the
 * spec behind it — `prompt-playground-traces.spec.ts` — runs a FREE-MODE prompt
 * against a live model and asserts only that its own trace appears. That is the
 * one case the pre-fix filter already handled, so the half opik#8799 changed
 * has no guard. This spec takes the other half, and takes it as a SET: the
 * sidebar's rendered row ids are compared against what the API answers for the
 * sidebar's own filter pair, so a disagreement between the two is a failure
 * rather than something only a human would notice.
 *
 * Deterministic by construction. Every row is seeded over REST, one per
 * (source, created_from) cell — no model is called, no run is launched, and
 * the row set is a pure function of the seed. The one provider key seeded
 * below exists only to keep the Playground's setup modal shut; it points at a
 * refused loopback port and nothing ever calls it. The
 * candidate's flow sketched seeding a dataset and a Custom provider so a real
 * run could produce the rows; that is unnecessary once the rows are written
 * directly, and it would have made the assertion depend on a provider
 * answering.
 */

const SOURCE_NOT_SDK: BackendFilter = {
  field: 'source',
  operator: '!=',
  value: 'sdk',
  type: 'string',
};

const CREATED_FROM_PLAYGROUND: BackendFilter = {
  field: 'metadata',
  operator: '=',
  value: 'playground',
  type: 'dictionary',
  key: 'created_from',
};

/** All the pre-fix filter was: a single `source = playground` equality. */
const SOURCE_IS_PLAYGROUND: BackendFilter = {
  field: 'source',
  operator: '=',
  value: 'playground',
  type: 'string',
};

const sorted = (ids: readonly string[]): string[] => [...ids].sort();

test.describe('Playground logs — which runs are listed', { tag: ['@area:playground'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'the sidebar lists dataset and test-suite runs and excludes SDK evaluate() traces',
    { tag: ['@t2-cuj', '@cap:playground.playground-logs-sidebar'] },
    async ({ project, playgroundLogsTraces, backendClient, providerKeys, testNamespace, page }) => {
      test.setTimeout(180_000);

      const expectedIds = sorted(playgroundLogsTraces.expectedIds);

      const apiAnswer = await test.step("The API's answer for the sidebar's own filter pair", async () => {
        const answer = await backendClient.searchTraceIdsPage({
          projectId: project.id,
          filters: [SOURCE_NOT_SDK, CREATED_FROM_PLAYGROUND],
          size: 200,
        });
        // Population and rows both, so a leaked row cannot hide in an envelope
        // the test never read.
        expect(answer.total).toBe(expectedIds.length);
        expect(sorted(answer.ids)).toEqual(expectedIds);

        // Named from the other side as well: these two are the rows a user
        // would report missing, and the row they would report seeing.
        for (const addedId of playgroundLogsTraces.addedByFixIds) {
          expect(answer.ids, 'a playground run logged source=experiment must be listed')
            .toContain(addedId);
        }
        expect(answer.ids, 'an SDK evaluate() trace must not reach the playground logs')
          .not.toContain(playgroundLogsTraces.sdkEvaluateId);
        expect(answer.ids, 'an SDK trace carrying created_from=playground is still an SDK trace')
          .not.toContain(playgroundLogsTraces.sdkFromPlaygroundId);
        return answer;
      });

      await test.step('Neither half of the pair is dead weight', async () => {
        // Dropping either clause must admit strictly MORE rows. Without this a
        // clause could have been removed entirely and the set above would still
        // match, because the remaining clause happens to cover the same rows
        // for this seed.
        const sourceOnly = await backendClient.searchTraceIdsPage({
          projectId: project.id,
          filters: [SOURCE_NOT_SDK],
          size: 200,
        });
        expect(sourceOnly.total).toBeGreaterThan(apiAnswer.total);
        expect(
          sourceOnly.ids,
          'without the created_from clause an SDK evaluate() trace is admitted',
        ).toContain(playgroundLogsTraces.sdkEvaluateId);

        const createdFromOnly = await backendClient.searchTraceIdsPage({
          projectId: project.id,
          filters: [CREATED_FROM_PLAYGROUND],
          size: 200,
        });
        expect(createdFromOnly.total).toBeGreaterThan(apiAnswer.total);
        expect(
          createdFromOnly.ids,
          'without the source clause an SDK trace claiming created_from=playground is admitted',
        ).toContain(playgroundLogsTraces.sdkFromPlaygroundId);
      });

      await test.step('The pre-fix filter would have hidden the runs this release added', async () => {
        const preFix = await backendClient.searchTraceIdsPage({
          projectId: project.id,
          filters: [SOURCE_IS_PLAYGROUND],
          size: 200,
        });
        expect(preFix.total).toBeLessThan(apiAnswer.total);
        expect(sorted(preFix.ids)).toEqual(sorted(playgroundLogsTraces.preFixIds));
        // The difference is named exactly rather than counted: these are the
        // non-`playground`-sourced runs — a dataset/suite run and an
        // optimization run — that the user makes in the Playground and that the
        // old filter could not see.
        expect(
          sorted(apiAnswer.ids.filter((id) => !preFix.ids.includes(id))),
          'the rows the fix added are the non-playground-sourced playground runs',
        ).toEqual(sorted(playgroundLogsTraces.addedByFixIds));
      });

      await test.step('Seed a provider, only so the Playground mounts past its setup modal', async () => {
        // A precondition rather than a detail, and the reason this step exists
        // at all: with no provider key in the workspace the Playground
        // auto-opens the "Add provider configuration" dialog, whose overlay
        // covers the page — the logs-sidebar button resolves but can never be
        // clicked. Every other spec in this directory establishes a provider
        // first for the same reason. It points at a refused loopback port and
        // is never called: nothing here runs a prompt, and every row under
        // test is seeded over REST.
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-unreachable`,
          modelName: `${testNamespace}-dead-model`,
        });
      });

      await test.step('The sidebar renders exactly the rows that answer describes', async () => {
        const playground = new PlaygroundPage(page, project.id);
        const sidebar = new PlaygroundLogsSidebarPage(page);

        await playground.goto();
        await playground.waitForReady();
        await playground.openLogsPanel();
        await sidebar.waitForOpen();
        await sidebar.waitForTraceRow();

        // Polled rather than read once: the sheet's table mounts its rows as
        // the read lands, so the first settled row count can still be short of
        // the answer. Compared as a SET against the API, which is the claim —
        // "my run is in there" would pass over a sidebar that also listed every
        // SDK evaluation in the project.
        await expect
          .poll(
            async () =>
              sorted(
                (
                  await sidebar.traceRows().evaluateAll((rows) =>
                    rows.map((row) => row.getAttribute('data-row-id') ?? ''),
                  )
                ).filter((id) => id !== ''),
              ),
            { timeout: 30_000, intervals: [500, 1_000, 2_000] },
          )
          .toEqual(expectedIds);

        // And the rows that must stay out, addressed by id, so the set
        // comparison above cannot be satisfied by a row the sidebar rendered
        // without a usable `data-row-id`.
        for (const hiddenId of [
          playgroundLogsTraces.sdkEvaluateId,
          playgroundLogsTraces.sdkFromPlaygroundId,
        ]) {
          await expect(
            sidebar.dialog.locator(`tr[data-row-id="${hiddenId}"]`),
            `${hiddenId} is an SDK trace and must not be listed in the playground logs`,
          ).toHaveCount(0);
        }
      });
    },
  );
});
