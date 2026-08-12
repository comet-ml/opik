import { test, expect } from '@e2e/fixtures';
import { ProjectsPage } from '@e2e/pom/projects.page';
import type { PythonSdkClient } from '@e2e/core/sdk';

/**
 * The Projects list is the workspace's landing page, and its statistic columns
 * are scoped to a rolling 30-day window: the table asks
 * `GET /v1/private/projects/stats` for `now-30d .. now` and labels every
 * column that carries that scope "(30d)".
 *
 * Two things can go wrong independently, so both are asserted here:
 *  - the backend can window wrongly (return all-time numbers, or nothing);
 *  - the page can render numbers that don't come from the windowed call.
 * A spec that only checked the API would miss the second; one that only read
 * the page couldn't tell a correct number from a coincidence.
 *
 * Traces are placed inside and outside the window by age: a seeded trace
 * carries a UUIDv7 id stamped at its own age, and the window is applied to
 * that id. Nothing here depends on data that happens to already exist.
 */
const OUTSIDE_WINDOW_DAYS = 60;
const SCORE_NAME = 'sample-quality';
const LIVE_THREAD = 'live-thread';

/** What the seeded project holds, all-time versus inside the 30-day window. */
const EXPECTED = {
  allTime: { traces: 3, threads: 2, errors: 2, score: 0.5 },
  windowed: { traces: 2, threads: 1, errors: 1, score: 1 },
};

const LLM_SPAN = {
  name: 'llm-call',
  type: 'llm' as const,
  model: 'gpt-3.5-turbo',
  provider: 'openai',
  usage: { prompt_tokens: 10, completion_tokens: 50, total_tokens: 60 },
};

const ERROR_INFO = {
  exception_type: 'ValueError',
  message: 'seeded failure',
  traceback: 'seeded failure',
};

/**
 * Three traces: two inside the window (sharing a thread; the first also failed
 * and scored 1.0) and one outside it (its own thread, failed, scored 0.0).
 *
 * Deliberately the smallest seed for which the windowed and the all-time
 * answer differ on *every* metric the table renders — trace count, thread
 * count, error count and the score average all move — so a page showing
 * all-time numbers cannot coincidentally match, while the seed stays small
 * enough not to trip the workspace's ingestion rate limit.
 */
async function seedWindowedProject(sdk: PythonSdkClient, projectName: string): Promise<void> {
  const trace = (
    name: string,
    extra: Partial<Parameters<PythonSdkClient['createNestedTrace']>[0]>,
  ) =>
    sdk.createNestedTrace({
      project_name: projectName,
      name,
      input: { q: name },
      output: { a: name },
      duration_seconds: 1,
      spans: [LLM_SPAN],
      ...extra,
    });

  await trace('in-window-scored-and-failed', {
    thread_id: LIVE_THREAD,
    feedback_scores: [{ name: SCORE_NAME, value: 1 }],
    error_info: ERROR_INFO,
  });
  await trace('in-window-same-thread', { thread_id: LIVE_THREAD });
  await trace('outside-window-scored-and-failed', {
    age_days: OUTSIDE_WINDOW_DAYS,
    thread_id: 'dormant-thread',
    feedback_scores: [{ name: SCORE_NAME, value: 0 }],
    error_info: ERROR_INFO,
  });
}

/** now-30d .. now, the window the table itself asks for. */
function thirtyDayWindow() {
  const toTime = new Date();
  return { fromTime: new Date(toTime.getTime() - 30 * 24 * 60 * 60 * 1000), toTime };
}

test.describe('Projects list — 30-day windowed stats', { tag: ['@area:projects'] }, () => {
  /** Stat columns collapse out of the viewport on a narrow window. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'Project stats are scoped to the requested window, and unscoped when none is given',
    { tag: ['@t2-cuj', '@cap:projects.list-projects'] },
    async ({ project, sdkClient, backendClient }) => {
      await test.step('Seed traces inside and outside the 30-day window', async () => {
        await seedWindowedProject(sdkClient.python, project.name);
      });

      await test.step('All-time stats count every trace', async () => {
        // Ingestion is eventually consistent: poll the aggregate rather than
        // sleeping, so the spec neither flakes nor waits longer than it must.
        await expect
          .poll(
            async () => {
              const [stats] = await backendClient.getProjectStats({ name: project.name });
              return stats?.traceCount ?? null;
            },
            { timeout: 60_000 },
          )
          .toBe(EXPECTED.allTime.traces);

        const [stats] = await backendClient.getProjectStats({ name: project.name });
        expect(stats.threadCount).toBe(EXPECTED.allTime.threads);
        expect(stats.errorCount).toBe(EXPECTED.allTime.errors);
        expect(stats.feedbackScores[SCORE_NAME]).toBe(EXPECTED.allTime.score);
      });

      await test.step('The 30-day window drops the traces that fall outside it', async () => {
        const [stats] = await backendClient.getProjectStats({
          name: project.name,
          ...thirtyDayWindow(),
        });
        expect(stats.traceCount).toBe(EXPECTED.windowed.traces);
        expect(stats.threadCount).toBe(EXPECTED.windowed.threads);
        expect(stats.errorCount).toBe(EXPECTED.windowed.errors);
        // The average moves with the window, it isn't merely re-counted: the
        // 0.0 score sits outside, so the windowed average is 1.0, not 0.5.
        expect(stats.feedbackScores[SCORE_NAME]).toBe(EXPECTED.windowed.score);
      });
    },
  );

  test(
    'The projects table renders the windowed stats, marks those columns "(30d)", and keeps dormant projects listed',
    { tag: ['@t2-cuj', '@cap:projects.list-projects'] },
    async ({ project, sdkClient, backendClient, testNamespace, page }) => {
      const dormantName = `${testNamespace}-dormant`;

      const dormantId = await test.step('Seed an active project and one whose only activity is old', async () => {
        await seedWindowedProject(sdkClient.python, project.name);
        const dormant = await sdkClient.python.createProject({ name: dormantName });
        await sdkClient.python.createNestedTrace({
          project_name: dormantName,
          name: 'dormant-trace',
          input: { q: 'dormant' },
          output: { a: 'dormant' },
          duration_seconds: 1,
          age_days: OUTSIDE_WINDOW_DAYS,
          spans: [LLM_SPAN],
        });
        return dormant.id;
      });

      try {
        const windowed = await test.step('Read the windowed stats the table should be showing', async () => {
          await expect
            .poll(
              async () => {
                const [stats] = await backendClient.getProjectStats({
                  name: project.name,
                  ...thirtyDayWindow(),
                });
                return stats?.traceCount ?? null;
              },
              { timeout: 60_000 },
            )
            .toBe(EXPECTED.windowed.traces);
          const [stats] = await backendClient.getProjectStats({
            name: project.name,
            ...thirtyDayWindow(),
          });
          return stats;
        });

        const projects = new ProjectsPage(page);
        await test.step('Open the Projects page, narrowed to this run', async () => {
          await projects.goto();
          await projects.waitForReady();
          await projects.search(testNamespace);
          await expect(projects.projectRow(project.name)).toBeVisible();
        });

        await test.step('Only the windowed columns are labelled "(30d)"', async () => {
          await expect(projects.columnHeader('Trace count (30d)')).toBeVisible();
          await expect(projects.columnHeader('Errors (30d)')).toBeVisible();
          await expect(projects.columnHeader('Avg feedback scores (30d)')).toBeVisible();
          // Project metadata is not windowed, and must not claim to be.
          await expect(projects.columnHeader('Name')).toBeVisible();
          await expect(projects.columnHeader('Last updated')).toBeVisible();
        });

        await test.step('The rendered cells carry the windowed numbers, not the all-time ones', async () => {
          await expect(projects.statCell(project.name, 'trace_count')).toHaveText(
            String(windowed.traceCount),
          );
          await expect(projects.statCell(project.name, 'error_count')).toContainText(
            String(windowed.errorCount),
          );

          const scoreCell = projects.statCell(project.name, 'feedback_scores');
          await expect(scoreCell).toContainText(`${SCORE_NAME} (avg)`);
          await expect(scoreCell).toContainText(String(windowed.feedbackScores[SCORE_NAME]));
          // 0.5 is this project's all-time average — seeing it here would mean
          // the page is rendering an unwindowed call.
          await expect(scoreCell).not.toContainText(String(EXPECTED.allTime.score));
        });

        await test.step('A project with no activity in the window is still listed, with blank stats', async () => {
          await expect(projects.projectRow(dormantName)).toBeVisible();
          // Blank, not zero: outside every window the backend returns no
          // metric at all, and the column renders nothing rather than "0".
          await expect(projects.statCell(dormantName, 'trace_count')).not.toContainText(/\d/);
        });
      } finally {
        await backendClient.deleteProject(dormantId);
      }
    },
  );
});
