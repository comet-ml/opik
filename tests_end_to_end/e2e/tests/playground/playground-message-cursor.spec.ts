import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

test.describe('Playground — message editor cursor', { tag: ['@t3-nightly', '@area:playground'] }, () => {
  test(
    'the text cursor is not clipped at the start of a line',
    { tag: ['@cap:playground.compose-run-prompt'] },
    async ({ page, project, providerKeys, testNamespace }) => {
      const playground = new PlaygroundPage(page, project.id);
      let emptyMessageClippedPx = Number.NaN;

      await test.step('Give the workspace a provider so the Playground mounts', async () => {
        await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
      });

      await test.step('Open the Playground', async () => {
        await playground.goto();
        await playground.waitForReady();
      });

      await test.step('An empty message shows the whole cursor', async () => {
        await playground.focusFirstMessageAtLineStart();
        await expect
          .poll(() => playground.firstMessageCursorClippedPx(), {
            message: 'px of the cursor cut off by the editor edge',
          })
          .toBeLessThanOrEqual(0);
        emptyMessageClippedPx = await playground.firstMessageCursorClippedPx();
      });

      await test.step('The start of a line with text shows the whole cursor', async () => {
        await playground.fillFirstMessage('Summarise the following in one sentence.');
        await playground.focusFirstMessageAtLineStart();
        await expect
          .poll(() => playground.firstMessageCursorClippedPx(), {
            message: 'the cursor is back at column 0, where the empty message had it',
          })
          .toBeCloseTo(emptyMessageClippedPx, 1);
      });
    },
  );
});
