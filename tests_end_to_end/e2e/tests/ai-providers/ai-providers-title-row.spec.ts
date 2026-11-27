import { test, expect } from '@e2e/fixtures';
import { ConfigurationPage } from '@e2e/pom/configuration.page';

/**
 * The AI Providers tab's title row, and the "Add configuration" button that
 * moved into it (opik#9832).
 *
 * `configuration.ai-provider-list` was uncovered, and this release moved the
 * tab's only primary action out of the search row into a new title row beside a
 * new `h2`, shrinking it to `size="xs"` with a `+` icon. That is exactly the
 * change that silently loses a button: the tab still renders, the table still
 * renders, and the only way to configure a provider is simply absent.
 *
 * The existing ai-providers specs would not notice.
 * `ConfigurationPage.ensureProviderConfigured` reaches the button with a
 * panel-wide `getByRole('button', { name: 'Add configuration' }).first()`, and
 * the tab's empty state renders a second control with the same name inside the
 * table — so on a workspace with no providers configured that lookup resolves
 * to the empty-state button and keeps passing against a tab that had lost its
 * title row entirely. This spec addresses the title row's button through the
 * heading's own row instead, which is the thing the release moved.
 *
 * Nothing is seeded and nothing is submitted. A provider key on this tab is
 * workspace-global, so the dialog is opened to prove the button is still wired
 * and then dismissed with Escape — writing one would change which models every
 * other spec's Playground and online-evaluation pickers offer.
 */
test.describe(
  'Configuration — AI Providers title row',
  { tag: ['@t2-cuj', '@area:configuration'] },
  () => {
    test(
      'renders its title heading exactly once and opens the add-provider dialog from the title row',
      { tag: ['@cap:configuration.ai-provider-list'] },
      async ({ page }) => {
        const configuration = new ConfigurationPage(page);

        await test.step('Open Configuration → AI Providers', async () => {
          await configuration.gotoAiProviders();
        });

        await test.step('The panel renders exactly one h2, and it is the tab\'s own title', async () => {
          // Count first, then text. "There is a heading saying AI Providers"
          // would also be satisfied by a panel that had grown a second heading,
          // or by one matching the page chrome's own — and a duplicated title
          // row is a plausible outcome of the change that added this one.
          await expect(
            configuration.aiProvidersHeadings,
            'the AI Providers panel must render exactly one h2',
          ).toHaveCount(1);
          await expect(configuration.aiProvidersHeadings).toHaveText('AI Providers');
        });

        await test.step('The Add configuration button sits in the title row, above the search row', async () => {
          await expect(
            configuration.addConfigurationInTitleRow,
            'exactly one Add configuration button inside the heading\'s row',
          ).toHaveCount(1);
          await expect(configuration.addConfigurationInTitleRow).toBeVisible();

          // The positional half of "moved into a title row". Asserted as a
          // relation between two boxes rather than as pixel values: the claim is
          // that the button now sits ABOVE the search row, which is what the
          // move did, and that survives a restyle of either control.
          const button = await configuration.addConfigurationInTitleRow.boundingBox();
          const search = await configuration.aiProvidersSearchInput.boundingBox();
          expect(button, 'the Add configuration button must be laid out').not.toBeNull();
          expect(search, 'the search box must be laid out').not.toBeNull();
          expect(
            button!.y + button!.height,
            'the title row\'s button must sit entirely above the search box, not beside it',
          ).toBeLessThanOrEqual(search!.y);
        });

        const dialog = await test.step('It still opens the add-provider dialog', async () =>
          configuration.openAddProviderDialogFromTitleRow());

        await test.step('Dismissing writes nothing', async () => {
          await page.keyboard.press('Escape');
          await expect(dialog).toBeHidden();

          // Back on the tab with the control still there: an Escape that unmounted
          // the title row would leave the dialog hidden too, and this spec is
          // about the control's continued existence.
          await expect(
            configuration.addConfigurationInTitleRow,
            'the title row and its button survive the dialog being dismissed',
          ).toBeVisible();
        });
      },
    );
  },
);
