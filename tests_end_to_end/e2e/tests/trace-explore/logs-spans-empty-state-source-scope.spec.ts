import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * What the Logs Spans tab says when it has no rows to show, and the
 * source-scoped existence probe that decides it (opik#9599, OPIK-9219).
 *
 * 9599 bounded `GET /v1/private/spans/exists` to the weeks its project holds
 * rows in. That probe has exactly one consumer, and it is not a statistic: the
 * Spans tab picks between two visually different empty states from its answer
 * alone —
 *
 *   showEmptyState = !isTableLoading && !hasProjectData && rows.length === 0 && page === 1
 *
 * where `hasProjectData` is `useTracesOrSpansExist` with
 * `source: LOGS_SOURCE.sdk`. So the probe answering `false` renders the SDK
 * ONBOARDING panel — "No spans yet", a Quickstart guide, "Spans will appear
 * here once your agent starts receiving requests" — while the same empty list
 * with `true` renders "No matching results". Those are opposite claims, and
 * showing the first to someone whose project is full of spans reads as data
 * loss rather than as a read that matched nothing. A probe narrowed to the
 * wrong weeks is precisely how that happens.
 *
 * ## What the estate asserted before this
 *
 * Nothing on either half. `pom/logs.page.ts` knew one empty-state string,
 * "No traces yet", used as a race target inside `waitForReady()` and never
 * asserted; no spec read the Spans tab's empty state at all, and no spec called
 * the exists endpoint. `toggle-spans-view` pages a populated table and
 * `logs-open-ended-window` is about the date window the KPI cards read over —
 * both need rows to exist, so neither can reach a branch that only runs when
 * the table is empty.
 *
 * ## The three answers, and why the third is the point
 *
 * `source` is what makes this endpoint more than a cheaper `size=1` listing,
 * and a project whose only span is non-sdk is the only seed that shows it:
 * `source=sdk` must answer no over the very rows an unscoped probe answers yes
 * over. The page is then expected to show the onboarding panel — deliberate,
 * per `TracesSpansTab`'s own comment, because its listing is hard-coded to
 * `logsSource: LOGS_SOURCE.sdk` and a user whose project holds only
 * experiment/playground/optimization/evaluator traffic has logged nothing
 * themselves. Asserting it is what stops that from being quietly changed into
 * "No matching results", which would tell that user their own read had been
 * filtered.
 *
 * Note `Source.legacyFallbackDbValue`: `sdk` also matches legacy `unknown`
 * rows, so the scope is not simply `source = 'sdk'` and the experiment span
 * must not be swept in by it.
 *
 * ## Why the fourth state is reached with a search and not with old spans
 *
 * The release's own question was a project whose spans are all older than the
 * probe's window. That is not seedable here: staging runs UUID timestamp
 * validation in reject mode with a `PT24H` window, so any id outside ±24h is
 * refused `400 Invalid UUID for id` — which is also why the estate's
 * `id-aged-spans` and `uuid-window-guard` specs skip on this deployment. The
 * branch does not need an old span, though: it needs `hasProjectData && rows
 * === 0`, and an unmatchable search term puts a present-day project there
 * deterministically. What is asserted is the DISTINCTION, which is the part a
 * mis-scoped probe breaks.
 */
test.describe('Logs — the Spans tab’s empty states and the source-scoped exists probe', {
  tag: ['@t2-cuj', '@area:traces'],
}, () => {
  test(
    'the exists probe answers per project and per source',
    { tag: ['@cap:traces.spans-exists-empty-state'] },
    async ({ spansEmptyStateProjects, backendClient }) => {
      const { sdkLogged, empty, nonSdkOnly } = spansEmptyStateProjects;

      await test.step('A project with an sdk span answers yes, scoped and unscoped', async () => {
        expect(
          await backendClient.spansExist({ projectId: sdkLogged.id, source: 'sdk' }),
          `${sdkLogged.name} holds one source=sdk span`,
        ).toBe(true);
        expect(
          await backendClient.spansExist({ projectId: sdkLogged.id }),
          `${sdkLogged.name} holds a span under some source`,
        ).toBe(true);
      });

      await test.step('A project with nothing answers no, scoped and unscoped', async () => {
        expect(
          await backendClient.spansExist({ projectId: empty.id, source: 'sdk' }),
          `${empty.name} is empty`,
        ).toBe(false);
        expect(
          await backendClient.spansExist({ projectId: empty.id }),
          `${empty.name} is empty under every source`,
        ).toBe(false);
      });

      await test.step('A project with only a non-sdk span answers differently per source', async () => {
        // The whole reason the endpoint takes a `source`. Both halves are
        // required: the `true` alone would be satisfied by an unscoped probe
        // that ignores the parameter, and the `false` alone by a probe that
        // finds nothing in this project at all.
        expect(
          await backendClient.spansExist({ projectId: nonSdkOnly.id, source: 'sdk' }),
          `${nonSdkOnly.name} holds no sdk span, and 'sdk' must not sweep in its experiment row ` +
            'through the legacy `unknown` fallback',
        ).toBe(false);
        expect(
          await backendClient.spansExist({ projectId: nonSdkOnly.id }),
          `${nonSdkOnly.name} holds one span under source=experiment`,
        ).toBe(true);
        expect(
          await backendClient.spansExist({ projectId: nonSdkOnly.id, source: 'experiment' }),
          `${nonSdkOnly.name}'s span is findable under its own source`,
        ).toBe(true);
      });
    },
  );

  test(
    'the Spans tab shows the onboarding panel only when the project has no sdk spans',
    { tag: ['@cap:traces.spans-exists-empty-state'] },
    async ({ spansEmptyStateProjects, page }) => {
      const { sdkLogged, empty, nonSdkOnly, timeRangePreset } = spansEmptyStateProjects;
      const logs = new LogsPage(page);

      await test.step('The sdk-logged project renders its span row and no empty state', async () => {
        await logs.gotoSpans(sdkLogged.id, { timeRange: timeRangePreset });
        await logs.waitForSpansReady();
        // By id: the row's `data-row-id` is the span's own id, so this cannot
        // be satisfied by some other row the shared workspace happens to hold.
        await expect(
          page.locator(`tr[data-row-id="${sdkLogged.spanId}"]`),
          'the seeded sdk span renders exactly once',
        ).toHaveCount(1);
        await expect(
          logs.onboardingEmptyState('spans'),
          'a project with an sdk span must not be told it has none',
        ).toBeHidden();
        await expect(logs.noMatchingResults).toBeHidden();
      });

      await test.step('The empty project renders the onboarding panel', async () => {
        await logs.gotoSpans(empty.id, { timeRange: timeRangePreset });
        await logs.waitForSpansReady();
        await expect(logs.onboardingEmptyState('spans')).toBeVisible();
        await expect(
          logs.quickstartGuideAction,
          'the onboarding panel is identified by its own action as well as its heading',
        ).toBeVisible();
        await expect(logs.noMatchingResults).toBeHidden();
        await expect(page.locator('tr[data-row-id]')).toHaveCount(0);
      });

      await test.step('The non-sdk project renders the onboarding panel, not its experiment span', async () => {
        await logs.gotoSpans(nonSdkOnly.id, { timeRange: timeRangePreset });
        await logs.waitForSpansReady();
        await expect(logs.onboardingEmptyState('spans')).toBeVisible();
        await expect(logs.quickstartGuideAction).toBeVisible();
        // Not "No matching results": the user has not filtered anything, and
        // saying so would describe their own read as narrowed.
        await expect(logs.noMatchingResults).toBeHidden();
        await expect(
          page.locator(`tr[data-row-id="${nonSdkOnly.spanId}"]`),
          'the experiment-source span is outside the Spans tab’s sdk-scoped listing',
        ).toHaveCount(0);
        await expect(page.locator('tr[data-row-id]')).toHaveCount(0);
      });
    },
  );

  test(
    'a project that has spans but matched none says so, rather than showing the onboarding panel',
    { tag: ['@cap:traces.spans-exists-empty-state'] },
    async ({ spansEmptyStateProjects, page }) => {
      const { sdkLogged, timeRangePreset, unmatchableSearch } = spansEmptyStateProjects;
      const logs = new LogsPage(page);

      await test.step('Open the Spans tab with a search nothing matches', async () => {
        await logs.gotoSpans(sdkLogged.id, {
          timeRange: timeRangePreset,
          search: unmatchableSearch,
        });
        await logs.waitForSpansReady();
      });

      await test.step('The table is empty and says "No matching results"', async () => {
        await expect(page.locator('tr[data-row-id]')).toHaveCount(0);
        await expect(logs.noMatchingResults).toBeVisible();
        // The claim of the whole spec: same empty table, different answer,
        // because the project HAS spans.
        await expect(
          logs.onboardingEmptyState('spans'),
          'a project that has sdk spans must never be shown the "no spans yet" onboarding panel',
        ).toBeHidden();
        await expect(logs.quickstartGuideAction).toBeHidden();
      });

      await test.step('Clearing the search brings the row back', async () => {
        // So the previous step is a statement about the SEARCH and not about a
        // project that had quietly lost its span by now.
        await logs.gotoSpans(sdkLogged.id, { timeRange: timeRangePreset });
        await logs.waitForSpansReady();
        await expect(page.locator(`tr[data-row-id="${sdkLogged.spanId}"]`)).toHaveCount(1);
      });
    },
  );
});
