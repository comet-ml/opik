import {
  test,
  expect,
  VERSION_COUNT,
  VERSION_PAGE_SIZE,
} from '../../fixtures/versioned-prompt.fixture';
import { PromptDetailPage } from '@e2e/pom/prompt-detail.page';

/**
 * Prompt version history past the first page (OPIK-8050).
 *
 * The version timeline loads 25 commits at a time. Everything that reads the
 * version list — the timeline itself, the deep link, the "Compare against"
 * menu — used to see only what had been paged in, so on a prompt with more than
 * 25 versions the older ones were silently missing from each of them. Nothing
 * about that looks like an error: a Compare menu offering 24 versions instead
 * of 30 renders exactly as well.
 *
 * The two specs in this directory that touch version labels build two or three
 * versions, so neither can reach a second page, and neither can tell a label
 * derived from a row's position apart from the backend's own `version_number`.
 * The fixture seeds 31 commits with a marker unique to each, which is what
 * makes both distinguishable.
 */
test.describe('Prompt version history — pagination', { tag: ['@t2-cuj', '@area:prompts'] }, () => {
  // The version timeline is the page's right sidebar, which only renders at the
  // xl breakpoint — below it the versions collapse into a dropdown instead.
  test.use({ viewport: { width: 1600, height: 900 } });

  test('The version timeline renders one page and lazily loads the rest, labelled from the backend', { tag: ['@cap:prompts.version-history'] }, async ({
    versionedPrompt,
    project,
    backendClient,
    page,
  }) => {
    const newestFirst = await test.step('The prompt really has 31 contiguously numbered versions (seed shape)', async () => {
      const versions = await backendClient.listPromptVersions(versionedPrompt.id);
      expect(versions, 'every seeded commit is readable').toHaveLength(VERSION_COUNT);

      const labels = versions.map((v) => v.versionNumber);
      // Newest first, v31 down to v1, with no gaps. Asserted server-side before
      // the browser opens: a timeline compared against a seed that had itself
      // failed to number contiguously would be checking nothing.
      const expected = Array.from({ length: VERSION_COUNT }, (_, i) => `v${VERSION_COUNT - i}`);
      expect(labels, 'backend version numbers, newest first').toEqual(expected);
      return expected;
    });

    const detail = new PromptDetailPage(page);

    await test.step('Only the first page of versions renders initially', async () => {
      await detail.goto(project.id, versionedPrompt.id);
      await detail.waitForReady();

      await expect(detail.versionTimelineItems()).toHaveCount(VERSION_PAGE_SIZE);
      expect(await detail.readVersionTimelineLabels()).toEqual(
        newestFirst.slice(0, VERSION_PAGE_SIZE),
      );
    });

    await test.step('Scrolling loads the remaining versions, in order and without gaps', async () => {
      await detail.scrollVersionTimelineToEnd();

      await expect(detail.versionTimelineItems()).toHaveCount(VERSION_COUNT);
      // The whole list, not just "the older ones arrived": a second page that
      // re-fetched an offset already loaded would duplicate rows and drop
      // others, and a count alone would not notice.
      expect(await detail.readVersionTimelineLabels()).toEqual(newestFirst);
    });
  });

  // Tagged `version-history`, not `compare-versions`: this asserts what the
  // Compare-against menu *offers* — which is the paginated version list read
  // through a second surface — and never selects an entry, so it would still
  // pass if the comparison itself were broken.
  test('A version deep-linked past the first page renders its own template and is offered every other version to compare against', { tag: ['@cap:prompts.version-history'] }, async ({
    versionedPrompt,
    project,
    page,
  }) => {
    // v3 is the third-oldest commit, so it falls on the second page — the page
    // that is not loaded when the prompt first renders.
    const deepLinked = versionedPrompt.versions[2];
    const deepLinkedLabel = versionedPrompt.labels[2];
    const latestLabel = versionedPrompt.labels[VERSION_COUNT - 1];

    const detail = new PromptDetailPage(page);

    await test.step(`Deep-linking to ${deepLinkedLabel} resolves that version, not the latest`, async () => {
      await detail.goto(project.id, versionedPrompt.id, { activeVersionId: deepLinked.id });
      await detail.waitForReady();

      await expect(detail.activeVersionLabel()).toHaveText(deepLinkedLabel);
      // The label alone would still be satisfied by a page that fell back to
      // the newest version and mislabelled it, so assert the rendered template
      // is the deep-linked commit's own — and is not the latest one's.
      await expect(detail.textContent()).toContainText(versionedPrompt.markerFor(3));
      await expect(detail.textContent()).not.toContainText(
        versionedPrompt.markerFor(VERSION_COUNT),
      );
    });

    await test.step('The Compare-against menu offers every other version, including ones past page 1', async () => {
      const menu = await detail.openDiffMenu();

      await expect(
        detail.diffMenuItems(menu),
        'one entry per version except the active one',
      ).toHaveCount(VERSION_COUNT - 1);

      // Each label individually, not just the count: a menu that had loaded
      // only page 1 and duplicated six of its entries would have the right
      // count and the wrong contents.
      for (const label of versionedPrompt.labels) {
        if (label === deepLinkedLabel) continue;
        await expect(
          detail.diffMenuVersionLabel(menu, label),
          `${label} is offered to compare against`,
        ).toHaveCount(1);
      }

      await expect(
        detail.diffMenuVersionLabel(menu, deepLinkedLabel),
        'the active version is not offered as its own comparison',
      ).toHaveCount(0);

      // The latest version is on page 1 and the deep-linked one on page 2, so
      // this pins the menu to the union rather than to whichever page happened
      // to load last.
      await expect(detail.diffMenuVersionLabel(menu, latestLabel)).toHaveCount(1);
    });
  });
});
