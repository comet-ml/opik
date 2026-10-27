import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * A failed dataset run renders as a FAILURE, not as the model's answer
 * (OPIK-8468).
 *
 * `playground-run-error.spec.ts` already covers the free-mode half of this —
 * that the run is recorded as an errored trace. The dataset grid is the other
 * half, and it is the surface a user actually looks at while a run is going:
 * the cell either shows a red failure tag, or it shows the failure text
 * rendered as ordinary markdown output, and the two are a glance apart. Until
 * now the grid cell was asserted only by vitest.
 *
 * The provider is seeded unreachable rather than forced to a status: the
 * connection is refused before a request is written, so there is no LLM call,
 * no provider key, no network flake and no wall-clock dependence — the run
 * fails the same way every time, immediately.
 *
 * Two sub-checks from the same change are deliberately NOT here, because an
 * unreachable provider cannot produce them: stop-before-first-token (the run
 * fails instantly, so there is no window to stop in) and the nested
 * `detail.detail` unwrapping (needs a controllable failing gateway). Both want
 * a provider that answers slowly or badly, not one that answers never.
 *
 * Also deliberately absent: what a stale failed cell looks like after the
 * prompt is edited. On 2.2.75 the grid CLEARS it to "No runs yet" while free
 * mode dims it, and which of those is correct is a product decision a human
 * has to settle — pinning either would freeze a guess. See the release
 * exploration report for the evidence.
 */

/** The prefix the failure tag puts in front of the provider's own message. */
const FAILURE_PREFIX = 'Run failed:';

test.describe('Playground — failed dataset run', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'every failed row renders a failure tag and no answer',
    { tag: ['@cap:playground.run-error-info'] },
    async ({ page, project, dataset, providerKeys, testNamespace }) => {
      test.setTimeout(180_000);

      const rowCount = dataset.items.length;

      const modelId = await test.step('Seed an unreachable custom provider', async () => {
        return providerKeys.createUnreachable({
          providerName: `${testNamespace}-unreachable`,
          modelName: `${testNamespace}-dead-model`,
        });
      });

      const playground = new PlaygroundPage(page, project.id);

      await test.step('Compose a dataset-templated prompt against the dead model', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: 'Summarise this in one sentence: {{input}}',
          modelDisplayName: modelId.split('/').pop(),
        });
      });

      await test.step(`Load the ${rowCount}-item dataset and run it`, async () => {
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({
          mode: 'dataset',
          entityName: dataset.name,
        });
        // The grid must really be holding this dataset's rows before the run
        // starts: running against an empty item list fails every row
        // client-side for an entirely different reason, which would look
        // identical in the assertions below.
        await playground.waitForRunReady({ expectedRows: rowCount });
        await playground.clickReRun();
        await playground.waitForRunsComplete({ expectedRows: rowCount, timeoutMs: 120_000 });
      });

      const messages = await test.step('Every row failed, and says so', async () => {
        await expect(
          playground.outputErrorTags(),
          'one failure tag per dataset row',
        ).toHaveCount(rowCount);

        const texts = await playground.outputErrorTags().allInnerTexts();
        for (const text of texts) {
          expect(text.trim(), 'the failure tag names itself as a failure').toContain(
            FAILURE_PREFIX,
          );
        }
        return texts.map((t) => t.trim());
      });

      await test.step('The tooltip carries the whole message', async () => {
        const tooltip = await playground.outputErrorTooltipText(0);
        // The wording is the backend's, so it is compared rather than pinned —
        // but it must be non-empty and it must be the message the tag itself
        // truncated, not some other string.
        expect(tooltip, 'the tooltip is not empty').not.toBe('');
        expect(
          messages[0].replace(/\s+/g, ' '),
          'the tag shows the same message the tooltip does',
        ).toContain(tooltip.replace(/\s+/g, ' '));
      });

      await test.step('No row renders the failure as the model\'s answer', async () => {
        // This is the assertion the change exists for. A cell whose error was
        // dropped back into the ordinary output path would render the same
        // text through MarkdownPreview and read, at a glance, like a reply.
        await expect(
          playground.outputMarkdownBlocks(),
          'a failed cell renders no markdown output',
        ).toHaveCount(0);
      });
    },
  );
});
