import { test, expect } from '@e2e/fixtures';
import { ProjectHomePage } from '@e2e/pom/project-home.page';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendFilter } from '@e2e/core/backend';

/**
 * "Traces logged today" on a project's Home page counts the user's OWN traffic
 * and nothing the product logged on their behalf (opik#8808).
 *
 * `RecentActivityService.fetchTraceDailyCounts` asks the daily TRACE_COUNT
 * metric under two filters — `visibility_mode = default` and `source = sdk` —
 * and `Source.legacyFallbackDbValue` widens that second one, and only that one,
 * to also match the stored `unknown` of rows written before source tracking
 * existed. So the number must count: SDK traces, plus legacy no-source rows.
 * Not: playground, experiment, optimization or evaluator runs.
 *
 * Why this is worth a permanent spec rather than the JUnit test that already
 * exists for the aggregation:
 *
 *  - The failure is silent in both directions. A filter one value too narrow
 *    under-reports a user's own traffic; one value too broad inflates it with
 *    runs they did not make. Neither errors, neither looks empty, and the page
 *    renders whichever number it is given perfectly plausibly.
 *  - Nothing in the estate touched Recent activity at all, and
 *    `projects.recent-activity` read `covered: false` with no tagged spec.
 *  - The row is a LINK to the project's Logs page, which asks the server the
 *    same `source = sdk` + `visibility_mode = default` pair. The two reads
 *    agreeing is the user-visible contract: a count that does not match the
 *    rows the link then lands on is the bug a user actually reports.
 *
 * Both surfaces, because the candidate is about them agreeing: the count is
 * asserted against `/activities` (the API the page renders) and against
 * `/traces` under the service's own filter pair (the API the Logs page
 * renders), and then read off the page and off the table it links to. Seeding
 * is REST-only — one row per `Source` value — so the expected number is a pure
 * function of what this test wrote and depends on no model, no clock arithmetic
 * and no data that happens to exist in the workspace.
 */

/**
 * The toggle the whole page is behind.
 *
 * `project_homepage_enabled` defaults to `false`, and when it is off
 * `ProjectHomePage` replaces itself with `/logs` — so the surface this spec
 * asserts is not merely hidden, it is unreachable, and waiting for its heading
 * times out on the Logs page with nothing to say why. Measured off the deployed
 * build rather than assumed, because it is a staged rollout: on at the time of
 * writing on staging, off on a stock OSS stack.
 */
const PROJECT_HOMEPAGE_TOGGLE = 'project_homepage_enabled';

/** The pair `RecentActivityService` sends, and the Logs traces tab with it. */
const SDK_SOURCE_FILTERS: BackendFilter[] = [
  { field: 'source', operator: '=', value: 'sdk', type: 'string' },
  { field: 'visibility_mode', operator: '=', value: 'default', type: 'string' },
];

test.describe('Project home — Recent activity', { tag: ['@area:projects'] }, () => {
  // The Logs table the row links to collapses columns on a narrow window, and
  // the home page's activity strip truncates its label.
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    '"Traces logged today" counts SDK and legacy no-source traces, and its link lists exactly those',
    { tag: ['@t2-cuj', '@cap:projects.recent-activity'] },
    async ({ project, sourceMixTraces, backendClient, page }) => {
      test.setTimeout(180_000);

      // A deployment that does not offer the page, not a failure: with the
      // toggle off the route redirects to Logs, so there is no Recent activity
      // to assert on. Skipped rather than marked `test.fail()` — the behaviour
      // is not broken anywhere, it is simply absent here, and a `test.fail()`
      // would turn a correct build with the flag ON into a red run. Every
      // assertion below is left exactly as it is.
      const homepageEnabled = await backendClient.isFeatureToggleEnabled(PROJECT_HOMEPAGE_TOGGLE);
      test.skip(
        !homepageEnabled,
        `the project home page is behind ${PROJECT_HOMEPAGE_TOGGLE}, which this deployment ` +
          'reports as off — the route redirects to Logs, so Recent activity is unreachable',
      );

      const expectedCounted = [...sourceMixTraces.countedIds].sort();

      await test.step('The activities read reports the count, and only that item', async () => {
        const activity = await backendClient.listRecentActivity({
          projectId: project.id,
          size: 10,
        });

        // The whole envelope, not just "a trace_daily item exists with 3": the
        // list merges six sources, and `fetchTraceDailyCounts` emits one item
        // per day that has any matching rows. A second trace_daily item would
        // mean the seed landed across two UTC buckets, which would make the
        // count below right for the wrong reason.
        expect(
          activity.items.map((item) => item.type),
          'the fresh project has exactly one activity item, the daily trace count',
        ).toEqual(['trace_daily']);
        expect(activity.total).toBe(1);
        // Compared as the string the server sends and the page renders. A count
        // that arrived as "3.0" is a different answer from one that arrived as
        // "3", and normalising it here would hide that.
        expect(activity.items[0].name).toBe(String(sourceMixTraces.expectedTodayCount));
      });

      await test.step("…and the service's own filter pair returns exactly those rows", async () => {
        const counted = await backendClient.searchTraceIdsPage({
          projectId: project.id,
          filters: SDK_SOURCE_FILTERS,
          size: 200,
        });
        // Both the population and the rows. `total` alone would pass over a
        // read that returned three rows of the wrong four; the id set alone
        // would pass over a read whose envelope claimed more than it returned.
        expect(counted.total).toBe(sourceMixTraces.expectedTodayCount);
        expect([...counted.ids].sort()).toEqual(expectedCounted);

        // Stated from the other side too, so "the four runs are excluded" is
        // asserted rather than inferred from a count that happens to match.
        for (const excludedId of sourceMixTraces.excludedIds) {
          expect(counted.ids, `${excludedId} is a product-logged run and must not count`)
            .not.toContain(excludedId);
        }
      });

      const home = new ProjectHomePage(page);

      await test.step('The Home page renders that count under "Traces logged today"', async () => {
        await home.goto(project.id);
        await home.waitForReady();

        // One row, and it is the trace-count one: the project is fresh, so any
        // other row would be activity this test did not create.
        await expect(home.recentActivityRows).toHaveCount(1);
        await expect(home.tracesLoggedRow).toHaveCount(1);
        expect(await home.tracesLoggedTodayCount()).toBe(sourceMixTraces.expectedTodayCount);
      });

      const logs = new LogsPage(page);

      await test.step('Following the row lands on Logs, holding exactly those traces', async () => {
        await home.tracesLoggedRow.click();
        // The link's own target (`/logs` with `logsType=traces`), asserted
        // before the table: a row that navigated somewhere else would otherwise
        // surface as a confusing table assertion rather than a wrong link.
        await page.waitForURL(
          (url) =>
            url.pathname.endsWith(`/projects/${project.id}/logs`) &&
            url.searchParams.get('logsType') === 'traces',
        );
        await logs.waitForReady();

        const rowIds = await logs.readRowIdsOnPage();
        expect([...rowIds].sort()).toEqual(expectedCounted);
        // The table's own population readout, so a second page of excluded rows
        // could not hide behind a correct first page.
        expect(await logs.countTraces()).toBe(sourceMixTraces.expectedTodayCount);

        for (const excludedId of sourceMixTraces.excludedIds) {
          await expect(
            logs.traceRow(excludedId),
            `${excludedId} is a product-logged run and must not be listed`,
          ).toHaveCount(0);
        }
      });
    },
  );
});
