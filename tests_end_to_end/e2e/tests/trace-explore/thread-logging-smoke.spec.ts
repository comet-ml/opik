import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

test.describe('Thread logging — smoke', { tag: ['@t1-smoke', '@trace-explore'] }, () => {
  test('Threads view groups a multi-turn conversation with the correct message count and endpoints', async ({
    conversation,
    page,
  }) => {
    const logs = new LogsPage(page);

    await test.step('Open Logs Threads view', async () => {
      await logs.gotoThreads(conversation.projectId);
      await logs.waitForThreadsReady(conversation.threadId);
    });

    await test.step('Verify a single thread is present', async () => {
      await expect(logs.threadsTab).toBeChecked();
      // The Threads count card is eventually consistent — poll rather than
      // reading it once, so a slow-propagating deploy doesn't fail the assertion.
      await expect
        .poll(async () => logs.countThreads(), {
          timeout: 15_000,
          intervals: [500, 1000, 2000],
        })
        .toBe(1);
      await expect(logs.threadRow(conversation.threadId)).toBeVisible();
    });

    await test.step('Verify message count and first/last message', async () => {
      // The Threads view counts messages: each turn contributes an input and an
      // output, so N turns report 2*N messages.
      expect(await logs.readThreadMessageCount(conversation.threadId)).toBe(
        conversation.turns.length * 2,
      );
      const firstTurn = conversation.turns[0];
      const lastTurn = conversation.turns[conversation.turns.length - 1];
      await expect(logs.threadFirstMessageCell(conversation.threadId)).toHaveText(firstTurn.input);
      await expect(logs.threadLastMessageCell(conversation.threadId)).toHaveText(lastTurn.output);
    });
  });

  test('Thread detail panel renders the conversation turns in order with the right input and output', async ({
    conversation,
    page,
  }) => {
    const logs = new LogsPage(page);

    const panel = await test.step('Open the thread detail panel', async () => {
      await logs.gotoThreads(conversation.projectId);
      await logs.waitForThreadsReady(conversation.threadId);
      const panel = await logs.openThreadById(conversation.threadId);
      await panel.waitForFullyLoaded();
      return panel;
    });

    await test.step('Verify the turns render in chronological order', async () => {
      expect(await panel.countTurns()).toBe(conversation.turns.length);
      expect(await panel.readTurnTraceIdsInOrder()).toEqual(
        conversation.turns.map((t) => t.traceId),
      );
    });

    await test.step('Verify each turn shows its own input and output', async () => {
      for (const turn of conversation.turns) {
        await expect(panel.turnInput(turn.traceId, turn.input)).toBeVisible();
        await expect(panel.turnOutput(turn.traceId, turn.output)).toBeVisible();
      }
    });
  });
});
