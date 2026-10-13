import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

test.describe('Playground — message editor cursor', { tag: ['@t3-nightly', '@area:playground'] }, () => {
  test(
    'the text cursor is not clipped at the start of a line',
    { tag: ['@cap:playground.compose-run-prompt'] },
    async ({ page, project, providerKeys, testNamespace }) => {
      const playground = new PlaygroundPage(page, project.id);
      let emptyMessageOverflowPx = Number.NaN;
      let emptyMessageCursorHeightPx = Number.NaN;

      await test.step('Give the workspace a provider so the Playground mounts', async () => {
        await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
      });

      await test.step('Open the Playground', async () => {
        await playground.goto();
        await playground.waitForReady();
      });

      await test.step('An empty message shows the whole cursor', async () => {
        await playground.focusUserMessageAtLineStart();
        await expect
          .poll(() => playground.userMessageCursorLeftOverflowPx(), {
            message: 'px of the cursor cut off by the editor edge',
          })
          .toBeLessThanOrEqual(0);
        emptyMessageOverflowPx = await playground.userMessageCursorLeftOverflowPx();
        emptyMessageCursorHeightPx = await playground.userMessageCursorHeightPx();
      });

      await test.step('The start of a line with text shows the whole cursor', async () => {
        await playground.fillUserMessage('Summarise the following in one sentence.');
        await playground.focusUserMessageAtLineStart();
        await expect
          .poll(() => playground.userMessageCursorLeftOverflowPx(), {
            message: 'the cursor is back at column 0, where the empty message had it',
          })
          .toBeCloseTo(emptyMessageOverflowPx, 1);
        expect(
          await playground.userMessageCursorLeftOverflowPx(),
          'px of the cursor cut off by the editor edge',
        ).toBeLessThanOrEqual(0);
      });

      await test.step('Deleting the only character keeps the cursor full height', async () => {
        await playground.emptyUserMessageWithBackspace();
        await expect
          .poll(() => playground.userMessageCursorLeftOverflowPx(), {
            message: 'the cursor is redrawn at column 0 after Backspace',
          })
          .toBeCloseTo(emptyMessageOverflowPx, 1);
        expect(
          await playground.userMessageCursorHeightPx(),
          'cursor height in the emptied message, compared with the never-typed one',
        ).toBeCloseTo(emptyMessageCursorHeightPx, 0);
      });
    },
  );

  test(
    'opening the Playground focuses the first message, and + Message focuses the new one',
    { tag: ['@cap:playground.compose-run-prompt'] },
    async ({ page, project, providerKeys, testNamespace }) => {
      const playground = new PlaygroundPage(page, project.id);

      await test.step('Give the workspace a provider so the Playground mounts', async () => {
        await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
      });

      await test.step('Open the Playground', async () => {
        await playground.goto();
        await playground.waitForReady();
      });

      await test.step('Typing right away lands in the first message, the System one', async () => {
        await expect(playground.messageEditor(0, 0)).toBeFocused();
        await page.keyboard.type('Hello');
        expect(await playground.messageBodies()).toEqual(['Hello', '']);
      });

      await test.step('+ Message moves the cursor into the new message', async () => {
        await playground.addMessage(0);
        await expect(playground.messageEditor(0, 2)).toBeFocused();
        await page.keyboard.type('World');
        expect(await playground.messageBodies()).toEqual(['Hello', '', 'World']);
      });
    },
  );
});
