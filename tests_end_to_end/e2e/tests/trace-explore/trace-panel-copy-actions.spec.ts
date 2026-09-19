import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { TracePanelPage } from '@e2e/pom/trace-panel.page';
import { expectClipboard, readClipboardMatching } from '@e2e/core/clipboard';

/**
 * The trace panel's header copy actions (OPIK-8342, shipped in 2.2.68).
 *
 * The release moved copy-ID and copy-link out of the panel's overflow menu and
 * into icon buttons beside the title, and gave the span toolbar a copy-ID of
 * its own with no link action (`withLink=false`), so a page offers exactly one
 * link to copy.
 *
 * Nothing in the estate had ever asserted a copied value: a grep for
 * copy/clipboard/share comes back empty. The component's own vitest suite
 * cannot close that gap either — `clipboard-copy` is mocked there, and under
 * happy-dom `window.location.href` is not an app URL, so the link action has
 * never been observed producing something that resolves. The failure mode is
 * silent in exactly the way that matters: a link that quietly drops its entity
 * param still looks like a working button.
 *
 * So this spec asserts the two things only a real browser can see — the value
 * that lands on the clipboard, and that pasting the copied link reopens the
 * same trace.
 */

/** Chromium refuses a background clipboard read without both grants. */
test.use({ permissions: ['clipboard-read', 'clipboard-write'] });

test.describe('Trace panel copy actions — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('the panel header copies the trace id, and the copied link reopens the same trace', { tag: ['@cap:traces.open-trace-panel'] }, async ({
    project,
    tracedAgent,
    backendClient,
    page,
    context,
  }) => {
    const logs = new LogsPage(page);

    const childSpan = await test.step('Resolve the seeded span ids from the API', async () => {
      // The ids the clipboard has to match come from the server, not from the
      // page: reading them out of the UI would make the copy assertion
      // circular.
      //
      // Polled, because span ingestion is eventually consistent — a single read
      // straight after the fixture returns can legitimately come back short,
      // and that is a wait, not a failure.
      await expect
        .poll(
          async () =>
            (await backendClient.listSpanRefs({
              projectId: project.id,
              traceId: tracedAgent.id,
            })).length,
          { timeout: 60_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(tracedAgent.spans.length);

      const spans = await backendClient.listSpanRefs({
        projectId: project.id,
        traceId: tracedAgent.id,
      });
      const llm = spans.find((s) => s.name === tracedAgent.llmSpan.name);
      expect(llm, `the '${tracedAgent.llmSpan.name}' span must be readable`).toBeDefined();
      expect(
        llm!.parentSpanId,
        'it must be a CHILD span — a root span would not exercise selecting into the tree',
      ).not.toBeNull();
      return llm!;
    });

    const panel = await test.step('Open the trace panel from Logs', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      const p = await logs.openTraceById(tracedAgent.id);
      await p.waitForFullyLoaded();
      return p;
    });

    await test.step('"Copy trace ID" puts exactly the seeded trace id on the clipboard', async () => {
      await expect(
        panel.headerCopyIdButton,
        'the header offers one copy-ID action',
      ).toHaveCount(1);

      await panel.copyTraceIdFromHeader();
      // Asserted before the clipboard read rather than after it. The
      // confirmation is on a 3s timer, so a read that runs long takes the check
      // icon with it and fails for a reason that has nothing to do with the copy.
      await expect(panel.headerCopiedButton, 'the icon swaps to a check').toBeVisible();

      // Polled, not read once: the component does not await the write, so the
      // check icon is not evidence the value has landed.
      await expectClipboard(
        page,
        tracedAgent.id,
        'the clipboard must carry the trace id verbatim',
      );
    });

    await test.step('The confirmation returns to its idle state', async () => {
      // The confirmation is the whole feedback for the action — the release
      // removed the success toast that used to stand in for it. A button stuck
      // on "Copied" is as wrong as one that never confirms, which is why both
      // ends of the 3s timer are asserted.
      await expect(
        panel.headerCopyIdButton,
        'and swaps back once the timer elapses',
      ).toBeVisible({ timeout: 15_000 });
    });

    const copiedLink = await test.step('"Copy trace link" puts a URL on the clipboard', async () => {
      await panel.copyTraceLinkFromHeader();
      // The pattern is also what tells the new value apart from the trace id the
      // previous step left on the clipboard, so this waits for the right write.
      const link = await readClipboardMatching(
        page,
        /^https?:\/\//,
        'the copied link is an absolute app URL',
      );
      expect(
        new URL(link).searchParams.get('trace'),
        'and it carries the trace it was copied from',
      ).toBe(tracedAgent.id);
      return link;
    });

    await test.step('Pasting the copied link reopens the same trace, on the Traces tab', async () => {
      // A second page rather than a reload: the point is that the link works
      // standalone, the way a colleague receiving it would use it. The new tab
      // takes focus with it, and `navigator.clipboard.readText()` rejects on an
      // unfocused document — so the span step below re-focuses this page before
      // it reads the clipboard again.
      const pasted = await context.newPage();
      try {
        await pasted.goto(copiedLink);
        const reopened = new TracePanelPage(pasted, tracedAgent.id);
        await reopened.waitForFullyLoaded();

        await expect(
          reopened.traceNameInHeader(tracedAgent.name),
          'the reopened panel shows the same trace',
        ).toBeVisible();
        expect(
          new URL(pasted.url()).pathname,
          'on this project\'s Logs page',
        ).toContain(`/projects/${project.id}/logs`);
        expect(
          [null, 'traces'],
          'and on the Traces tab — the tab is unset (its default) or explicitly traces, never threads or spans',
        ).toContain(new URL(pasted.url()).searchParams.get('logsType'));
      } finally {
        await pasted.close();
      }
    });

    await test.step('Selecting a span swaps the toolbar action to "Copy span ID"', async () => {
      // The pasted tab took focus and has since closed. Claim it back explicitly
      // rather than relying on the browser handing it over, because the clipboard
      // read at the end of this step rejects on an unfocused document.
      await page.bringToFront();
      await panel.selectSpan(tracedAgent.llmSpan.name);

      await expect(
        panel.copySpanIdButton,
        'the inspect toolbar offers exactly one copy-span-ID action',
      ).toHaveCount(1);
      await panel.copySpanId();
      await expectClipboard(
        page,
        childSpan.id,
        'the clipboard must carry the seeded span id, not the trace id',
      );
    });

    await test.step('A span offers no link of its own — one link action per page', async () => {
      // `withLink={false}` on the toolbar's copy actions. Asserted as a count
      // over every link action on screen rather than as "no Copy span link":
      // the ambiguity this replaced was two buttons promising different scopes
      // and returning the identical URL, and only counting them catches a
      // second one coming back under any label.
      await expect(
        panel.copyLinkButtons,
        'exactly one link action is on screen',
      ).toHaveCount(1);
      await expect(
        panel.headerCopyLinkButton,
        'and it is the header\'s trace link',
      ).toBeVisible();
    });
  });
});
