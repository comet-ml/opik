import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

const ERROR_INFO = {
  exception_type: 'PermissionDeniedError',
  message: 'Error code: 403 - key limit exceeded (total limit)',
  traceback: 'Traceback (most recent call last):\n  File "app.py", line 12, in run\n    raise PermissionDeniedError()',
};

test.describe('Trace Explore — MCP hint on expanded errors', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('the hint is offered only once the user opens the error, and survives closing it again', { tag: ['@cap:traces.mcp-hint-on-trace-error'] }, async ({
    project,
    sdkClient,
    testNamespace,
    page,
  }) => {
    const trace = await test.step('Seed a failing trace', async () =>
      sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-failing-trace`,
        input: { question: 'ask the model something' },
        error_info: ERROR_INFO,
        spans: [{ name: 'llm_call', type: 'llm' }],
      }),
    );

    const logs = new LogsPage(page);
    const panel = await test.step('Open the failing trace', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      const panel = await logs.openTraceById(trace.id);
      await panel.waitForFullyLoaded();
      return panel;
    });

    await test.step('The error being present is not enough to offer the hint', async () => {
      await expect(panel.errorSection).toBeVisible();
      await expect(panel.mcpHintButton).toBeHidden();
    });

    await test.step('Opening the error reveals it', async () => {
      await panel.expandError();
      await panel.waitForMcpHint();
      await expect(panel.mcpHintButton).toBeVisible();
    });

    // Asking and then tidying up is not changing your mind — and closing the
    // traceback is usually the moment the user turns to acting on it.
    await test.step('Closing the error again leaves the hint in place', async () => {
      await panel.collapseError();
      await expect(panel.mcpHintButton).toBeVisible();
    });
  });

  test('the popover explains Opik MCP and links to its documentation', { tag: ['@cap:traces.mcp-hint-on-trace-error'] }, async ({
    project,
    sdkClient,
    testNamespace,
    page,
  }) => {
    const trace = await test.step('Seed a failing trace', async () =>
      sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-failing-trace-popover`,
        input: { question: 'ask the model something' },
        error_info: ERROR_INFO,
        spans: [{ name: 'llm_call', type: 'llm' }],
      }),
    );

    const logs = new LogsPage(page);
    const panel = await test.step('Open the failing trace and reveal the hint', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      const panel = await logs.openTraceById(trace.id);
      await panel.waitForFullyLoaded();
      await panel.expandError();
      await panel.waitForMcpHint();
      return panel;
    });

    await test.step('Hovering the pill opens the popover', async () => {
      await panel.openMcpHintPopover();
      await expect(panel.mcpHintPopover.getByText('Opik MCP')).toBeVisible();
      await expect(
        panel.mcpHintPopover.getByText(/your agent reads Opik directly/),
      ).toBeVisible();
    });

    await test.step('Learn more points at the MCP server documentation', async () => {
      const learnMore = panel.mcpHintPopover.getByRole('link', { name: /Learn more/ });
      await expect(learnMore).toHaveAttribute('href', /\/docs\/opik\/mcp-server/);
      await expect(learnMore).toHaveAttribute('target', '_blank');
    });

    // Crossing the gap between the pill and the popover fires a leave on one and
    // an enter on the other; a popover that closes in between is unreachable.
    await test.step('Moving the pointer into the popover does not dismiss it', async () => {
      await panel.mcpHintPopover.getByText('Opik MCP').hover();
      await expect(panel.mcpHintPopover).toBeVisible();
    });
  });

  // The button follows whichever error is open: stepping away to another
  // failing trace and back must not leave the first one without it.
  test('the hint comes back on a trace the user returns to', { tag: ['@cap:traces.mcp-hint-on-trace-error'] }, async ({
    project,
    sdkClient,
    testNamespace,
    page,
  }) => {
    const traces = await test.step('Seed two failing traces', async () => {
      const seedOne = (suffix: string) =>
        sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${testNamespace}-${suffix}`,
          input: { question: `${suffix} question` },
          error_info: ERROR_INFO,
          spans: [],
        });
      // Sequential, so the table's row order is settled before the arrows walk it.
      const first = await seedOne('first');
      const second = await seedOne('second');
      return [first, second];
    });

    const logs = new LogsPage(page);
    const first = await test.step('Open the top row and reveal its hint', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      for (const trace of traces) {
        await expect(logs.traceRow(trace.id)).toHaveCount(1);
      }
      const opened = await logs.openFirstTrace();
      await opened.waitForFullyLoaded();
      await opened.expandError();
      await opened.waitForMcpHint();
      return opened;
    });

    const second = await test.step('Reveal the hint on the adjacent trace', async () => {
      const next = await first.goToAdjacentRow('next');
      await next.expandError();
      await next.waitForMcpHint();
      return next;
    });

    await test.step('Going back and opening the first error again shows the hint', async () => {
      const back = await second.goToAdjacentRow('previous');
      await back.expandError();
      await back.waitForMcpHint();
      await expect(back.mcpHintButton).toBeVisible();
    });
  });
});
