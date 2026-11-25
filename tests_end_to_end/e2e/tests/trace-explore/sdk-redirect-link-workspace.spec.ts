import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import {
  expectedTraceRedirectLocation,
  getTraceRedirect,
  getTraceRedirectWithoutSession,
} from '@e2e/core/session-redirect';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The link the SDK prints after a run resolves the workspace and opens that
 * trace (opik#9742 / OPIK-9217).
 *
 * Every `opik.Opik` run prints one of these — "Started logging traces to the
 * ... project at .../v1/session/redirect/projects?trace_id=...&path=..." — and
 * it is the URL people paste to each other. No spec in the estate reads
 * `/v1/session/redirect/*` at all.
 *
 * What 9742 changed is where the WORKSPACE in that URL comes from. The endpoint
 * takes an optional `workspace_name`; when it is absent the backend now looks
 * the workspace up through the configured `reactService.url` instead of the
 * previous source. So the regression it can cause is not a 500 — it is a
 * perfectly well-formed URL whose workspace segment belongs to somebody else,
 * which nobody notices until they follow it and see another team's project or a
 * "private project" wall. That is why every assertion here is on the WHOLE
 * `Location` string and not on the trace id being somewhere inside it.
 *
 * The differential is what makes the no-param answer a finding rather than a
 * coincidence: a deliberately WRONG `workspace_name` is echoed into the
 * `Location` verbatim, so the endpoint demonstrably does not sanitise or
 * re-derive what it was given — which means the identical, correct answer it
 * gives with NO param was derived by the lookup 9742 moved, and not copied from
 * the request. Neither half proves that alone.
 *
 * Scoped to the TRACE endpoint only, deliberately. The other three
 * (`datasets`, `experiments`, `optimizations`) answer a correct `Location`
 * here too, but following it in a browser lands on "This is a private project"
 * on this workspace: the frontend's legacy-route redirect strips the `/opik`
 * basepath with an unbounded `pathname.replace(basepath, "")`, which eats the
 * `opik` inside the workspace name `opik-testing` — a known, documented hazard
 * in the frontend's own tests (`RecentActivitySection.test.tsx` names it, and
 * `parseExperimentReturnHref.ts` works around it). That is pre-existing, is not
 * 9742 (this release ships zero frontend files), reproduces on the plain
 * `/{ws}/datasets` route, and only bites a workspace whose name starts with
 * `opik`. Asserting the landing of those three would make this spec fail
 * forever on this workspace for a reason that has nothing to do with the
 * endpoint — so they are left out rather than pinned to today's broken
 * behaviour.
 */

/** `workspace_name` that cannot exist, for the echo-back differential. */
const BOGUS_WORKSPACE = 'cuj-not-a-real-workspace';

test.describe(
  'SDK redirect links — workspace resolution — CUJ',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'the trace redirect resolves the workspace itself, echoes one it is given, and does not take it from the caller’s session',
      { tag: ['@cap:traces.sdk-redirect-link'] },
      async ({
        project,
        backendClient,
        testNamespace,
        registerProjectTracesCleanup,
        envConfig,
        page,
      }) => {
        // `deleteProject` does not cascade to traces, and global-teardown's
        // run-prefix sweep does not know about them. Registered before the
        // write so a seed that failed part-way is still swept.
        registerProjectTracesCleanup(project.id);

        const traceId = uuid7();
        const traceName = `${testNamespace}-redirect-trace`;

        await test.step('Seed one trace through the REST API', async () => {
          await backendClient.createTraceWithSource({
            id: traceId,
            projectName: project.name,
            name: traceName,
            source: 'sdk',
            input: { seeded: true },
            output: { seeded: true },
            endTime: new Date(),
          });
        });

        await test.step('Wait for the trace to be readable by id', async () => {
          // The endpoint 404s on a trace it cannot read, and the staging
          // exploration saw a by-id read answer 404 within a second of its own
          // 201. Without this wait the first redirect call races ingestion and
          // reports 404 — which reads exactly like the finding this spec is
          // for.
          await expect
            .poll(async () => (await backendClient.getTracePayload(traceId))?.name ?? null, {
              message: `the trace ${traceName} is readable by id`,
              timeout: 180_000,
              intervals: [1_000, 2_000, 5_000],
            })
            .toBe(traceName);
        });

        const expected = expectedTraceRedirectLocation({
          projectId: project.id,
          traceId,
        });

        await test.step('With NO workspace_name, the redirect names this workspace and this trace', async () => {
          const result = await getTraceRedirect(page.request, { traceId });
          expect(
            result.status,
            `the redirect answers 303 (body: ${result.body})`,
          ).toBe(303);
          // The whole string. A segment-wise assertion would pass on the exact
          // failure 9742 could introduce — the right project and trace under
          // the wrong workspace.
          expect(result.location, 'the Location is the trace page in this workspace').toBe(
            expected,
          );
        });

        await test.step('With workspace_name supplied, the answer is identical', async () => {
          const result = await getTraceRedirect(page.request, {
            traceId,
            workspaceName: envConfig.workspace,
          });
          expect(result.status, `the redirect answers 303 (body: ${result.body})`).toBe(303);
          expect(
            result.location,
            'supplying the workspace this session is in changes nothing',
          ).toBe(expected);
        });

        await test.step('A wrong workspace_name is echoed verbatim — so the no-param answer was a lookup', async () => {
          const result = await getTraceRedirect(page.request, {
            traceId,
            workspaceName: BOGUS_WORKSPACE,
          });
          expect(result.status, `the redirect answers 303 (body: ${result.body})`).toBe(303);
          expect(
            result.location,
            'the supplied workspace is passed through untouched',
          ).toBe(
            expectedTraceRedirectLocation({
              projectId: project.id,
              traceId,
              workspaceName: BOGUS_WORKSPACE,
            }),
          );
          // Stated as its own assertion so a failure says which of the two
          // things went wrong: the endpoint sanitising the param (and so
          // answering `expected` here) would make the no-param step above
          // meaningless, and that is worth its own line in the report.
          expect(
            result.location,
            'the endpoint did not re-derive the workspace over the one it was given',
          ).not.toBe(expected);
        });

        await test.step('An unknown trace id is 404, not a redirect into nowhere', async () => {
          const result = await getTraceRedirect(page.request, { traceId: uuid7() });
          expect(result.status, `an unknown trace id is refused (body: ${result.body})`).toBe(404);
          expect(result.location, 'a refused lookup carries no Location').toBeNull();
        });

        await test.step('Omitting the required path param is 400, not a redirect', async () => {
          const result = await getTraceRedirect(page.request, { traceId, omitPath: true });
          expect(result.status, `a missing path param is refused (body: ${result.body})`).toBe(400);
          expect(result.location, 'a refused request carries no Location').toBeNull();
        });

        await test.step('A caller with no session cookie meets this deployment’s auth wall', async () => {
          // The claim is not "strangers are refused" but "this endpoint answers
          // for a SESSION" — which is what makes the workspace it resolves the
          // session's own, and so what makes the no-param branch meaningful at
          // all. See core/session-redirect.ts for why the API key is sent.
          //
          // QA: branched on the deployment because plain `oss` has no auth wall
          // at ALL — nothing is behind a session or an API key there
          // (`GET /v1/private/projects` answers 200 to a request carrying no
          // headers whatsoever), so there is no refusal for this endpoint to
          // give and a flat 403 assertion fails every OSS run. It was authored
          // and settled against staging, which does have the wall.
          //
          // Both branches are assertions, not a skip: on oss the endpoint must
          // still answer this session-free caller the SAME Location it gave the
          // session above, which is the positive form of "the workspace came
          // from the deployment's own config and not from a cookie" — the very
          // thing 9742 moved. So the no-param branch stays meaningful on both
          // targets, each by the route that target actually has.
          const result = await getTraceRedirectWithoutSession({ traceId });
          if (envConfig.deployment === 'oss') {
            expect(
              result.status,
              `oss has no auth wall, so a session-free caller is still served (body: ${result.body})`,
            ).toBe(303);
            expect(
              result.location,
              'and is served the same workspace the session was — so the lookup is not session-derived',
            ).toBe(expected);
            return;
          }

          expect(
            result.status,
            `an API-key-only caller is refused on a ${envConfig.deployment} deployment ` +
              `(body: ${result.body})`,
          ).toBe(403);
          expect(result.location, 'a refused caller gets no Location').toBeNull();
        });
      },
    );

    test(
      'following the redirect in a browser lands on the Logs page with that trace’s panel open',
      {
        tag: ['@cap:traces.sdk-redirect-link', '@cap:traces.open-trace-panel'],
      },
      async ({ project, backendClient, testNamespace, registerProjectTracesCleanup, page }) => {
        registerProjectTracesCleanup(project.id);

        const traceId = uuid7();
        const traceName = `${testNamespace}-redirect-landing-trace`;

        await test.step('Seed one trace through the REST API', async () => {
          await backendClient.createTraceWithSource({
            id: traceId,
            projectName: project.name,
            name: traceName,
            source: 'sdk',
            input: { seeded: true },
            output: { seeded: true },
            endTime: new Date(),
          });
          await expect
            .poll(async () => (await backendClient.getTracePayload(traceId))?.name ?? null, {
              message: `the trace ${traceName} is readable by id`,
              timeout: 180_000,
              intervals: [1_000, 2_000, 5_000],
            })
            .toBe(traceName);
        });

        const location = await test.step('Read the Location the backend chose', async () => {
          const result = await getTraceRedirect(page.request, { traceId });
          expect(result.status, `the redirect answers 303 (body: ${result.body})`).toBe(303);
          // Asserted, not defaulted: following `null` would navigate to the
          // current page and the panel assertions below would then be about
          // nothing at all.
          expect(result.location, 'the redirect carries a Location to follow').not.toBeNull();
          return result.location!;
        });

        await test.step('Follow it and assert the trace’s own panel is open on it', async () => {
          const logs = new LogsPage(page);
          // Followed as given. Nothing here rebuilds the URL — that is the
          // difference between this test and every other panel test in the
          // estate, and the only way it can notice the link itself being wrong.
          const panel = await logs.landOnTraceLink(location, traceId);
          await panel.waitForFullyLoaded();

          // The panel header, by name: `waitForFullyLoaded` already pins
          // `?trace=<id>` in the URL, which the link put there — so on its own
          // it would pass against a page that opened a panel for a trace it
          // could not load. The name only renders once the panel's own read
          // came back.
          await expect(
            panel.traceNameInHeader(traceName),
            'the panel that opened is this trace',
          ).toBeVisible({ timeout: 60_000 });

          // And the table underneath is this project's Logs page, with the
          // trace listed. This is the half that fails when the workspace
          // segment is mangled: the route still matches, the panel param is
          // still in the URL, and the page renders a permission wall instead.
          await expect(
            logs.traceRow(traceId),
            'the Logs table underneath lists the trace',
          ).toHaveCount(1);
          await expect(
            logs.breadcrumbProjectLink(project.name),
            'the breadcrumb names the project the link pointed at',
          ).toBeVisible();
        });
      },
    );
  },
);
