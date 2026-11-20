import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendFilter } from '@e2e/core/backend';

/**
 * `source=evaluator` monitoring traces: written unconditionally, hidden from
 * every Logs read, and findable only by asking for them (opik#7317).
 *
 * 7317 deleted the `onlineScoringTracingEnabled` service toggle, so the rows
 * an LLM-judge rule writes about its own evaluations are no longer optional.
 * Every project with a judge rule now accumulates one per sampled trace,
 * permanently, and an operator who had turned that off cannot turn it back off.
 * What makes that liveable is a contract with two halves, and nothing in the
 * estate asserted either of them:
 *
 *  - **Hidden.** `TracesSpansTab` pins `logsSource: LOGS_SOURCE.sdk` on its
 *    listing, its stats and its KPI cards, so a user's view of their own
 *    traffic must not move when monitoring rows arrive. A leak inflates the
 *    table and the count card with runs the user did not make — silently, and
 *    on the page they read most often.
 *  - **Findable.** The same `source` column has to answer an explicit
 *    `= evaluator` read, which is the only route to these rows: the Logs page
 *    offers no control that can reach them (its own `source = sdk` is appended
 *    to whatever the user filters by, so a user-built `source = evaluator`
 *    chip composes into a contradiction and returns nothing). If this half
 *    broke, the writes would be pure storage cost with nothing able to read
 *    them back — invisible to every assertion about the first half.
 *
 * Why this is not already covered. `project-home-traces-logged-today.spec.ts`
 * pins the DAILY COUNT on the project home page — one of the six sources its
 * activity list merges — and the Logs rows that row links to, over a seed
 * carrying one trace per `Source` value. It is the right spec for "the daily
 * counter counts the user's own traffic". It says nothing about the Traces
 * tab's own KPI card, and nothing at all about reading the excluded rows back:
 * its assertions are exclusions, which hold just as well for rows that were
 * never written.
 *
 * Both surfaces, because the contract is that they disagree on purpose: the
 * same project is read through the UI (which must show five rows) and through
 * the API under an explicit filter (which must show the other seven). A spec on
 * either alone cannot tell "excluded" from "absent".
 *
 * Deterministic and free. `evaluatorSourceTraces` seeds the `source` values
 * directly over REST — no judge rule, no provider call, no sampling — so the
 * expected numbers are a pure function of what this test wrote. See that
 * fixture for why that is the only way to make this reproducible.
 */

/** The pair the Logs listing sends for the Traces tab. */
const LOGS_TRACES_FILTERS: BackendFilter[] = [
  { field: 'source', operator: '=', value: 'sdk', type: 'string' },
  { field: 'visibility_mode', operator: '=', value: 'default', type: 'string' },
];

/** The filter a caller has to send by hand to reach the monitoring rows. */
const EVALUATOR_FILTER: BackendFilter[] = [
  { field: 'source', operator: '=', value: 'evaluator', type: 'string' },
];

test.describe(
  'Trace Explore — evaluator monitoring traces (opik#7317)',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    // The Logs table collapses columns on a narrow window, and the KPI cards
    // only render their deltas above ~240px each.
    test.use({ viewport: { width: 1600, height: 900 } });

    // `describe.configure` and not `test.setTimeout`: the fixture's own
    // queryability poll allows up to 120s, which is already past the 90s
    // default by the time the body could raise it — so a slow staging would
    // die in setup with the limit the body never got to change.
    test.describe.configure({ timeout: 300_000 });

    test(
      'the Logs table and its Traces count card both show only the user traces, and name no evaluator row',
      { tag: ['@cap:traces.list-traces'] },
      async ({ project, evaluatorSourceTraces, backendClient, page }) => {
        const seed = evaluatorSourceTraces;
        const expectedUserIds = seed.userTraces.map((t) => t.id).sort();

        await test.step("The listing under the tab's own filters returns exactly the user traces", async () => {
          const listed = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            filters: LOGS_TRACES_FILTERS,
            size: 200,
          });
          // The population and the rows. `total` alone passes over a read that
          // returned four of the five; the id set alone passes over an envelope
          // claiming more than it returned.
          expect(listed.total, 'the envelope counts the user traces only').toBe(
            seed.userTraces.length,
          );
          expect([...listed.ids].sort(), 'and they are exactly those rows').toEqual(
            expectedUserIds,
          );
          // Stated from the other side too, by id, so "the evaluator rows are
          // excluded" is asserted rather than inferred from a count that
          // happens to match.
          for (const evaluatorTrace of seed.evaluatorTraces) {
            expect(
              listed.ids,
              `${evaluatorTrace.name} is a monitoring row and must not be listed`,
            ).not.toContain(evaluatorTrace.id);
          }
        });

        const expectedCardCount = await test.step(
          'The KPI card read agrees, under the single filter the cards send',
          async () => {
            // The cards are a different query from the listing and send a
            // different filter set — `generateLogsSourceFilter` only, with no
            // `visibility_mode` (see `useProjectKpiCards`) — so agreement
            // between the two is a real claim and not a tautology.
            const cards = await backendClient.projectKpiCards({
              projectId: project.id,
              entityType: 'traces',
              intervalStart: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
              filters: [{ field: 'source', operator: '=', value: 'sdk', type: 'string' }],
            });
            expect(cards.status, `the kpi-cards read answered ${cards.status}`).toBe(200);
            const count = cards.stats.find((stat) => stat.type === 'count');
            // Asserted present, then compared. The API types every card value
            // as nullable, and a `?? 0` here would turn "the card returned
            // nothing" into a passing zero on an empty project.
            expect(count, 'the kpi-cards read carries a count card').toBeDefined();
            expect(count?.currentValue, 'the count card counts the user traces only').toBe(
              seed.userTraces.length,
            );
            return seed.userTraces.length;
          },
        );

        const logs = new LogsPage(page);

        await test.step('The Logs Traces tab renders exactly those rows', async () => {
          await logs.gotoTraces(project.id, { timeRange: seed.timeRangePreset, size: 100 });
          // Gate on the card, not the table: the cards are a separate query,
          // and reading the table first would race the count placeholder.
          await expect(
            logs.metricsCardValue('count'),
            'the Traces count card resolves to the user-trace count',
          ).toHaveText(String(expectedCardCount), { timeout: 90_000 });
          await logs.waitForReady({ timeout: 60_000 });

          expect(await logs.activeLogsTab(), 'the Traces tab is the one on screen').toBe('traces');

          const rowIds = await logs.readRowIdsOnPage();
          expect([...rowIds].sort(), 'the rendered rows are exactly the user traces').toEqual(
            expectedUserIds,
          );
          // The footer's own population readout, so a second page of
          // monitoring rows could not hide behind a correct first page.
          const summary = await logs.readPaginationSummary();
          expect(summary.total, 'the table footer counts the user traces only').toBe(
            seed.userTraces.length,
          );
        });

        await test.step('No evaluator trace is addressable or named anywhere on the page', async () => {
          for (const evaluatorTrace of seed.evaluatorTraces) {
            await expect(
              logs.traceRow(evaluatorTrace.id),
              `${evaluatorTrace.name} must have no row`,
            ).toHaveCount(0);
            // By NAME as well as by row id: a row rendered without its
            // `data-row-id`, or a monitoring trace surfacing in a cell of
            // someone else's row, is invisible to the id lookup above. The
            // names carry the run namespace, so neither string occurs anywhere
            // else in the workspace.
            await expect(
              page.getByText(evaluatorTrace.name, { exact: true }),
              `${evaluatorTrace.name} must not be rendered at all`,
            ).toHaveCount(0);
          }
        });
      },
    );

    test(
      'an explicit source filter reads the monitoring traces back, exactly and exclusively',
      { tag: ['@cap:traces.filter-traces'] },
      async ({ project, evaluatorSourceTraces, backendClient }) => {
        const seed = evaluatorSourceTraces;
        const expectedEvaluatorIds = seed.evaluatorTraces.map((t) => t.id).sort();
        const expectedUserIds = seed.userTraces.map((t) => t.id).sort();

        await test.step('source = evaluator returns exactly the monitoring rows', async () => {
          const found = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            filters: EVALUATOR_FILTER,
            size: 200,
          });
          expect(found.total, 'the envelope counts the monitoring rows').toBe(
            seed.evaluatorTraces.length,
          );
          expect([...found.ids].sort(), 'and they are exactly those rows').toEqual(
            expectedEvaluatorIds,
          );
          // The whole answer, not just that mine are in it: a filter that also
          // carried the user rows would satisfy a membership check while
          // breaking the exclusion this release depends on.
          for (const userTrace of seed.userTraces) {
            expect(
              found.ids,
              `${userTrace.name} is the user's own traffic and must not be returned`,
            ).not.toContain(userTrace.id);
          }
        });

        await test.step('source = sdk returns exactly the complement', async () => {
          // The same question from the other side, and not the same query:
          // `Source.legacyFallbackDbValue` widens `= sdk` — and only `= sdk` —
          // to also match the stored `unknown` of pre-source-tracking rows, so
          // this clause is the one that could over-match. Asserted here because
          // the two filters must partition the project between them: a value
          // that answered neither read would be a row nobody can ever see.
          const user = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            filters: [{ field: 'source', operator: '=', value: 'sdk', type: 'string' }],
            size: 200,
          });
          expect(user.total, 'the envelope counts the user traces').toBe(seed.userTraces.length);
          expect([...user.ids].sort(), 'and they are exactly those rows').toEqual(expectedUserIds);
        });

        await test.step('Together the two filters account for the whole project', async () => {
          // The exhaustion check neither filter can make alone. Read unfiltered
          // rather than added up from the two answers above: a row carrying
          // some third `source` would be counted by neither filter and would
          // show up only here.
          const everything = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            size: 200,
          });
          expect(everything.total, 'the project holds every seeded trace and nothing else').toBe(
            seed.all.length,
          );
          expect([...everything.ids].sort(), 'and they are exactly the seeded rows').toEqual(
            [...expectedUserIds, ...expectedEvaluatorIds].sort(),
          );
        });

        await test.step('Each monitoring row is readable by id, carrying its payload', async () => {
          // The half an exclusion assertion can never make: these rows are
          // WRITTEN, with their content, and not merely absent from a filtered
          // listing. A build that stopped writing them would pass every
          // exclusion assertion in this file and fail here.
          for (const evaluatorTrace of seed.evaluatorTraces) {
            const payload = await backendClient.getTracePayload(evaluatorTrace.id);
            expect(payload, `${evaluatorTrace.name} is readable by id`).not.toBeNull();
            expect(payload?.source, `${evaluatorTrace.name} is stored as a monitoring row`).toBe(
              'evaluator',
            );
            expect(payload?.output, `${evaluatorTrace.name} kept its payload`).toEqual({
              answer: `${evaluatorTrace.name} answer`,
            });
          }
        });
      },
    );
  },
);
