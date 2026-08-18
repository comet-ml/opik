import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { ProjectDashboardsPage } from '@e2e/pom/project-dashboards.page';
import {
  DASHBOARDS_DATE_RANGE_KEY,
  LOGS_DATE_RANGE_KEY,
  readStoredDateRange,
  scopedDateRangeKey,
} from '@e2e/pom/metric-date-range.control';

/**
 * The date range that Logs and Dashboards open on for an ordinary project.
 *
 * Both pages resolve the project *before* mounting anything that reads the
 * range, because the range is backed by `use-local-storage-state`, which
 * captures its `defaultValue` once and writes that captured value into
 * storage. A consumer that mounted while the project name was still unknown
 * would freeze whatever placeholder was in scope, and the user would land on a
 * window they never chose — silently, since nothing on screen says the default
 * was decided too early.
 *
 * Two things must hold, and they fail independently:
 *
 *  - the workspace default (30 days) reaches *both* surfaces, and the project
 *    persists into the shared unsuffixed slot rather than a project-scoped one
 *    (the scoped slot exists for the seeded demo project, whose default is
 *    24h; an ordinary project leaking into a scoped slot means the wrong
 *    project got the override);
 *  - a range the user picks outranks that default and survives a fresh mount —
 *    the captured default must not overwrite the stored choice.
 *
 * Logs and Dashboards keep separate slots, so the second is asserted on each.
 * Everything is read at the real storage boundary; nothing is mocked, and no
 * assertion depends on the wall clock or on data that happens to already exist
 * in the workspace.
 */

const WORKSPACE_DEFAULT = 'Past 30 days';
const WORKSPACE_DEFAULT_ID = 'past30days';
const CHOSEN = 'Past 7 days';
const CHOSEN_ID = 'past7days';

const SEEDED_TRACES = 3;

const LLM_SPAN = {
  name: 'llm-call',
  type: 'llm' as const,
  model: 'gpt-3.5-turbo',
  provider: 'openai',
  usage: { prompt_tokens: 10, completion_tokens: 50, total_tokens: 60 },
};

test.describe('Project date range', { tag: ['@t2-cuj', '@area:dashboards'] }, () => {
  test(
    'An ordinary project opens Logs and Dashboards on the 30-day default and persists it to the shared, unscoped slot',
    { tag: ['@cap:dashboards.metric-date-range'] },
    async ({ project, sdkClient, page }) => {
      await test.step(`Seed ${SEEDED_TRACES} recent traces via the SDK`, async () => {
        for (let i = 0; i < SEEDED_TRACES; i++) {
          await sdkClient.python.createNestedTrace({
            project_name: project.name,
            name: `date-range-trace-${i}`,
            input: { q: `question ${i}` },
            output: { a: `answer ${i}` },
            duration_seconds: 1,
            spans: [LLM_SPAN],
          });
        }
      });

      const logs = new LogsPage(page);

      await test.step('Logs opens on the 30-day default with the seeded traces listed', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();

        await logs.dateRange.expectPreset(WORKSPACE_DEFAULT);
        // The range is not decoration: it windows the table. Traces stamped
        // now must fall inside the default window and be listed.
        await expect(logs.traceRows).toHaveCount(SEEDED_TRACES);
        expect(await logs.countTraces()).toBe(SEEDED_TRACES);
      });

      await test.step('Logs persisted the default to the shared slot, not a project-scoped one', async () => {
        expect(await readStoredDateRange(page, LOGS_DATE_RANGE_KEY)).toBe(WORKSPACE_DEFAULT_ID);
        expect(
          await readStoredDateRange(page, scopedDateRangeKey(LOGS_DATE_RANGE_KEY, project.name)),
        ).toBeNull();
      });

      const dashboards = new ProjectDashboardsPage(page);

      await test.step('Dashboards opens on the same default, in its own shared slot', async () => {
        await dashboards.goto(project.id);
        await dashboards.waitForReady();

        await dashboards.dateRange.expectPreset(WORKSPACE_DEFAULT);
        // Dashboards persists under its own key, so the two surfaces can hold
        // different ranges — but both must start from the workspace default.
        expect(await readStoredDateRange(page, DASHBOARDS_DATE_RANGE_KEY)).toBe(
          WORKSPACE_DEFAULT_ID,
        );
        expect(
          await readStoredDateRange(
            page,
            scopedDateRangeKey(DASHBOARDS_DATE_RANGE_KEY, project.name),
          ),
        ).toBeNull();
      });
    },
  );

  test(
    'A range chosen on Logs or Dashboards outranks the default and survives a fresh mount',
    { tag: ['@cap:dashboards.metric-date-range'] },
    async ({ project, sdkClient, page }) => {
      await test.step('Seed one recent trace via the SDK', async () => {
        await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: 'date-range-sticky-trace',
          input: { q: 'question' },
          output: { a: 'answer' },
          duration_seconds: 1,
          spans: [LLM_SPAN],
        });
      });

      const logs = new LogsPage(page);

      await test.step(`Pick "${CHOSEN}" on Logs`, async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await logs.dateRange.expectPreset(WORKSPACE_DEFAULT);

        await logs.dateRange.selectPreset(CHOSEN);
        expect(await readStoredDateRange(page, LOGS_DATE_RANGE_KEY)).toBe(CHOSEN_ID);
      });

      await test.step('A fresh Logs mount reads the stored choice back, not the default', async () => {
        // Navigating afresh rather than reloading is what makes this an
        // assertion about storage: selecting a range also writes ?time_range=
        // into the URL, and a reload would satisfy the trigger from the query
        // param alone even if the stored value had been clobbered.
        await logs.goto(project.id);
        await logs.waitForReady();

        await logs.dateRange.expectPreset(CHOSEN);
        expect(await readStoredDateRange(page, LOGS_DATE_RANGE_KEY)).toBe(CHOSEN_ID);
      });

      const dashboards = new ProjectDashboardsPage(page);

      await test.step('Dashboards keeps its own choice across a fresh mount, independently of Logs', async () => {
        await dashboards.goto(project.id);
        await dashboards.waitForReady();
        // Its own slot, so the Logs choice above must not have moved it.
        await dashboards.dateRange.expectPreset(WORKSPACE_DEFAULT);

        await dashboards.dateRange.selectPreset(CHOSEN);
        expect(await readStoredDateRange(page, DASHBOARDS_DATE_RANGE_KEY)).toBe(CHOSEN_ID);

        await dashboards.goto(project.id);
        await dashboards.waitForReady();
        await dashboards.dateRange.expectPreset(CHOSEN);
        expect(await readStoredDateRange(page, DASHBOARDS_DATE_RANGE_KEY)).toBe(CHOSEN_ID);
      });
    },
  );
});
