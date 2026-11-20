import { test, expect } from '@e2e/fixtures';
import { PromptDetailPage } from '@e2e/pom/prompt-detail.page';
import { loadEnvConfig } from '../../config/env.config';

/**
 * The grouped Name cell on the prompt detail page's Experiments tab
 * (opik#9620 / OPIK-9253).
 *
 * The second call site. `useExperimentsTableConfig` builds the grouped name
 * cell for both surfaces, but each page supplies its OWN `onRowClick`:
 * `GeneralDatasetsTab.handleRowClick` hardcodes the compare route, while
 * `PromptPage/ExperimentsTab.handleRowClick` composes it out of
 * `RESOURCE_MAP[RESOURCE_TYPE.experiment].projectUrl` and its `param`. They are
 * supposed to land in the same place and they can disagree — which is exactly
 * the half of opik#9620 the widened click target made reachable, because before
 * it the row handler on this tab was never invoked from this cell at all.
 *
 * Nothing in the estate opens this tab grouped: `gotoExperimentsTab` pins
 * `groups=[]`, so `prompt-experiments-pinning.spec.ts` exercises the flat
 * list's unchanged name column.
 *
 * Deliberately not a copy of every case the Experiments-page spec makes. What
 * is worth asserting here is that this call site AGREES with that one — plain
 * text, the name click, and the beside-the-name click all resolving to the same
 * per-row compare route — on a seed built once and reached by prompt rather
 * than by project. The checkbox and shift-range gestures come from the shared
 * `DataTable` and nothing on this tab selects them, so they are pinned on the
 * other surface only.
 */
test.describe(
  'Prompt — Experiments tab grouped name cell',
  { tag: ['@t2-cuj', '@area:prompts'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'the name is plain text and both the name and the cell beside it open that row',
      { tag: ['@cap:prompts.experiments-tab-name-cell'] },
      async ({ page, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;
        const prompt = new PromptDetailPage(page);
        const [first, second] = seed.mixed.experiments;

        await test.step("Open the prompt's Experiments tab on its default grouped view", async () => {
          await openGroupedTab(prompt, seed);
          // Every seeded experiment is linked to this prompt's one version, so
          // the tab must show the same groups the project page does. Fewer
          // would mean the prompt link did not land and the cell asserted below
          // belongs to a different population.
          await expect(
            prompt.experimentGroups.groupRows,
            'one group row per seeded item source, reached by prompt',
          ).toHaveCount(seed.groups.length, { timeout: 30_000 });
        });

        await test.step('The name renders as plain cell text, at rest and under the pointer', async () => {
          for (const experiment of seed.mixed.experiments) {
            await prompt.experimentGroups.expectExperimentNamePlainText(
              experiment.id,
              experiment.name,
            );
            await prompt.experimentGroups.expectExperimentNamePlainText(
              experiment.id,
              experiment.name,
              { hover: true },
            );
          }
        });

        await test.step("Clicking the name lands on that row's compare view", async () => {
          await prompt.experimentGroups.clickExperimentName(first.id);
          await expectCompareViewFor(page, seed.projectId, seed.mixed.datasetId, first.id);
        });

        // The other row, through the other code path: the tab's own
        // `handleRowClick`. Different row AND different path, so neither a
        // handler that navigates to a constant nor one that disagrees with the
        // anchor can pass both halves.
        await test.step('And clicking beside the name lands on the other row, the same way', async () => {
          await openGroupedTab(prompt, seed);
          await prompt.experimentGroups.clickNameCellPastTheLink(second.id);
          await expectCompareViewFor(page, seed.projectId, seed.mixed.datasetId, second.id);
        });
      },
    );
  },
);

/** Open the prompt's Experiments tab grouped, with the two-experiment group expanded. */
async function openGroupedTab(
  prompt: PromptDetailPage,
  seed: {
    projectId: string;
    promptId: string;
    groups: { datasetName: string }[];
    mixed: { datasetName: string; experiments: { id: string }[] };
  },
): Promise<void> {
  await prompt.gotoExperimentsTabGrouped(seed.projectId, seed.promptId);
  await prompt.experimentGroups.waitForGroupRows(seed.groups.length);
  await prompt.experimentGroups.expandGroup(
    seed.mixed.datasetName,
    seed.mixed.experiments[0].id,
  );
}

/**
 * The compare route's pathname for one dataset, built from the seed rather than
 * read off the anchor — comparing where a click landed against the link that
 * was clicked would be circular.
 *
 * The expectation is the PROJECT-scoped route both surfaces resolve to, which
 * is the agreement this spec is about: this tab composes it from `RESOURCE_MAP`
 * while the Experiments page spells it out.
 */
const compareRoutePath = (projectId: string, datasetId: string): string => {
  const env = loadEnvConfig();
  return new URL(
    `${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments/${datasetId}/compare`,
  ).pathname;
};

/** Assert the browser is on the compare view for exactly one experiment. */
async function expectCompareViewFor(
  page: import('@playwright/test').Page,
  projectId: string,
  datasetId: string,
  experimentId: string,
): Promise<void> {
  await page.waitForURL((url) => url.pathname.endsWith('/compare'));
  const url = new URL(page.url());
  expect(url.pathname, "the click must land on this row's dataset compare view").toBe(
    compareRoutePath(projectId, datasetId),
  );
  expect(
    selectedExperimentIds(url),
    'the compare view must carry exactly the clicked experiment',
  ).toEqual([experimentId]);
}

/** The experiment ids in a compare URL's `experiments` param, parsed as JSON. */
function selectedExperimentIds(url: URL): string[] {
  const raw = url.searchParams.get('experiments');
  if (raw === null) {
    throw new Error(`the compare URL carried no experiments param: ${url.toString()}`);
  }
  const parsed: unknown = JSON.parse(raw);
  if (!Array.isArray(parsed)) {
    throw new Error(`the experiments param is not a JSON array: ${raw}`);
  }
  return parsed.map(String);
}
