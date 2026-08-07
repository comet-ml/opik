import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Each test drives a different filter *kind* against the same seeded set, so
 * they don't re-cover each other: a query-builder list chip (tags), a
 * one-click boolean chip (with errors), and a keyed numeric chip pinned from
 * the "All filters" manager (feedback scores).
 *
 * Every assertion pins the exact surviving row ids rather than just a count —
 * a filter that returned the wrong rows in the right number is the silent
 * failure this coverage exists to catch.
 */
test.describe('Trace filters', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'Tag filter narrows the table to the tagged traces and clearing restores the full set',
    { tag: ['@cap:traces.filter-traces'] },
    async ({ filterableTraces, project, page }) => {
      const logs = new LogsPage(page);
      const { all, sharedTag } = filterableTraces;
      const tagged = all.filter((t) => t.tags.includes(sharedTag));

      await test.step('Open Logs and confirm every seeded trace is listed', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(all.length);
      });

      await test.step(`Filter by tag "${sharedTag}"`, async () => {
        await logs.applyFilter('tags', sharedTag);

        await expect(logs.traceRows).toHaveCount(tagged.length);
        for (const trace of tagged) {
          await expect(logs.traceRow(trace.id)).toBeVisible();
        }
        for (const trace of all.filter((t) => !t.tags.includes(sharedTag))) {
          await expect(logs.traceRow(trace.id)).toBeHidden();
        }
        expect(await logs.countTraces()).toBe(tagged.length);
      });

      await test.step('Clear the filter and verify the full set returns', async () => {
        await logs.clearAllFilters();

        await expect(logs.traceRows).toHaveCount(all.length);
        for (const trace of all) {
          await expect(logs.traceRow(trace.id)).toBeVisible();
        }
        expect(await logs.countTraces()).toBe(all.length);
      });
    },
  );

  test(
    'With errors filter narrows the table to the errored trace',
    { tag: ['@cap:traces.filter-traces'] },
    async ({ filterableTraces, project, page }) => {
      const logs = new LogsPage(page);
      const { all } = filterableTraces;
      const errored = all.filter((t) => t.hasError);

      await test.step('Open Logs and confirm every seeded trace is listed', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(all.length);
      });

      await test.step('Toggle the "With errors" filter on', async () => {
        await logs.toggleBooleanFilter('with_errors');

        await expect(logs.traceRows).toHaveCount(errored.length);
        for (const trace of errored) {
          await expect(logs.traceRow(trace.id)).toBeVisible();
        }
        for (const trace of all.filter((t) => !t.hasError)) {
          await expect(logs.traceRow(trace.id)).toBeHidden();
        }
        await expect(logs.filterChip('with_errors')).toHaveAttribute('aria-pressed', 'true');
        expect(await logs.countTraces()).toBe(errored.length);
      });

      await test.step('Toggle it back off and verify the full set returns', async () => {
        await logs.toggleBooleanFilter('with_errors');

        await expect(logs.traceRows).toHaveCount(all.length);
        await expect(logs.filterChip('with_errors')).toHaveAttribute('aria-pressed', 'false');
        expect(await logs.countTraces()).toBe(all.length);
      });
    },
  );

  test(
    'Feedback score filter narrows the table to traces scoring at or above the threshold',
    { tag: ['@cap:traces.filter-traces'] },
    async ({ filterableTraces, project, page }) => {
      const logs = new LogsPage(page);
      const { all, scoreName, scoreThreshold } = filterableTraces;
      const passing = all.filter((t) => t.relevance !== null && t.relevance >= scoreThreshold);
      const excluded = all.filter((t) => t.relevance === null || t.relevance < scoreThreshold);

      await test.step('Open Logs and confirm every seeded trace is listed', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(all.length);
      });

      await test.step(`Filter by ${scoreName} >= ${scoreThreshold}`, async () => {
        // Feedback scores aren't pinned to the bar by default, so pin it first.
        await logs.pinFilterChip('Trace feedback scores');
        await logs.applyKeyedFilter('feedback_scores', scoreName, String(scoreThreshold));

        await expect(logs.traceRows).toHaveCount(passing.length);
        for (const trace of passing) {
          await expect(logs.traceRow(trace.id)).toBeVisible();
        }
        // Includes the trace carrying the same score name below the threshold —
        // the case that separates a real numeric comparison from a key-presence match.
        for (const trace of excluded) {
          await expect(logs.traceRow(trace.id)).toBeHidden();
        }
        expect(await logs.countTraces()).toBe(passing.length);
      });

      await test.step('Clear the filter and verify the full set returns', async () => {
        await logs.clearAllFilters();

        await expect(logs.traceRows).toHaveCount(all.length);
        expect(await logs.countTraces()).toBe(all.length);
      });
    },
  );

  test(
    'Switching to a different chip while one is open filters by the chip that was asked for',
    { tag: ['@cap:traces.filter-traces'] },
    async ({ filterableTraces, project, page }) => {
      const logs = new LogsPage(page);
      const { all, sharedTag } = filterableTraces;
      const tagged = all.filter((t) => t.tags.includes(sharedTag));

      await test.step('Open Logs and leave the Metadata chip open', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await logs.openFilterChip('metadata');
        await expect(logs.filterChipPopover).toBeVisible();
      });

      await test.step(`Filter by tag "${sharedTag}" without closing Metadata first`, async () => {
        // Only one chip popover is mounted at a time, so "a dialog is visible"
        // does not mean the requested chip owns it. If the POM gated on that
        // instead of the chip's own aria-expanded, the value would land in
        // Metadata's row and no tag filter would apply — all rows would remain.
        await logs.applyFilter('tags', sharedTag);

        await expect(logs.traceRows).toHaveCount(tagged.length);
        for (const trace of tagged) {
          await expect(logs.traceRow(trace.id)).toBeVisible();
        }
      });
    },
  );
});
