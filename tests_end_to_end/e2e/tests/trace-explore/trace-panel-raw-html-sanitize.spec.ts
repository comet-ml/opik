import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * This release put `MarkdownHighlighter` and `CodeBlockBody` behind a shared
 * rehype config that adds `rehypeSanitize` where they previously ran
 * `rehypeRaw` alone. `MarkdownPreview` — the renderer behind every text block
 * on the trace panel's Messages tab — moved onto the same config, so raw HTML
 * in an LLM message is now filtered through GitHub's conservative
 * `defaultSchema` (plus `mark`) before it reaches the DOM.
 *
 * A sanitizer is two failure modes at once and both are silent:
 *
 * - **Too much goes.** A schema that is narrower than the markup people
 *   actually log drops a `<details>` block, a table, or an image and leaves no
 *   error behind — the message simply renders shorter than what the model
 *   returned, and nobody can tell from the page that anything is missing.
 * - **Too little goes.** A schema that is wider than intended leaves executable
 *   or presentational markup in a panel that renders other tenants' logged
 *   output.
 *
 * `traces.messages-tab` is `covered: false`. The one spec that touches a
 * Messages tab, `playground.trace-messages-tab`, drives a trace the playground
 * wrote, whose completion is a bare `{output: string}` — a different mapper, a
 * different block shape, and prose rather than markup. The nearest trace-side
 * specs (`compare-message-panel`, `playground-failed-run-output`) match
 * `.comet-markdown` by text content, which plain prose satisfies whether or not
 * the HTML around it survived.
 *
 * Seeded through the SDK as an OpenAI chat-completion shape, which is what
 * makes the Messages tab appear at all (`detectLLMMessages` → `openaiFormat`)
 * and what routes the assistant's string content to `PrettyLLMMessage.TextBlock`
 * → `MarkdownPreview`. Deterministic: no model is called, and every assertion
 * reads an element or attribute out of the rendered message.
 */

/**
 * One assistant answer carrying a marker per class of markup the schema has an
 * opinion about.
 *
 * Every marker is a distinct nonsense token so that an assertion cannot be
 * satisfied by the surrounding prose, and `**PROSE_MARKER**` is deliberately
 * first: it is the only reason `isStringMarkdown` returns true for this string,
 * and so the only reason `MarkdownPreview` takes its `ReactMarkdown` branch at
 * all. Without it the whole answer renders as pre-wrapped plain text, where
 * every text marker below is present and no `<script>` exists — a shape that
 * would satisfy a naive version of both tests while proving nothing. Each test
 * therefore asserts a *parsed element* before it asserts anything about
 * filtering.
 *
 * The blank lines are load-bearing: `<details>` and `<table>` are CommonMark
 * HTML blocks and need to start one.
 */
const RAW_HTML_ANSWER = [
  'Here is some **PROSE_MARKER** before the HTML.',
  '',
  // The body is wrapped in its own `<p>` so that the exact-text lookups below
  // have an element to resolve to: a bare text node after `</summary>` belongs
  // to the `<details>`, whose own text is both markers run together.
  '<details><summary>SUMMARY_MARKER</summary><p>DETAILS_MARKER</p></details>',
  '',
  '<img alt="ALT_MARKER" src="/cuj-raw-html-sanitize-probe">',
  '',
  '<span style="color:red">RED_TEXT_MARKER</span>',
  '',
  '<table><thead><tr><th>HEAD_MARKER</th></tr></thead>' +
    '<tbody><tr><td>CELL_MARKER</td></tr></tbody></table>',
  '',
  '<script>alert("SCRIPT_MARKER")</script>',
  '',
  // Trailing markdown, on a line of its own for the same reason as
  // DETAILS_MARKER above. It is here to catch a parse that swallows everything
  // after the last raw-HTML block.
  'TAIL_MARKER',
].join('\n');

/** The question half of the turn, so the tab renders a User message too. */
const QUESTION = 'Render this please';

/**
 * The trace shape the OpenAI format detector claims on both halves.
 *
 * `{messages: […]}` on the input and `{choices: [{message: …}]}` on the output
 * are the two shapes `detectOpenAIFormat` recognises, and the tab is shown only
 * when at least one side is claimed and neither is rejected — so logging the
 * answer as a bare string would take the Details tab instead and this spec
 * would be asserting against the wrong renderer.
 */
const llmMessageTrace = (name: string) => ({
  name,
  input: { messages: [{ role: 'user', content: QUESTION }] },
  output: {
    choices: [
      { index: 0, message: { role: 'assistant', content: RAW_HTML_ANSWER } },
    ],
  },
});

/** Markers that must be on screen once the disclosure block is open. */
const VISIBLE_TEXT_MARKERS = [
  'PROSE_MARKER',
  'SUMMARY_MARKER',
  'DETAILS_MARKER',
  'RED_TEXT_MARKER',
  'HEAD_MARKER',
  'CELL_MARKER',
  'TAIL_MARKER',
] as const;

test.describe('Trace Explore — raw HTML in an SDK-logged LLM message', {
  tag: ['@t2-cuj', '@area:traces'],
}, () => {
  test(
    'The Messages tab renders the markup it allows and loses none of the text',
    { tag: ['@cap:traces.messages-tab'] },
    async ({ project, sdkClient, testNamespace, page }) => {
      const trace = await test.step('Seed a trace whose answer embeds raw HTML', async () =>
        sdkClient.python.createNestedTrace({
          project_name: project.name,
          ...llmMessageTrace(`${testNamespace}-html-render`),
          spans: [],
        }));

      const logs = new LogsPage(page);
      const panel = await test.step('Open the trace in Logs', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        const opened = await logs.openTraceById(trace.id);
        await opened.waitForFullyLoaded();
        return opened;
      });

      await test.step('The panel opens on Messages and shows both turns', async () => {
        // Asserted as the *selected* tab rather than clicked into: the tab
        // being the default is the observable half of `detectLLMMessages`
        // having claimed this trace, and a click would mask a build where it
        // fell back to Details.
        await expect(
          panel.messagesTab,
          'an OpenAI-shaped trace must open on its Messages tab',
        ).toHaveAttribute('aria-selected', 'true');
        await expect(panel.messageRole('User')).toBeVisible();
        await expect(panel.messageRole('Assistant')).toBeVisible();
        await expect(panel.messageBody('User')).toContainText(QUESTION);
      });

      const markdown = panel.messageMarkdown('Assistant');

      await test.step('The answer took the parsed-markdown branch', async () => {
        // The gate for everything below. `MarkdownPreview` stamps
        // `comet-markdown` on its plain-text fallback too, so the container
        // existing proves nothing; a `<strong>` built from `**PROSE_MARKER**`
        // only exists on the `ReactMarkdown` branch, which is the one
        // `rehypeRaw` and `rehypeSanitize` run on.
        await expect(markdown, 'exactly one markdown body on the turn').toHaveCount(1);
        await expect(
          markdown.locator('strong').filter({ hasText: /^PROSE_MARKER$/ }),
          'the answer must render as parsed markdown, not as pre-wrapped text',
        ).toHaveCount(1);
      });

      const disclosure = markdown.locator('details').filter({
        has: page.locator('summary', { hasText: /^SUMMARY_MARKER$/ }),
      });

      await test.step('The allowed block-level markup survives as elements', async () => {
        // Each of these is an *element* assertion, not a text one: the text of
        // a dropped `<table>` survives as a run-together line, so matching the
        // markers alone would stay green through exactly the regression this
        // spec exists to catch.
        await expect(disclosure, 'the <details> block renders as a disclosure').toHaveCount(1);

        const table = markdown.locator('table');
        await expect(table, 'the <table> renders as a table').toHaveCount(1);
        await expect(table.locator('th').filter({ hasText: /^HEAD_MARKER$/ })).toHaveCount(1);
        await expect(table.locator('td').filter({ hasText: /^CELL_MARKER$/ })).toHaveCount(1);

        // By accessible name, which for an `<img>` IS its `alt` — so this
        // asserts the element and the attribute the schema allows in one go.
        // Deliberately not a visibility check: the probe src resolves to no
        // image, and whether a browser gives a broken image a box is not the
        // subject here.
        await expect(
          markdown.getByRole('img', { name: 'ALT_MARKER' }),
          'the <img> and its alt text both survive',
        ).toHaveCount(1);
      });

      await test.step('Opening the disclosure reveals the text it was hiding', async () => {
        // `<details>` renders collapsed, so its body is in the DOM but not on
        // screen. Opening it is what turns "the markup parsed" into "the
        // content is reachable", and it exercises the element as a control
        // rather than as a tag name.
        await disclosure.getByText('SUMMARY_MARKER', { exact: true }).click();
        await expect(disclosure).toHaveAttribute('open', '');
      });

      await test.step('Every marker from the answer is on screen', async () => {
        for (const marker of VISIBLE_TEXT_MARKERS) {
          await expect(
            markdown.getByText(marker, { exact: true }),
            `"${marker}" must not have been dropped from the rendered message`,
          ).toBeVisible();
        }
      });
    },
  );

  test(
    'Script and inline-style markup is filtered out of the rendered message',
    { tag: ['@cap:traces.messages-tab'] },
    async ({ project, sdkClient, testNamespace, page }) => {
      // Registered before the panel is ever opened, so a dialog raised by
      // markup that slipped through is recorded instead of silently
      // auto-dismissed by Playwright. Dismissing here is what keeps the page
      // responsive if one does fire — the assertion is on the list, at the end.
      const dialogs: string[] = [];
      page.on('dialog', (dialog) => {
        dialogs.push(dialog.message());
        void dialog.dismiss();
      });

      const trace = await test.step('Seed a trace whose answer embeds raw HTML', async () =>
        sdkClient.python.createNestedTrace({
          project_name: project.name,
          ...llmMessageTrace(`${testNamespace}-html-sanitize`),
          spans: [],
        }));

      const logs = new LogsPage(page);
      const panel = await test.step('Open the trace in Logs', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        const opened = await logs.openTraceById(trace.id);
        await opened.waitForFullyLoaded();
        return opened;
      });

      const markdown = panel.messageMarkdown('Assistant');
      const styledSpan = markdown.locator('span').filter({ hasText: /^RED_TEXT_MARKER$/ });

      await test.step('The answer took the parsed-markdown branch', async () => {
        // Same gate as the render test, and it matters more here: on the
        // plain-text fallback there is no `<script>` and no `style` attribute
        // either, so every assertion below would pass over a build whose
        // sanitizer had been removed entirely.
        await expect(markdown, 'exactly one markdown body on the turn').toHaveCount(1);
        await expect(
          markdown.locator('strong').filter({ hasText: /^PROSE_MARKER$/ }),
          'the answer must render as parsed markdown, not as pre-wrapped text',
        ).toHaveCount(1);
        await expect(
          styledSpan,
          'the <span> must survive as an element — only its attribute is filtered',
        ).toHaveCount(1);
      });

      await test.step('The inline style is dropped and the text it wrapped is not', async () => {
        // The discriminating assertion of this test. `defaultSchema` allows
        // `span` but not `style`, and without the sanitizer
        // `hast-util-to-jsx-runtime` turns the attribute into a React style
        // object and the marker renders red. Asserting the attribute is gone
        // *and* the text is still there separates "filtered" from "deleted".
        await expect(
          styledSpan,
          'the inline style attribute must not reach the DOM',
        ).not.toHaveAttribute('style');
        await expect(styledSpan).toBeVisible();
        await expect(styledSpan).toHaveText('RED_TEXT_MARKER');
      });

      await test.step('The script element and its text are both gone', async () => {
        await expect(
          markdown.locator('script'),
          'no <script> may survive into the rendered message',
        ).toHaveCount(0);
        // A schema that kept `script` in `tagNames` without stripping its
        // content would spill the call into the message as visible text, which
        // is a different bug with the same cause.
        await expect(
          panel.messageBody('Assistant'),
          'the script body must not be unwrapped into visible text',
        ).not.toContainText('SCRIPT_MARKER');
      });

      await test.step('Nothing in the message raised a dialog', async () => {
        expect(dialogs, 'markup in a logged message must not be executable').toEqual([]);
      });
    },
  );
});
