import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';
import type { TracePanelPage } from '@e2e/pom/trace-panel.page';

/**
 * This release made the panel's shared `CodeBlock` *optionally* controlled, and
 * then wired exactly one of its instances up: on the Logs page the Error section
 * takes its open state from the panel, while Input, Output, Metadata and the
 * rest keep owning theirs. That split is the whole subject here.
 *
 * It has no coverage at all. `trace-explore-smoke.spec.ts` asserts that Input
 * and Output are *visible*; nothing in the estate has ever toggled a code block,
 * let alone checked that a controlled one and its uncontrolled siblings behave
 * differently in the same panel.
 *
 * The two things a split like this gets wrong are both silent. A block that was
 * meant to stay uncontrolled but reads the panel's state collapses when the user
 * opens the error — no error, just a section that shut itself. A controlled
 * block that keeps a shadow copy of its state drifts: the panel says collapsed,
 * the block renders expanded, and the next node inherits whatever the last one
 * was left at. The last two tests are for the drift, and the first for the
 * split.
 *
 * Deterministic and API-seedable throughout. Every assertion reads
 * `aria-expanded` off the section's own header, which is the same value the
 * chevron renders from.
 */

const ERROR_INFO = {
  exception_type: 'RuntimeError',
  message: 'the model returned an unparseable payload',
  traceback:
    'Traceback (most recent call last):\n  File "app.py", line 44, in run\n    raise RuntimeError()',
};

/** The sections the Details tab renders for a node carrying input, output and metadata. */
const UNCONTROLLED_SECTIONS = ['Input', 'Output', 'Metadata'] as const;

async function expectSectionStates(
  panel: TracePanelPage,
  expected: Record<string, 'true' | 'false'>,
): Promise<void> {
  for (const [title, state] of Object.entries(expected)) {
    await expect(
      panel.sectionHeader(title),
      `the ${title} section should report aria-expanded="${state}"`,
    ).toHaveAttribute('aria-expanded', state);
  }
}

/**
 * Toggle a section twice and assert it lands on the opposite state and then back
 * on the original one.
 *
 * Both halves matter: a section that only ever opens passes a single toggle, and
 * a section wired to a constant passes a "returned to where it started" check
 * that never looked in between.
 */
async function expectTogglesBothWays(
  panel: TracePanelPage,
  title: string,
  startsExpanded: boolean,
): Promise<void> {
  const start = startsExpanded ? 'true' : 'false';
  const flipped = startsExpanded ? 'false' : 'true';

  await panel.toggleSection(title);
  await expect(
    panel.sectionHeader(title),
    `${title} should be aria-expanded="${flipped}" after one toggle`,
  ).toHaveAttribute('aria-expanded', flipped);

  await panel.toggleSection(title);
  await expect(
    panel.sectionHeader(title),
    `${title} should be back at aria-expanded="${start}" after a second toggle`,
  ).toHaveAttribute('aria-expanded', start);
}

test.describe('Trace Explore — trace panel section toggles', {
  tag: ['@t2-cuj', '@area:traces'],
}, () => {
  test(
    'Sections open and close independently, and only the Error section starts collapsed',
    { tag: ['@cap:traces.open-trace-panel'] },
    async ({ project, sdkClient, backendClient, testNamespace, page }) => {
      const trace = await test.step('Seed a failing trace with a failing child span', async () => {
        const seeded = await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${testNamespace}-toggles`,
          input: { question: 'why did this call fail?' },
          output: { answer: 'it did not get that far' },
          metadata: { retries: 2, region: 'eu-west-1' },
          error_info: ERROR_INFO,
          spans: [],
        });
        // The span's error is seeded through the backend client because the
        // bridge's nested-trace route can set `error_info` on a trace but not on
        // one of its spans — and the Error section is per-selected-node, so a
        // span with no error of its own renders no Error section to toggle.
        await backendClient.createSpan({
          id: uuid7(),
          traceId: seeded.id,
          projectName: project.name,
          name: `${testNamespace}-failing-span`,
          source: 'sdk',
          input: { prompt: 'why did this call fail?' },
          output: { completion: 'it did not get that far' },
          metadata: { attempt: 2 },
          errorInfo: {
            exceptionType: ERROR_INFO.exception_type,
            message: ERROR_INFO.message,
            traceback: ERROR_INFO.traceback,
          },
        });
        return seeded;
      });

      const logs = new LogsPage(page);
      const panel = await test.step('Open the trace', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        const opened = await logs.openTraceById(trace.id);
        await opened.waitForFullyLoaded();
        return opened;
      });

      await test.step('The trace opens with its content expanded and its error closed', async () => {
        await expectSectionStates(panel, {
          Input: 'true',
          Output: 'true',
          Metadata: 'true',
          Error: 'false',
        });
      });

      for (const title of UNCONTROLLED_SECTIONS) {
        await test.step(`The trace's ${title} section toggles both ways`, async () => {
          await expectTogglesBothWays(panel, title, true);
        });
      }

      await test.step("The trace's Error section toggles both ways", async () => {
        await expectTogglesBothWays(panel, 'Error', false);
      });

      const spanPanel = await test.step("Select the failing span with every section left off its default", async () => {
        // Every section is moved AWAY from the state it opens in, and that is
        // the only reason this step does more than click a span. Both loops
        // above finish by restoring each section to where it started, so a span
        // selected straight after them would be asserted against exactly the
        // states the trace is already showing — and the assertion below could
        // not tell a reset from an inheritance.
        //
        // So Error is opened (it defaults closed) and the uncontrolled three
        // are closed (they default open). Now the two halves of the split
        // disagree, and the next step can name which is which.
        await panel.toggleSection('Error');
        for (const title of UNCONTROLLED_SECTIONS) {
          await panel.toggleSection(title);
        }
        await expectSectionStates(panel, {
          Input: 'false',
          Output: 'false',
          Metadata: 'false',
          Error: 'true',
        });
        await panel.selectSpan(`${testNamespace}-failing-span`);
        return panel;
      });

      await test.step('The controlled Error section resets for the span; the uncontrolled three do not', async () => {
        // This is the split, asserted at the one moment it is observable.
        //
        // `CodeBlock` is *optionally* controlled — `isOpen = open ?? useState(defaultOpen)`.
        // The panel passes `open` for Error only, so Error is reset from the
        // selected node and comes back collapsed. Input/Output/Metadata read
        // their own `useState`, which survives because the component stays
        // mounted across a node change, so they arrive still carrying what the
        // user left them at on the trace.
        //
        // Both halves are deliberate and this asserts them as written, not as
        // one might wish: collapsing Input to get it out of the way while
        // walking a span tree is meant to stick, and the error traceback is
        // meant not to. An Input that came back expanded here would mean the
        // uncontrolled sections had started resetting per node; an Error that
        // came back expanded would mean the control had been dropped and the
        // next node inherits a spent MCP hint — the drift this spec exists for.
        await expectSectionStates(spanPanel, {
          Input: 'false',
          Output: 'false',
          Metadata: 'false',
          Error: 'false',
        });
      });

      // Back to their defaults, so the toggle loops below start from the state
      // their `startsExpanded` argument claims.
      await test.step("Reopen the span's uncontrolled sections", async () => {
        for (const title of UNCONTROLLED_SECTIONS) {
          await spanPanel.toggleSection(title);
        }
        await expectSectionStates(spanPanel, {
          Input: 'true',
          Output: 'true',
          Metadata: 'true',
        });
      });

      for (const title of UNCONTROLLED_SECTIONS) {
        await test.step(`The span's ${title} section toggles both ways`, async () => {
          await expectTogglesBothWays(spanPanel, title, true);
        });
      }

      await test.step("The span's Error section toggles both ways", async () => {
        await expectTogglesBothWays(spanPanel, 'Error', false);
      });
    },
  );

  test(
    'An error opened on one trace does not follow the panel to the next row',
    { tag: ['@cap:traces.open-trace-panel'] },
    async ({ project, sdkClient, testNamespace, page }) => {
      const traces = await test.step('Seed two failing traces', async () => {
        const seedOne = (suffix: string) =>
          sdkClient.python.createNestedTrace({
            project_name: project.name,
            name: `${testNamespace}-${suffix}`,
            input: { question: `${suffix} question` },
            output: { answer: `${suffix} answer` },
            error_info: ERROR_INFO,
            spans: [],
          });
        // Sequential, not `Promise.all`: the two are distinguished by which row
        // the table puts first, and concurrent writes leave that to chance.
        const first = await seedOne('first');
        const second = await seedOne('second');
        return [first, second];
      });

      const logs = new LogsPage(page);
      const first = await test.step('Open the top row and expand its error', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        // Both rows must be on screen before the panel opens: the arrows walk
        // the table behind it, and a table still ingesting has no adjacent row.
        for (const trace of traces) {
          await expect(logs.traceRow(trace.id)).toHaveCount(1);
        }
        // The top row rather than a named one, so "next" is guaranteed to exist
        // without this spec encoding the table's sort order.
        const opened = await logs.openFirstTrace();
        await opened.waitForFullyLoaded();
        await opened.expandError();
        await expect(opened.sectionHeader('Error')).toHaveAttribute('aria-expanded', 'true');
        return opened;
      });

      await test.step('Stepping to the adjacent row shows its error closed', async () => {
        // Through the panel's own arrow, not a fresh navigation: a reload would
        // reset the state for free and assert nothing about the panel.
        const second = await first.goToAdjacentRow('next');
        await expect(
          second.sectionHeader('Error'),
          'a trace opened after another had its error expanded must start collapsed',
        ).toHaveAttribute('aria-expanded', 'false');
      });
    },
  );

  test(
    'An error opened before the panel is closed is closed again when it reopens',
    { tag: ['@cap:traces.open-trace-panel'] },
    async ({ project, sdkClient, testNamespace, page }) => {
      const trace = await test.step('Seed a failing trace', async () =>
        sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${testNamespace}-reopened`,
          input: { question: 'does the panel remember?' },
          output: { answer: 'it must not' },
          error_info: ERROR_INFO,
          spans: [],
        }));

      const logs = new LogsPage(page);
      await test.step('Open the trace and expand its error', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        const opened = await logs.openTraceByRow(trace.id);
        await opened.expandError();
        await expect(opened.sectionHeader('Error')).toHaveAttribute('aria-expanded', 'true');
        await opened.close();
      });

      await test.step('Reopening the same trace shows the error closed again', async () => {
        // The panel stays mounted when it closes, so this is the case its own
        // reset exists for: without it the same node reopens mid-conversation,
        // with the traceback already unrolled and the MCP hint already spent.
        const reopened = await logs.openTraceByRow(trace.id);
        await expect(
          reopened.sectionHeader('Error'),
          'the error must not still be expanded from the previous opening',
        ).toHaveAttribute('aria-expanded', 'false');
      });
    },
  );
});
