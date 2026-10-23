# Opik view kit v1

A custom view is one HTML document fragment (markup, `<style>`, `<script>`) that Opik renders in a sandboxed
iframe. The kit is loaded before the view and gives it Opik's look and a small set of helpers.

Store `kit_version: 1` with every view written against this reference.

## Rules

- No network. Every request, external script, stylesheet, font or image URL is blocked. Images and media only
  from `data:` or `blob:` URLs.
- No forms, popups, dialogs or navigation. Links do not open pages.
- Build elements with `kit.h` and set text with `text`. `innerHTML`, `outerHTML`, `insertAdjacentHTML`,
  `document.write` and `srcdoc` are blocked, so trace content can never run as markup. Use `kit.stripHtml` to show
  HTML as plain text and `kit.emailFrame` to render an email.
- Render through `kit.mount`. A view that does not call `kit.mount`, throws, or returns nothing is replaced by the
  default Opik view, and the error is shown to the user.
- Style with the kit tokens and classes below. Do not hard-code colours: the same view must work in light and
  dark themes.
- A view that navigates its frame (`location`, links, meta refresh) is removed and replaced by the default Opik
  view.
- Attachments stored separately from the trace are not available. Media embedded in the data (`data:` URLs or
  base64 strings) can be shown with a `data:` URL.

## Surfaces

| Surface | Data passed to `kit.mount` | Packs | Actions |
|---|---|---|---|
| Annotation queue (trace) | `{ trace }`: `id`, `name`, `input`, `output`, `metadata`, `tags`, `feedback_scores`, `start_time`, `end_time`, `duration`, `usage`, `total_estimated_cost` | `review` | none |
| Annotation queue (thread) | `{ thread, traces }`: `thread` has `id`, `start_time`, `end_time`, `duration`, `number_of_messages`, `status`, `tags`, `feedback_scores`, `usage`, `total_estimated_cost`; `traces` are the conversation turns in order, each with the trace fields above | `review` | none |

`input`, `output` and `metadata` are whatever the traced app logged: objects, arrays, or strings that may hold
JSON. Read them with `kit.get` and `kit.first`, and expect fields to be missing.

## Tokens

CSS custom properties. Use them as `var(--kit-…)`.

| Token | Use |
|---|---|
| `--kit-bg` | page background, inputs, buttons |
| `--kit-surface` | card background |
| `--kit-fill` | subtle fill: code, tags, bars |
| `--kit-border` | borders and dividers |
| `--kit-text` | body text |
| `--kit-text-strong` | headings |
| `--kit-muted` | secondary text, labels |
| `--kit-subtle` | tertiary text, placeholders |
| `--kit-off` | inactive marks (empty stars) |
| `--kit-primary` | links, accents, focus |
| `--kit-primary-soft` | highlighted background |
| `--kit-on-primary` | text on `--kit-primary` |
| `--kit-success` | pass, good |
| `--kit-danger` | fail, bad |
| `--kit-warning` | warning text |
| `--kit-warning-soft` | warning background, `<mark>` |
| `--kit-amber` | ratings |
| `--kit-blue`, `--kit-green`, `--kit-red`, `--kit-yellow`, `--kit-purple`, `--kit-orange`, `--kit-teal`, `--kit-pink` | categorical colours (charts, labels) |
| `--kit-font`, `--kit-mono` | font stacks |
| `--kit-radius`, `--kit-radius-sm` | corner radius (6px, 4px) |
| `--kit-gap` | default gap between blocks (12px) |

For a soft status background mix a token with transparency:
`color-mix(in srgb, var(--kit-success) 12%, transparent)`.

## Styled elements

Plain HTML already looks like Opik: `h1`–`h4`, `p`, `small`, `a`, `ul`, `ol`, `blockquote`, `hr`, `mark`, `code`,
`kbd`, `pre`, `table`/`th`/`td`, `details`/`summary`, `button`, `input`, `select`, `textarea`.

## Classes

| Class | Use |
|---|---|
| `kit-stack` | vertical stack with `--kit-gap` |
| `kit-row` | wrapping horizontal row |
| `kit-spacer` | pushes the rest of a row to the right |
| `kit-grid` | one column; add `kit-cols-2` (two columns from 600px) or `kit-cols-main-side` (3:2 from 960px) |
| `kit-card`, `kit-card-header`, `kit-card-body` | bordered card; add `kit-highlight` to emphasise |
| `kit-tag` | small label; add `kit-primary`, `kit-success`, `kit-danger` or `kit-warning` |
| `kit-text` | keeps line breaks of plain text |
| `kit-muted`, `kit-subtle`, `kit-xs`, `kit-strong` | text colour, size and weight |
| `kit-link` | a button that looks like a link |
| `kit-note` | dashed note box |
| `kit-dl` | label/value grid (`dl` with `dt`/`dd`) |
| `kit-list` | vertical list with dividers |

## Core helpers

All on the global `kit`.

- `kit.mount(render)`: renders the view. `render(data)` returns a DOM node; it runs again when the data changes.
- `kit.refresh()`: runs the last `render` again, e.g. after the view's own state changed.
- `kit.action(name, payload)`: sends a named action to the page. Only the surface's actions are accepted.
- `kit.h(tag, props, ...children)`: creates an element. Props: `class`, `text`, `style` (CSS text), `on<event>`
  handlers, any other attribute. Children: nodes, strings, numbers, arrays; `null`/`false` are skipped.
- `kit.get(source, path)`: reads a nested value by a path array, parsing JSON strings on the way.
- `kit.first(...values)`: the first present value.
- `kit.isPresent(value)`: not `null`/`undefined`/blank string.
- `kit.isObject(value)`: a plain object (not an array).
- `kit.tryJson(value)`: parses a JSON object/array string, otherwise returns the value.
- `kit.asText(value)`: a string, or pretty JSON for anything else.
- `kit.looksLikeHtml(value)`: the string contains HTML markup.
- `kit.stripHtml(html)`: the visible text of an HTML string.
- `kit.wordCount(text)`: number of words.
- `kit.format(value)`: numbers with thousands separators, anything else as a string.
- `kit.tag(text, variant)`: a `kit-tag`; `variant` is `primary`, `success`, `danger` or `warning`.
- `kit.card({ title, extra, body, highlight, className })`: a card; `extra` sits at the right of the header.
- `kit.dl(entries)`: a `kit-dl` from `[label, value]` pairs, skipping empty values.
- `kit.json(value)`: a `pre` with the value as pretty JSON.

## Pack: review

For reviewing model output.

- `kit.turn({ role, title, body })`: one conversation turn. `role` `"user"` sits on the right with a highlighted
  bubble, anything else on the left; `title` is a small label above it. Wrap turns in a `kit-stack`. Classes
  `kit-turn`, `kit-turn-user`, `kit-turn-assistant`, `kit-turn-title`, `kit-turn-body`.
- `kit.chip(label, value, variant)`: label/value chip; `variant` `primary` for emphasis. Classes `kit-chip`,
  `kit-chip-label`.
- `kit.stars(count, max)`: star rating (default max 5). Class `kit-stars`.
- `kit.verdict(value, labels)`: pass/fail tag from a score (`>= 1` passes); `labels` defaults to
  `["Fail", "Pass"]`.
- `kit.reasonView(reason)`: a judge rationale; `key: value` lines become a `kit-dl`.
- `kit.revealGate({ summary, note, render, visible })`: hides content behind a "Reveal" button; `render(hide)`
  returns the content.
- `kit.clampGroup()`: blocks that clamp to 320px together. `.text(value)` and `.node(child)` add blocks,
  `.button()` returns the Expand/Collapse toggle, `.refresh()` re-measures.
- `kit.segmented(options, value, onChange)`: segmented control from `[key, label]` pairs.
- `kit.emailFrame(html)`: renders an HTML email in a nested frame on a white background.
- Class `kit-bar` with a `span` child of `width: N%`: a progress bar.

## Example

```html
<script>
  kit.mount(({ trace }) => {
    const question = kit.first(kit.get(trace.input, ["question"]), trace.input);
    const answer = kit.first(kit.get(trace.output, ["answer"]), trace.output);
    return kit.h(
      "div",
      { class: "kit-stack" },
      kit.card({ title: "Question", body: kit.h("div", { class: "kit-text", text: kit.asText(question) }) }),
      kit.card({ title: "Answer", highlight: true, body: kit.h("div", { class: "kit-text", text: kit.asText(answer) }) }),
    );
  });
</script>
```
