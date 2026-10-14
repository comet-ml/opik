import {
  test,
  expect,
  DATASET_CONVERSATION,
  OUTPUT_TEXT,
  EXPECTED_DATASET_REMAINING_LINES,
  EXPECTED_OUTPUT_REMAINING_LINES,
} from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * The compare row-detail panel renders a conversation as message bubbles, and
 * keeps the keys that are NOT part of one beside it (opik#8547, OPIK-7965).
 *
 * Two pure functions decide this, per key: `partitionMessageFields` splits the
 * selected dataset columns and `splitOutputForMessages` splits the output.
 * Every way they can be wrong is silent. The viewer renders message
 * descriptors, so a column handed to it that it does not recognise simply does
 * not appear — and an `expected` answer or a `retrieval_score` missing from the
 * panel looks exactly like an item that never had one. The earlier version of
 * this routing dropped precisely that: a selection mixing a conversation with
 * scalar columns rendered the conversation alone.
 *
 * `experiments-compare.spec.ts` already opens this panel, but it asserts with
 * `toContainText` over a whole section — which passes whether the payload
 * rendered as bubbles or as JSON, and whether or not a key disappeared. So this
 * asserts the WHOLE of each block: the roles in order, each bubble's text
 * joined to its own role, and the leftover block line by line, so a key that
 * was dropped and a key that leaked in both fail.
 *
 * Deterministic by construction: fixed strings seeded over REST, no model
 * output and no wall clock.
 */
test.describe('Experiment compare — conversation beside its sibling keys', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  /**
   * Same 120s budget and same reason as `experiment-compare-image-output.spec.ts`:
   * the fixture waits for the seeded experiment to become queryable on the
   * compare API, which does not fit the 90s default.
   */
  test.slow();

  test(
    'the conversation renders as bubbles and no selected key is dropped',
    { tag: ['@cap:experiments.compare-row-detail'] },
    async ({ experimentMessagePanel, page }) => {
      const seed = experimentMessagePanel;
      const compare = new CompareExperimentsPage(page, seed.projectId, seed.datasetId, [
        seed.experimentId,
      ]);

      await test.step('Open the row detail panel for the mixed-shape item', async () => {
        await compare.gotoResults();
        await compare.waitForResultsReady();
        await compare.openRowPanel(seed.datasetItemId);
      });

      await test.step('The dataset column renders its conversation role by role', async () => {
        // In order, and the whole list: the viewer this replaced collapsed a
        // conversation to its last user message, which a `toContainText` on the
        // user text would still pass.
        expect(
          await compare.panelMessageRoles('dataset'),
          'the roles the dataset column rendered',
        ).toEqual(['System', 'User']);

        for (const message of DATASET_CONVERSATION) {
          const role = message.role === 'system' ? 'System' : 'User';
          expect(
            await compare.panelMessageText('dataset', role),
            `the ${role} bubble's text`,
          ).toBe(message.content);
        }
      });

      await test.step('Both non-conversation dataset columns survive beneath it', async () => {
        // The whole block, not a containment check per key: a partition that
        // also handed `messages` to this viewer would leak the conversation
        // back in here, and that is as wrong as dropping a column.
        expect(
          await compare.panelRemainingKeyLines('dataset'),
          'the leftover dataset columns the panel rendered',
        ).toEqual(EXPECTED_DATASET_REMAINING_LINES);
      });

      await test.step('The output renders as one Assistant bubble', async () => {
        expect(
          await compare.panelMessageRoles({ experimentName: seed.experimentName }),
          'the roles the experiment column rendered',
        ).toEqual(['Assistant']);
        expect(
          await compare.panelMessageText({ experimentName: seed.experimentName }, 'Assistant'),
          "the Assistant bubble's text",
        ).toBe(OUTPUT_TEXT);
      });

      await test.step("Every sibling key of `output` survives, and `output` is not rendered twice", async () => {
        const lines = await compare.panelRemainingKeyLines({
          experimentName: seed.experimentName,
        });
        expect(lines, 'the leftover output keys the panel rendered').toEqual(
          EXPECTED_OUTPUT_REMAINING_LINES,
        );
        // Stated outright as well, because it is the half of `getRemainingOutput`
        // that the list equality above proves only incidentally: the key the
        // bubble rendered is the one key the block must not repeat.
        expect(lines.join('\n'), 'the message text repeated below its own bubble').not.toContain(
          OUTPUT_TEXT,
        );
      });
    },
  );
});
