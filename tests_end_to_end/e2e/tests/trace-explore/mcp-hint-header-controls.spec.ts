import type { Page } from '@playwright/test';
import { test, expect } from '@e2e/fixtures';
import type { SdkClient } from '@e2e/core/sdk';
import { LogsPage } from '@e2e/pom/logs.page';
import type { TracePanelPage } from '@e2e/pom/trace-panel.page';

/**
 * The "Fix via MCP" pill sits in a sticky rail pinned to the top-right of the
 * trace panel. The rail itself is careful — zero height, `pointer-events: none`,
 * so the header controls underneath stay clickable at any scroll. The pill is
 * the one thing in it that takes pointer events back, and every `CodeBlock`
 * section header carries its Search and Copy icons at the same x. Scroll an
 * expanded error far enough that its header rides up into the pill's band, and
 * the pill is on top of those icons.
 *
 * What the user gets is a popover they did not ask for instead of the control
 * they clicked. Nothing errors, nothing is logged, and the gesture is the exact
 * one the feature advertises — read the traceback, then search it.
 *
 * Verified by hand on staging at 2.2.67, A/B at one scroll position against
 * another on the same trace with the same error expanded (see OPIK-7791):
 *
 *   scrollTop 0   -> the hit target is the Search icon; search opens
 *   scrolled up   -> the hit target is BUTTON[mcp-hint-button]; search does not
 *
 * The band is narrow (~10-20px of travel), Logs-page only, and only on a trace
 * whose Error section is expanded — so the scroll position is derived from the
 * two boxes at run time rather than hard-coded to the viewport this was written
 * against.
 *
 * `mcp-hint.spec.ts` is the sibling for the pill's own behaviour: when it
 * appears, what its popover says. It never scrolls, so this cannot be folded
 * into one of its assertions.
 */

const ERROR_INFO = {
  exception_type: 'PermissionDeniedError',
  message: 'Error code: 403 - key limit exceeded (total limit)',
  traceback: Array.from(
    { length: 40 },
    (_, line) => `  File "app.py", line ${line + 1}, in step_${line}\n    raise PermissionDeniedError()`,
  ).join('\n'),
};

/** The descriptor {@link TracePanelPage.hitTargetAt} answers for the Search icon. */
const SEARCH_ICON = 'BUTTON[Search]';

/**
 * A fixed viewport, because this is a geometry claim: the pill's band and the
 * section header's controls have to be able to overlap in x at all, and at a
 * narrow enough width the header row wraps and they never do.
 */
test.use({ viewport: { width: 1600, height: 1000 } });

/**
 * Seed a trace whose panel overflows, open it, expand the error and wait for the
 * pill to stop moving. Returns the panel with everything in place.
 *
 * The payloads are deliberately large: without enough content the viewer does
 * not scroll at all, and a spec about what happens when it is scrolled would
 * quietly assert nothing.
 */
async function openScrollableFailingTrace(args: {
  sdkClient: SdkClient;
  project: { id: string; name: string };
  traceName: string;
  page: Page;
}): Promise<TracePanelPage> {
  const trace = await args.sdkClient.python.createNestedTrace({
    project_name: args.project.name,
    name: args.traceName,
    input: { question: 'x'.repeat(4000) },
    output: { answer: 'y'.repeat(4000) },
    error_info: ERROR_INFO,
    spans: [],
  });

  const logs = new LogsPage(args.page);
  await logs.goto(args.project.id);
  await logs.waitForReady();
  const panel = await logs.openTraceById(trace.id);
  await panel.waitForFullyLoaded();
  await panel.expandError();
  await panel.waitForMcpHintSettled();
  return panel;
}

test.describe('Trace Explore — MCP hint rail vs section header controls', {
  tag: ['@t2-cuj', '@area:traces'],
}, () => {
  /**
   * The control, and the guard for the known failure below.
   *
   * Everything about the gesture EXCEPT the scroll, asserted normally so it
   * fails loudly. Its counterpart carries a blanket `test.fail()`, which would
   * otherwise report a seed that never produced a pill, a panel that never
   * rendered, or a POM looking at the wrong element as the "expected" failure
   * and keep reporting green while testing nothing.
   */
  test(
    'The Error header Search icon is clickable while the panel is at the top',
    { tag: ['@cap:traces.mcp-hint-on-trace-error'] },
    async ({ project, sdkClient, testNamespace, page }) => {
      const panel = await test.step('Open a failing trace and reveal the hint', async () =>
        openScrollableFailingTrace({
          sdkClient,
          project,
          page,
          traceName: `${testNamespace}-unscrolled`,
        }));

      await test.step('The pill and the Search icon really can collide in x', async () => {
        // The precondition the whole scenario rests on. At a width where the
        // pill sits clear of the header controls there is no blocked position
        // to find, and the sibling test below would pass for the wrong reason.
        const pill = await panel.mcpHintButton.boundingBox();
        const search = await panel.sectionSearchButton('Error').boundingBox();
        expect(pill, 'the MCP hint pill must have a box to measure').not.toBeNull();
        expect(search, 'the Error section must render a Search icon').not.toBeNull();

        const overlapsHorizontally =
          pill!.x < search!.x + search!.width && search!.x < pill!.x + pill!.width;
        expect(
          overlapsHorizontally,
          'the pill and the Error header Search icon must share an x range for this to be testable',
        ).toBe(true);
      });

      await test.step('The panel can scroll the Error header up into that band', async () => {
        // The other half of the precondition, asserted here rather than in the
        // known-failure test below — where a blanket `test.fail()` would report
        // "the seed was too short to scroll" as the expected failure and keep
        // the run green while the real gesture went untested.
        const pill = (await panel.mcpHintButton.boundingBox())!;
        const search = (await panel.sectionSearchButton('Error').boundingBox())!;
        const travel = search.y + search.height / 2 - (pill.y + pill.height / 2);
        expect(travel, 'the Error header must start below the pill').toBeGreaterThan(0);

        const reached = await panel.scrollDataViewerTo('Error', travel);
        expect(
          reached,
          'the seeded trace must be tall enough to scroll the header into the pill band',
        ).toBeGreaterThanOrEqual(Math.floor(travel));

        await panel.scrollDataViewerTo('Error', 0);
      });

      await test.step('At the top of the panel the Search icon takes the click', async () => {
        const search = (await panel.sectionSearchButton('Error').boundingBox())!;
        const hit = await panel.hitTargetAt(
          search.x + search.width / 2,
          search.y + search.height / 2,
        );
        expect(hit, 'nothing should be covering the Search icon at scrollTop 0').toBe(SEARCH_ICON);
      });

      await test.step('And clicking it opens the section find box', async () => {
        await panel.sectionSearchButton('Error').click();
        await expect(panel.sectionSearchInput('Error')).toBeVisible();
      });
    },
  );

  /**
   * Known failure — the defect this spec exists for.
   *
   * Asserted against the CORRECT behaviour and marked `test.fail()`, so it
   * reports as an expected failure while the bug is open and then fails with
   * "Expected to fail, but passed" once it is fixed — prompting removal of the
   * annotation. Any fix makes it pass: moving the pill, giving the rail a gap,
   * or handing the pointer back. The assertion names the element that took the
   * click, so a failure reads as the defect rather than as a timeout.
   *
   * Kept deliberately minimal, since a blanket `test.fail()` swallows every
   * failure in its own test. The control above covers the rest.
   */
  test(
    'The Error header Search icon is still clickable once the panel is scrolled',
    { tag: ['@cap:traces.mcp-hint-on-trace-error'] },
    async ({ project, sdkClient, testNamespace, page }) => {
      test.fail();

      const panel = await test.step('Open a failing trace and reveal the hint', async () =>
        openScrollableFailingTrace({
          sdkClient,
          project,
          page,
          traceName: `${testNamespace}-scrolled`,
        }));

      await test.step('Scroll until the Error header rides up into the pill band', async () => {
        const pill = (await panel.mcpHintButton.boundingBox())!;
        const search = (await panel.sectionSearchButton('Error').boundingBox())!;
        // Derived, not hard-coded: how far the header has to rise to reach the
        // pill's row is a function of this viewport and this panel's layout.
        const travel =
          search.y + search.height / 2 - (pill.y + pill.height / 2);
        await panel.scrollDataViewerTo('Error', travel);
      });

      await test.step('The Search icon still takes the click', async () => {
        const search = (await panel.sectionSearchButton('Error').boundingBox())!;
        const hit = await panel.hitTargetAt(
          search.x + search.width / 2,
          search.y + search.height / 2,
        );
        expect(
          hit,
          'the MCP hint pill must not intercept the Error header controls it scrolls over',
        ).toBe(SEARCH_ICON);
      });
    },
  );
});
