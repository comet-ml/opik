import { test, expect } from '@e2e/fixtures';
import { DashboardsPage } from '@e2e/pom/dashboards.page';

/**
 * The Markdown dashboard widget's form is one of the four `zodResolver` forms
 * opik#9431 migrated to zod 4 with no spec of its own, and opik#9421 then moved
 * every form in the app onto `@hookform/resolvers` 6 — a major the migration
 * was written and verified against resolvers 5.
 *
 * `dashboards.add-widget` is covered, but only for the Time series type
 * (`workspace-span-metrics.spec.ts`, `project-cost-breakdown-others.spec.ts`,
 * `widget-tag-cost-info.spec.ts`): no spec has ever opened the Markdown editor,
 * and `TextMarkdownWidget/schema.ts` is named nowhere in the estate.
 *
 * The two halves asserted are the two a resolver major breaks independently:
 *
 *  - the REFUSAL. `WidgetConfigDialog.handleSave` calls the editor's
 *    `form.trigger()` and only saves when it returns true, so a resolver that
 *    stopped producing an issue for an empty string saves a blank widget and
 *    closes the dialog. The message is asserted too, not just the dialog
 *    staying open — a dialog that stayed open for any other reason (a crashed
 *    submit, a disabled button) would satisfy a visibility-only check.
 *  - the SUBMIT. The content is read back three ways, each of which the others
 *    cannot see: rendered in the saved widget, stored in the dashboard's own
 *    `config` server-side, and still there after a reload. A widget rendering
 *    from the client store looks identical to one that persisted.
 */

/**
 * The markdown SOURCE typed into the editor, and the text the widget renders
 * from it.
 *
 * They differ on purpose. `MarkdownPreview` renders `## x` as a heading whose
 * text is `x`, so the two assertions this spec makes are genuinely different
 * facts: the stored config has to carry the source VERBATIM (that is what the
 * form submitted), while the widget has to render it AS markdown (the heading
 * marker consumed, not printed). A plain-text probe would collapse both into
 * one check and could not tell a widget that stored the source from one that
 * stored the rendered text.
 *
 * One line, because CodeMirror auto-indents typed input and a multi-line string
 * is not necessarily the string that ends up stored. And the text deliberately
 * does not contain the widget's own generated header title ("Text"), since the
 * widget is addressed by that title and content carrying it would make the
 * locator match itself.
 */
const MARKDOWN_CONTENT = '## Release note — markdown widget probe content';
const MARKDOWN_RENDERED = 'Release note — markdown widget probe content';

/** The wire value `WIDGET_TYPE.TEXT_MARKDOWN` serialises to. */
const TEXT_MARKDOWN_TYPE = 'text_markdown';

/** The widget's generated header title (`calculateTitle` returns a constant). */
const WIDGET_TITLE = 'Text';

test.describe('Dashboards — Markdown widget form', { tag: ['@area:dashboards'] }, () => {
  test(
    'an empty Markdown widget is refused with its message, and the content that is typed is saved and persists',
    { tag: ['@t2-cuj', '@cap:dashboards.add-widget'] },
    async ({ backendClient, registerDashboardCleanup, testNamespace, page }) => {
      const dashboards = new DashboardsPage(page);
      const dashboardName = `${testNamespace}-md-widget`;

      const dashboardId = await test.step('Create a dashboard', async () => {
        await dashboards.goto();
        await dashboards.waitForReady();
        const id = await dashboards.createDashboard(dashboardName);
        // Registered the moment the id exists — it only surfaces on the route
        // the app lands on, and nothing cascades to a workspace-scoped
        // dashboard.
        registerDashboardCleanup(id);
        return id;
      });

      const dialog = await test.step('Open Add widget and choose the Markdown type', async () => {
        const d = await dashboards.openWidgetDialog();
        await dashboards.selectWidgetType(d, 'Markdown');
        await expect(
          dashboards.markdownEditor(d),
          'the Markdown editor mounted',
        ).toBeVisible();
        await expect(
          await dashboards.markdownEditorContent(d),
          'and it opens empty, so the refusal below is reached without clearing anything',
        ).toBe('');
        return d;
      });

      await test.step('Submitting it empty is refused, and the message names the field', async () => {
        await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();

        await expect(
          dashboards.widgetValidationMessage(dialog, 'Markdown content is required'),
          "the schema's own message renders",
        ).toBeVisible();
        // The dialog is still open, which is what says the save was actually
        // blocked: `handleSave` closes it the moment `form.trigger()` passes,
        // so an open dialog and a rendered message together cannot both
        // survive a save that went through.
        await expect(dialog, 'the dialog stayed open').toBeVisible();
        // Deliberately NOT asserted here: a widget count off the page. The
        // dialog renders its own live PREVIEW inside a `dashboard-widget`
        // container carrying the same generated title, so a page-wide count is
        // 1 while the dialog is open whether or not anything was added. That
        // this submit added nothing is asserted instead where it is
        // unambiguous — exactly one widget in the UI once the dialog has
        // closed, and exactly one in the dashboard's stored config.
      });

      await test.step('Typing content and submitting closes the dialog and renders the widget', async () => {
        await dashboards.fillMarkdownContent(dialog, MARKDOWN_CONTENT);

        // NOT asserted: that the "Markdown content is required" message
        // disappears as soon as the field becomes valid. Measured on staging at
        // 2.2.141, it does not — it stays until the next submit. That is RHF's
        // own touched/submitted semantics rather than anything this release
        // changed: the editor runs `mode: 'onTouched'` and the dialog validates
        // through `form.trigger()`, which does not set `isSubmitted`, so
        // `reValidateMode: 'onChange'` never engages and a field that has not
        // been blurred is not re-validated on typing. Cosmetic and transient —
        // the submit below clears it by closing the dialog — and pinning it
        // either way would be pinning a framework default this spec has no
        // business owning.
        await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();
        await dialog.waitFor({ state: 'hidden' });

        // Exactly one — which is also what says the refused empty submit above
        // added nothing. The dialog is hidden by now, so its own preview
        // container is gone and this count is unambiguous.
        await expect(dashboards.widget(WIDGET_TITLE), 'one widget was added').toHaveCount(1);
        await expect(
          dashboards.widget(WIDGET_TITLE).getByRole('heading', {
            name: MARKDOWN_RENDERED,
            exact: true,
          }),
          'rendering the typed content as markdown — a heading, not the raw source',
        ).toBeVisible();
      });

      await test.step("The dashboard's stored config carries that exact content", async () => {
        // The page autosaves; without waiting for the indicator this read races
        // the write and would report an empty widget list for a dashboard that
        // was about to be correct.
        await expect(dashboards.savedIndicator()).toBeVisible({ timeout: 30_000 });

        const widgets = await backendClient.getDashboardWidgets(dashboardId);
        // The whole list, not a `find()`: a second widget the dialog should
        // never have created — the refused empty one, say — is exactly the
        // failure this read exists to catch, and a lookup by type would step
        // right over it.
        expect(widgets, 'exactly one widget was persisted').toHaveLength(1);
        expect(widgets[0].type, 'as a markdown widget').toBe(TEXT_MARKDOWN_TYPE);
        expect(
          widgets[0].config.content,
          'holding the typed string verbatim, not undefined or a truncated form',
        ).toBe(MARKDOWN_CONTENT);
      });

      await test.step('And it is still there after a reload', async () => {
        await page.reload();
        await expect(dashboards.widget(WIDGET_TITLE)).toHaveCount(1);
        await expect(
          dashboards.widget(WIDGET_TITLE).getByRole('heading', {
            name: MARKDOWN_RENDERED,
            exact: true,
          }),
          'the reloaded page renders the stored content',
        ).toBeVisible();
      });
    },
  );
});
