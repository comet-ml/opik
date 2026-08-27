import { test, expect } from '@e2e/fixtures';
import { LogsPage, type LogsFilterRow } from '@e2e/pom/logs.page';
import type { TracePanelPage } from '@e2e/pom/trace-panel.page';
import type { QuickFilterAttributesRef } from '@e2e/fixtures';

/**
 * The details panel's quick-filter icon sits on attributes that belong to the
 * selected span, but the panel can be opened from the Traces tab — where the
 * span table that owns such a filter is not mounted. The filter is therefore
 * written into the other view's URL state by hand and the table follows.
 *
 * Both tests assert the exact surviving row ids rather than a count alone. A
 * handoff that dropped its row, or wrote it against the wrong field, leaves the
 * user on a table that still renders — just with the wrong rows in it — so
 * "the table changed" is not evidence the filter was carried across.
 */
test.describe('Quick attribute filter handoff', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  /** The row the handoff must write for a `metadata.<key>` attribute. */
  const metadataRow = (key: string, value: string): LogsFilterRow => ({
    field: 'metadata',
    type: 'dictionary',
    operator: 'contains',
    key,
    value,
  });

  /**
   * Open a trace from the Traces table, select one of its spans, and quick-filter
   * one of that span's metadata attributes into the Spans view.
   *
   * Rows are opened by click rather than by URL, because `spans_filters` lives in
   * the query string the handoff just wrote and a rebuilt URL would drop it.
   */
  const handOffSpanAttribute = async (
    logs: LogsPage,
    seed: QuickFilterAttributesRef,
    suffix: string,
    attribute: { key: string; value: string },
  ): Promise<TracePanelPage> => {
    const trace = seed.traces.find((t) => t.suffix === suffix);
    if (!trace) {
      throw new Error(`handOffSpanAttribute: the seed carries no trace with suffix "${suffix}"`);
    }

    const panel = await logs.openTraceRow(trace.id);
    await panel.waitForFullyLoaded();
    await panel.selectSpan(trace.spans.retrieve.name);
    await panel.quickFilterAttribute(`${attribute.key}: ${attribute.value}`, 'spans');
    await expect.poll(() => logs.currentLogsType()).toBe('spans');
    return panel;
  };

  test(
    'Quick-filtering a span attribute from the Traces tab moves the table to Spans and narrows to the matching spans',
    { tag: ['@cap:traces.toggle-spans-view'] },
    async ({ quickFilterAttributes, project, page }) => {
      const logs = new LogsPage(page);
      const { spanStage, stageSpanIds, traces } = quickFilterAttributes;

      await test.step('Open Logs on the Traces tab with every seeded trace listed', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(traces.length);
        expect(logs.currentLogsType()).toBe('traces');
      });

      const panel = await test.step('Select a span and quick-filter its metadata attribute', async () =>
        handOffSpanAttribute(logs, quickFilterAttributes, 'alpha', spanStage));

      await test.step('The filter lands in the Spans view, and only there', async () => {
        await expect
          .poll(() => logs.readFilterRows('spans'))
          .toEqual([metadataRow(spanStage.key, spanStage.value)]);
        // The origin view's own filters must be left alone — asserted as
        // "never written" rather than "empty", which an overwrite would satisfy.
        expect(logs.readFilterRows('traces')).toBeNull();
        // The destination shows a fresh result set, so the page the user was on
        // would land them past the end of it.
        expect(logs.currentPageParam()).toBe(1);
      });

      await test.step('The Logs type toggle has moved to Spans', async () => {
        // The panel is modal — the rest of the page is aria-hidden until it closes.
        await panel.close();
        await expect(logs.spansTab).toHaveAttribute('aria-checked', 'true');
        await expect(logs.tracesTab).toHaveAttribute('aria-checked', 'false');
      });

      await test.step('The Spans table holds exactly the matching spans', async () => {
        await logs.waitForSpansReady(stageSpanIds[0]);
        await expect(logs.spanRows).toHaveCount(stageSpanIds.length);
        for (const spanId of stageSpanIds) {
          await expect(logs.spanRow(spanId)).toBeVisible();
        }
        // The sibling `generate` spans share every other attribute, so they are
        // what separates a real filter from a table that merely re-rendered.
        for (const trace of traces) {
          await expect(logs.spanRow(trace.spans.generate.id)).toBeHidden();
        }
        expect(await logs.countSpans()).toBe(stageSpanIds.length);
      });
    },
  );

  test(
    'A handed-off filter appends to the destination and a repeat of it does not duplicate',
    { tag: ['@cap:traces.toggle-spans-view'] },
    async ({ quickFilterAttributes, project, page }) => {
      const logs = new LogsPage(page);
      const { spanStage, spanOwner, stageSpanIds, stageAndOwnerSpanId, traces } =
        quickFilterAttributes;
      const stageRow = metadataRow(spanStage.key, spanStage.value);
      const ownerRow = metadataRow(spanOwner.key, spanOwner.value);

      await test.step('Hand off a first span filter from the Traces tab', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        const panel = await handOffSpanAttribute(logs, quickFilterAttributes, 'alpha', spanStage);
        await panel.close();

        await expect.poll(() => logs.readFilterRows('spans')).toEqual([stageRow]);
        await logs.waitForSpansReady(stageSpanIds[0]);
        await expect(logs.spanRows).toHaveCount(stageSpanIds.length);
      });

      await test.step('Return to the Traces tab, which is still unfiltered', async () => {
        await logs.switchLogsType('traces');
        await logs.waitForReady();
        expect(logs.readFilterRows('traces')).toBeNull();
        await expect(logs.traceRows).toHaveCount(traces.length);
        // The handed-off filter is held for the Spans view, not thrown away.
        expect(logs.readFilterRows('spans')).toEqual([stageRow]);
      });

      await test.step('Hand off a second filter, on a key the first knows nothing about', async () => {
        const panel = await handOffSpanAttribute(logs, quickFilterAttributes, 'beta', spanOwner);
        await panel.close();

        // Appended, not overwritten: the first row survives, in place.
        await expect.poll(() => logs.readFilterRows('spans')).toEqual([stageRow, ownerRow]);
        await logs.waitForSpansReady(stageAndOwnerSpanId);
        await expect(logs.spanRows).toHaveCount(1);
        await expect(logs.spanRow(stageAndOwnerSpanId)).toBeVisible();
        expect(await logs.countSpans()).toBe(1);
      });

      await test.step('Repeating the identical handoff leaves the filter and the table unchanged', async () => {
        await logs.switchLogsType('traces');
        await logs.waitForReady();
        const panel = await handOffSpanAttribute(logs, quickFilterAttributes, 'beta', spanOwner);
        await panel.close();

        await expect.poll(() => logs.readFilterRows('spans')).toEqual([stageRow, ownerRow]);
        await logs.waitForSpansReady(stageAndOwnerSpanId);
        await expect(logs.spanRows).toHaveCount(1);
        expect(await logs.countSpans()).toBe(1);
      });
    },
  );
});
