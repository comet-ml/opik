import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { ThreadPanelPage } from '@e2e/pom/thread-panel.page';
import { expectClipboard, readClipboardMatching } from '@e2e/core/clipboard';

/**
 * The thread panel's header actions and overflow menu (OPIK-8342, in 2.2.68).
 *
 * `threads.thread-actions-panel` was the area's one uncovered capability, and
 * this release restructured precisely that menu: copy-ID and copy-link came out
 * of it and became icon buttons beside the title, leaving the overflow with
 * three items.
 *
 * Membership is asserted exhaustively, not item by item. "Export as CSV is
 * present" would still pass with a copy action quietly restored beside it —
 * and a duplicated copy action, on two surfaces promising different scopes, is
 * the exact thing this release set out to remove.
 *
 * The title tooltip is the other half. A thread's header reads just "Thread",
 * so the tooltip is the only place its id is legible without copying it; the
 * component's vitest suite can assert the title is a tooltip TRIGGER but never
 * the text, because Radix portals the content and happy-dom never materialises
 * it.
 */

/** Chromium refuses a background clipboard read without both grants. */
test.use({ permissions: ['clipboard-read', 'clipboard-write'] });

/** What the overflow menu is allowed to hold, in render order. */
const EXPECTED_MENU_ITEMS = ['Export as CSV', 'Export as JSON', 'Delete'];

test.describe('Thread panel actions — CUJ', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test('the thread panel header copies id and link, and the overflow menu holds only its three exports and delete', { tag: ['@cap:threads.thread-actions-panel'] }, async ({
    project,
    conversation,
    page,
    context,
  }) => {
    const logs = new LogsPage(page);

    const panel = await test.step('Open the thread panel from the Threads tab', async () => {
      await logs.gotoThreads(project.id);
      await logs.waitForThreadsReady(conversation.threadId);
      const p = await logs.openThreadById(conversation.threadId);
      await p.waitForFullyLoaded();
      await expect(
        p.turns,
        'the seeded conversation renders all three turns before anything is asserted about the header',
      ).toHaveCount(conversation.turns.length);
      return p;
    });

    await test.step('"Copy thread ID" puts exactly the seeded thread id on the clipboard', async () => {
      await expect(panel.copyIdButton, 'the header offers one copy-ID action').toHaveCount(1);

      await panel.copyThreadId();
      // Asserted before the clipboard read rather than after it. The
      // confirmation is on a 3s timer, so a read that runs long takes the check
      // icon with it and fails for a reason that has nothing to do with the copy.
      await expect(panel.copiedButton, 'the icon confirms the copy').toBeVisible();

      // Polled, not read once: the component does not await the write, so the
      // check icon is not evidence the value has landed.
      await expectClipboard(
        page,
        conversation.threadId,
        'the clipboard must carry the thread id verbatim',
      );
    });

    await test.step('Hovering the title reveals the full thread id', async () => {
      expect(
        await panel.readTitleTooltip(),
        'the tooltip spells out the id the header itself only shows as "Thread"',
      ).toBe(`Thread ID: ${conversation.threadId}`);
    });

    const copiedLink = await test.step('"Copy thread link" puts a URL on the clipboard', async () => {
      await panel.copyThreadLink();
      // The pattern is also what tells the new value apart from the thread id
      // the earlier step left on the clipboard, so this waits for the right write.
      const link = await readClipboardMatching(
        page,
        /^https?:\/\//,
        'the copied link is an absolute app URL',
      );
      expect(
        new URL(link).searchParams.get('thread'),
        'and it carries the thread it was copied from',
      ).toBe(conversation.threadId);
      return link;
    });

    await test.step('Pasting the copied link reopens the same conversation on the Threads tab', async () => {
      // A second page rather than a reload, so the link is exercised the way a
      // colleague receiving it would use it. Nothing past this point reads the
      // clipboard: a new tab takes focus, and `readText()` rejects on an
      // unfocused document.
      const pasted = await context.newPage();
      try {
        await pasted.goto(copiedLink);
        const reopened = new ThreadPanelPage(pasted, conversation.threadId);
        await reopened.waitForFullyLoaded();

        expect(
          new URL(pasted.url()).searchParams.get('logsType'),
          'the link restores the Threads tab, not the default Traces one',
        ).toBe('threads');
        await expect(
          reopened.turns,
          'and the reopened panel shows the whole conversation, not just the first turn',
        ).toHaveCount(conversation.turns.length);
        expect(
          await reopened.readTurnTraceIdsInOrder(),
          'turn for turn, in the order they were logged',
        ).toEqual(conversation.turns.map((t) => t.traceId));
      } finally {
        await pasted.close();
      }
    });

    await test.step('The overflow menu holds exactly the two exports and Delete', async () => {
      // Exhaustive by design: this equality is what fails when a copy or share
      // item is restored beside the exports, and equally when Export as JSON
      // quietly disappears. Neither shows up in a per-item presence check.
      expect(
        await panel.openActionsMenuItems(),
        'the menu\'s membership, in full',
      ).toEqual(EXPECTED_MENU_ITEMS);
    });
  });
});
