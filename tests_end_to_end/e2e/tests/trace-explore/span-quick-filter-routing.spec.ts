import { test, expect, QUICK_FILTER_SEED, TRACE_PROVIDER_METADATA_VALUE } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The Logs quick filter routes to the table of the SELECTED entity
 * (OPIK-8105, opik#8684).
 *
 * The regression this guards is the quiet kind. Before the fix, clicking the
 * filter icon on a selected span's attribute wrote the filter to the Traces
 * table, which the user was still looking at — so the page narrowed to a
 * plausible but unrelated result set instead of failing. Every assertion here
 * pins an exact id or an exact wire shape rather than a row count, because a
 * count is the one thing both the right and the wrong answer can share.
 *
 * `logsType` is persisted per project in localStorage, so which tab is active
 * is asserted rather than assumed; the exploration for this PR saw two false
 * failures from a leaked Spans tab alone.
 */
test.describe('Logs quick filter — routing', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  const METADATA = 'Metadata';

  test("a selected span's metadata attribute filters the Spans table, not the Traces table", { tag: ['@cap:traces.filter-traces'] }, async ({
    quickFilterLogs,
    page,
  }) => {
    const seed = quickFilterLogs;
    const logs = new LogsPage(page);

    await test.step('Open Logs with the Traces table active', async () => {
      await logs.goto(seed.projectId);
      await logs.waitForReady();
      expect(await logs.activeLogsTab(), 'the tab the view opened on').toBe('traces');
    });

    const panel = await test.step('Open trace alpha and select its child span', async () => {
      const panel = await logs.openTraceById(seed.alpha.traceId);
      await panel.waitForFullyLoaded();
      await panel.selectSpan(seed.alpha.spanName);
      return panel;
    });

    await test.step("The icon announces it will filter the span's own table", async () => {
      await expect(
        panel.quickFilterButton(METADATA, 'component'),
        'the quick-filter icon on the span\'s "component" attribute',
      ).toHaveAttribute('aria-label', 'Filter in Spans table');
    });

    await test.step('Clicking it writes a metadata filter to the Spans table only', async () => {
      await panel.applyQuickFilter(METADATA, 'component');

      const spanFilters = await logs.waitForUrlFilters('spans', (f) => f.length === 1);
      expect(spanFilters[0], 'the filter the Spans table received').toMatchObject({
        field: 'metadata',
        type: 'dictionary',
        key: 'component',
        operator: 'contains',
        value: QUICK_FILTER_SEED.alpha.component,
      });

      // The whole point of the fix: the table the user was looking at must be
      // left alone. Absent, not empty — an empty array would be a write.
      expect(
        await logs.readUrlFilters('traces'),
        'the Traces table must not have been filtered',
      ).toBeNull();
    });

    await test.step('…flips the view to Spans and pins the Metadata chip', async () => {
      expect(await logs.activeLogsTab(), 'the tab after the filter was applied').toBe('spans');
      await expect(logs.filterChip('metadata'), 'the pinned Metadata chip').toHaveText(
        `Metadata: component contains ${QUICK_FILTER_SEED.alpha.component}`,
      );
    });

    await test.step('The Spans table holds exactly span alpha', async () => {
      await panel.close();
      await expect(logs.spanRow(seed.alpha.spanId), 'span alpha').toHaveCount(1);
      await expect(logs.spanRow(seed.beta.spanId), 'span beta is excluded').toHaveCount(0);
      // The whole table, not just "mine is in it": a filter that leaked would
      // still show alpha.
      expect(await logs.readRowIdsOnPage(), 'every row the filtered table holds').toEqual([
        seed.alpha.spanId,
      ]);
    });
  });

  test('with no span selected the same gesture filters the Traces table', { tag: ['@cap:traces.filter-traces'] }, async ({
    quickFilterLogs,
    page,
  }) => {
    const seed = quickFilterLogs;
    const logs = new LogsPage(page);

    // The control for the test above. Without it, "the filter went to the Spans
    // table" is satisfied by a build that sends every quick filter there.
    const panel = await test.step('Open trace alpha with the trace itself selected', async () => {
      await logs.goto(seed.projectId);
      await logs.waitForReady();
      expect(await logs.activeLogsTab(), 'the tab the view opened on').toBe('traces');

      const panel = await logs.openTraceById(seed.alpha.traceId);
      await panel.waitForFullyLoaded();
      // Empty rather than absent: the panel writes the param and leaves it
      // blank until a tree node is picked, and `useLogsQuickAttributeFilter`
      // reads exactly that — a blank `span` means the trace is the selection.
      expect(
        new URL(page.url()).searchParams.get('span') ?? '',
        'no span is selected',
      ).toBe('');

      await expect(
        panel.quickFilterButton(METADATA, 'tenant'),
        "the icon on the trace's own attribute",
      ).toHaveAttribute('aria-label', 'Filter by this attribute');

      await panel.applyQuickFilter(METADATA, 'tenant');
      return panel;
    });

    await test.step('The filter lands on the Traces table, and the Spans table is untouched', async () => {
      const traceFilters = await logs.waitForUrlFilters('traces', (f) => f.length === 1);
      expect(traceFilters[0], 'the filter the Traces table received').toMatchObject({
        field: 'metadata',
        type: 'dictionary',
        key: 'tenant',
        operator: 'contains',
        value: QUICK_FILTER_SEED.alpha.tenant,
      });
      expect(
        await logs.readUrlFilters('spans'),
        'the Spans table must not have been filtered',
      ).toBeNull();
      expect(await logs.activeLogsTab(), 'the tab stays on Traces').toBe('traces');
    });

    await test.step('The Traces table narrows to trace alpha', async () => {
      // Closed, not re-navigated: `openTraceById` goes to a URL carrying only
      // `trace=`, which would drop the `traces_filters` param the click just
      // wrote and leave this asserting against an unfiltered table.
      await panel.close();
      expect(await logs.readRowIdsOnPage(), 'every row the filtered table holds').toEqual([
        seed.alpha.traceId,
      ]);
    });
  });

  // Both keys, because this test asserts both things: the filter the quick
  // filter builds (`filter-traces`, as its two siblings above do) AND the Spans
  // view it flips to, whose chip bar gains a pinned Provider column
  // (`toggle-spans-view`). Tagged with the toggle alone, the routing assertion
  // that is the point of the test would be coverage the map cannot see.
  test("a span's provider attribute filters through the provider column and pins its chip", { tag: ['@cap:traces.filter-traces', '@cap:traces.toggle-spans-view'] }, async ({
    quickFilterLogs,
    page,
  }) => {
    const seed = quickFilterLogs;
    const logs = new LogsPage(page);

    await test.step('Open Logs with the Traces table active and select the span', async () => {
      await logs.goto(seed.projectId);
      await logs.waitForReady();
      expect(await logs.activeLogsTab(), 'the tab the view opened on').toBe('traces');
    });

    const panel = await test.step('Open trace alpha and select its child span', async () => {
      const panel = await logs.openTraceById(seed.alpha.traceId);
      await panel.waitForFullyLoaded();
      await panel.selectSpan(seed.alpha.spanName);
      return panel;
    });

    await test.step('The Provider chip is not pinned yet — the precondition for the pin assertion', async () => {
      // `provider` is absent from the Spans bar's defaults (`type`, `tags`,
      // `with_errors`, `metadata`), which is what makes pinning observable at
      // all — a chip already pinned would satisfy the assertion below without
      // the filter having done anything. Asserted both ways: not in the stored
      // set, and no chip on the bar.
      // `null` here would mean nothing has been stored yet and the bar is
      // showing its defaults, which do not include provider either; both are
      // legitimate pre-states and what matters is only that provider is not
      // among them.
      expect(
        (await logs.readPinnedChipIds('spans')) ?? [],
        'provider is not pinned for the Spans table yet',
      ).not.toContain('provider');
      await expect(logs.filterChip('provider'), 'the Provider chip').toHaveCount(0);
    });

    await test.step('The provider attribute offers a filter, labelled for the Spans table', async () => {
      await expect(
        panel.quickFilterButton(METADATA, 'provider'),
        "the icon on the span's provider attribute",
      ).toHaveAttribute('aria-label', 'Filter in Spans table');
      await panel.applyQuickFilter(METADATA, 'provider');
    });

    await test.step('It targets the dedicated provider column, not metadata', async () => {
      const spanFilters = await logs.waitForUrlFilters('spans', (f) => f.length === 1);
      const applied = spanFilters[0];
      expect(applied, 'the filter the Spans table received').toMatchObject({
        field: 'provider',
        type: 'string',
        operator: 'contains',
        value: QUICK_FILTER_SEED.alpha.provider,
      });
      // A dedicated string column takes no key. A build that fell back to the
      // metadata target would carry `key: "provider"` and still filter to the
      // same single row here, so the absent key is the assertion that
      // distinguishes them.
      expect(applied.key ?? '', 'a string-column filter carries no key').toBe('');

      expect(
        await logs.readUrlFilters('traces'),
        'the Traces table must not have been filtered',
      ).toBeNull();
      expect(await logs.activeLogsTab(), 'the tab after the filter was applied').toBe('spans');
    });

    await test.step('The Provider chip is now pinned and reads the applied filter', async () => {
      const pinnedAfter = await logs.readPinnedChipIds('spans');
      // Not defaulted away: after a pin the store MUST have been written, so an
      // absent entry is a real failure and not a "defaults still apply".
      expect(pinnedAfter, 'the Spans pinned set was written').not.toBeNull();
      expect(pinnedAfter, 'the pinned set gained provider').toContain('provider');
      await expect(logs.filterChip('provider'), 'the pinned Provider chip').toHaveText(
        `Provider: contains ${QUICK_FILTER_SEED.alpha.provider}`,
      );
    });

    await test.step('The Spans table holds exactly the alpha span', async () => {
      await panel.close();
      expect(await logs.readRowIdsOnPage(), 'every row the filtered table holds').toEqual([
        seed.alpha.spanId,
      ]);
    });

    await test.step("A trace's own provider metadata key offers no filter at all", async () => {
      // Traces have no provider column, so the resolver must refuse this key —
      // while a sibling key on the very same block still offers one, which is
      // what shows the refusal is per-attribute and not a dead block.
      const tracePanel = await logs.openTraceById(seed.alpha.traceId);
      await tracePanel.waitForFullyLoaded();

      await expect(
        tracePanel.attributeLine(METADATA, 'provider'),
        "the trace's provider attribute is rendered",
      ).toHaveCount(1);
      await expect(
        tracePanel.attributeLine(METADATA, 'provider'),
        'and it carries the seeded value',
      ).toContainText(TRACE_PROVIDER_METADATA_VALUE);
      await expect(
        tracePanel.quickFilterButton(METADATA, 'provider'),
        "no filter action on the trace's provider key",
      ).toHaveCount(0);
      await expect(
        tracePanel.quickFilterButton(METADATA, 'stage'),
        'but its sibling key still has one',
      ).toHaveCount(1);
    });
  });
});
