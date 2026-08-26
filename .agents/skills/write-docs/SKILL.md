---
name: write-docs
description: Authoring Fern MDX documentation pages for the Opik docs site, plus release-note and changelog routing. Use when writing or updating pages under apps/opik-documentation/documentation/fern/, drafting PR descriptions, or picking the right changelog surface.
---

# Write Docs

The Opik docs site is built with [Fern](https://buildwithfern.com/) from MDX sources under `apps/opik-documentation/documentation/fern/`. There is one content surface: `fern/docs-v2/`. The old v1 surface (`fern/docs/`) was removed; every v1 URL now redirects to its Opik 2 equivalent through the `redirects:` list in `fern/docs.yml`.

## Where new pages live

- Create the file at `apps/opik-documentation/documentation/fern/docs-v2/<section>/<page-name>.mdx`.
- Register it in `fern/docs.yml` under the right `section:` block in `navigation:`.
- Routing is not implied by folder layout. Always check `navigation:` in `fern/docs.yml`.

## Frontmatter template

Every page uses YAML frontmatter. `title` and `headline` are required; the `og:*` fields are strongly recommended for SEO/social sharing and are present on every page. Do not repeat `title` as an inline `# H1` in the body — Fern renders it from frontmatter.

```yaml
---
title: Page Title
headline: Page Title | Opik Documentation
og:title: Page Title — Opik
og:description: One-line summary used for social sharing and previews
og:site_name: Opik Documentation
---
```

`subtitle: ...` is an optional field used on concept/overview pages to add a secondary line. Landing/overview pages may also set `layout: overview`.

## Style and voice

Pull examples from existing pages when unsure — `fern/docs-v2/tracing/advanced/log_traces.mdx`, `fern/docs-v2/tracing/concepts.mdx`, and `fern/docs-v2/quickstart.mdx` are good anchors.

- **Person:** "you" and imperative voice. Professional but approachable.
- **Opening:** one or two intro sentences before the first `##` heading. No inline H1.
- **Headings:** `##` for top-level sections, `###` for subsections. Never introduce an inline `#` — that collides with the frontmatter title.
- **Paragraphs:** keep them short (2–4 sentences). Mix prose with bullet lists for features, options, and prerequisites.
- **Page shape:**
  - *Concept pages* start with the *why*, then definitions.
  - *How-to pages* start with brief context, then the task steps.
  - *Overview/landing pages* lead with a short pitch and a `<CardGroup>` of links.
- **End with "## Next steps"** linking to 2–4 related pages when useful.

## Fern MDX components

All examples below are taken from real pages in the repo.

### `<Tabs>` / `<Tab>` — SDK, language, or environment choice

Use when the whole section varies (not just a code block). Attach `language="..."` so Fern groups tabs across the site by the reader's last choice.

```mdx
<Tabs>
  <Tab value="Python SDK" title="Python SDK" language="python">
    ```bash
    pip install opik
    ```
  </Tab>
  <Tab value="Typescript SDK" title="Typescript SDK" language="typescript">
    ```bash
    npm install opik
    ```
  </Tab>
  <Tab value="OpenTelemetry" title="OpenTelemetry">
    ...
  </Tab>
</Tabs>
```

### `<Steps>` / `<Step>` — walkthroughs

For quickstarts, installs, and any sequential procedure. `title` on each `<Step>` is optional.

```mdx
<Steps>
  <Step title="Install the Opik skill">
    ```bash
    npx skills add comet-ml/opik-skills
    ```
  </Step>
  <Step title="Run the integration">
    Once the skill is installed, you can add tracing using the following prompt:
    ```
    Instrument my agent with Opik using the /instrument command.
    ```
  </Step>
</Steps>
```

### `<CodeBlocks>` — multi-language code, identical surrounding prose

Prefer this over `<Tabs>` when only the code varies.

```mdx
<CodeBlocks>
  ```python title="Python"
  import opik
  opik.configure()
  ```
  ```ts title="Typescript"
  import Opik from "opik";
  const client = new Opik();
  ```
</CodeBlocks>
```

### `<CardGroup>` / `<Card>` — landing and integration grids

```mdx
<CardGroup cols={3}>
  <Card title="LangChain" href="/integrations/langchain" icon={<img src="/img/tracing/langchain.svg" />} iconPosition="left"/>
  <Card title="LlamaIndex" href="/integrations/llama_index" icon={<img src="/img/tracing/llamaindex.svg" />} iconPosition="left"/>
  <Card title="Anthropic" href="/integrations/anthropic" icon={<img src="/img/tracing/anthropic.svg" />} iconPosition="left"/>
</CardGroup>
```

### `<AccordionGroup>` / `<Accordion>` — FAQs and expandable advanced topics

```mdx
<AccordionGroup>
  <Accordion title="Why use the optimizer?">
    The Agent Optimizer provides a unified interface...
  </Accordion>
</AccordionGroup>
```

### `<Frame>` — image wrapper (always wrap images)

```mdx
<Frame>
  <img src="/img/tracing/introduction.png" />
</Frame>
```

### Callouts: `<Tip>`, `<Note>`, `<Warning>`, `<Info>`, `<Callout>`

Pick by intent, not aesthetics:

- **`<Tip>`** — cross-references, shortcuts, "If you're just getting started, see..."
- **`<Note>`** — clarifications and recommendations that aren't risky
- **`<Warning>`** — breaking changes, footguns, prerequisites that will break things
- **`<Info>`** — informational, interchangeable with `<Note>` in practice
- **`<Callout>`** — catch-all when none of the above fits

```mdx
<Tip>
  If you are just getting started with Opik, we recommend first checking out the [Quickstart](/quickstart) guide.
</Tip>

<Warning>
  Note that the authorization header value does not include the `Bearer ` prefix.
</Warning>
```

## Code examples

- Use `<CodeBlocks>` for multi-language blocks; use `<Tabs>` when surrounding prose also varies.
- Install commands are inline bash blocks (`pip install opik`, `npm install opik`).
- There is no snippet-include system. All code is written inline in MDX.
- **Use placeholders for credentials:** `<API_KEY>`, `<TOKEN>`, `<your-api-key>`. Never commit real keys.

## Images

- Store under `apps/opik-documentation/documentation/fern/img/<section>/...`.
- Reference from MDX as `/img/<section>/<file>.png` (path is rooted at the docs base).
- **Never** put new assets in `static/img/` — that folder is legacy and only kept for external integrations.
- Always wrap with `<Frame>`. Captions are not a repo convention.

## Cross-links

Root-relative, slug-based paths only (`/section/page`).

- **Never** link internal docs pages with full `https://www.comet.com/docs/opik/...` URLs. Full URLs bypass the Fern preview build, and they 404 in the link checker when the target page ships in the same PR. Use the root-relative slug path instead.
- No file-relative paths (`../foo`) either.
- Build the path from `navigation:` in `fern/docs.yml`, including every nested `section:` slug. Example: the "Manage datasets" page sits inside an `advanced` section, so the path is `/evaluation/advanced/manage_datasets`, not `/evaluation/manage_datasets`.

```mdx
[Python SDK](/reference/python-sdk/overview)
[Log traces](/tracing/advanced/log_traces)
[Integrations overview](/integrations/overview)
```

In-page anchors use the heading slug: `[Concepts](#concepts)`.

## Routing: adding a page to `docs.yml`

Add a page entry under the correct `section:` in `navigation:` (keep the YAML at 2-space indent):

```yaml
- page: Page Title
  path: ./docs-v2/section/page-name.mdx
  slug: page-name
```

## File naming

- Kebab-case for new files: `getting-started.mdx`, `log-traces.mdx`.
- When editing an existing section that uses snake_case, match neighbors rather than renaming. Renames require redirect entries in `docs.yml`.

## Local verification

```bash
cd apps/opik-documentation/documentation
npm install          # first time only
npm run dev          # live-reload preview
```

Open the rendered page and confirm:
- Frontmatter renders (title shows, no stray H1 in body).
- Every MDX component resolves (no raw `<Tabs>` tags visible).
- Every link works (no 404s, no `Broken link` warnings in the terminal).
- Images load.

## Changelog routing

Pick the changelog target by scope — do not default everything to one surface.

- `apps/opik-documentation/documentation/fern/docs-v2/self-host/changelog.mdx` — self-hosted deployment changelog shown at `/docs/opik/self-host/changelog`. Breaking, critical, or security-impacting changes only. (The former repo-root `CHANGELOG.md` was removed; its content lives on this page now.)
- `apps/opik-documentation/documentation/fern/docs-v2/changelog/*.mdx` — general product release notes shown at `/docs/opik/changelog`. One dated `.mdx` per entry.
- `apps/opik-documentation/documentation/fern/docs-v2/development/optimization-runs/changelog.mdx` — Agent Optimizer version updates (e.g. `sdks/opik_optimizer` releases like `3.1.0`).
- Liquibase `changelog.xml` files are migration manifests, not user-facing release notes. Do not put prose there.
- When unsure, confirm the surface from `fern/docs.yml` before editing.

### Changelog entry template

```markdown
### [VERSION] - [DATE]

#### New Features
- **Feature Name**: Brief description

#### Improvements
- **Improvement**: What changed and why

#### Bug Fixes
- **Fix**: What was broken (#issue)

#### Breaking Changes
- **Change**: What breaks, migration steps
```

## Feature documentation checklist

When documenting a new feature, cover:

- **User impact** — What capability does this add? How do users access it?
- **Technical changes** — API endpoints and params, SDK methods, config or env vars, migrations.
- **Breaking changes** — What breaks and the migration path, if any.

Keep it user-facing: avoid implementation detail unless it affects how someone uses the feature.

## PR description template

Use the repository template at `.github/pull_request_template.md` — read the FULL file before drafting (the required sections continue past the first screen). CI (`.github/workflows/pr-lint.yml`) fails any PR whose description is missing one of these exact headings:

- `## Details`
- `## Change checklist`
- `## Issues`
- `## Testing`
- `## Documentation`

Also fill in the template's `## AI-WATERMARK` section (yes/no; if yes: Tools, Model(s), Scope, Human verification). Never invent a different structure such as `## Summary` / `## Test Plan`.

A section that does not apply gets `N/A` — never delete a heading.

### `## Details` — style

Write what changes for a user. A reviewer reads the diff for the code; this section tells them what is different when they use the product.

- **Short.** Most PRs need 3–10 bullets. If it runs longer, the section is doing the diff's job — cut it.
- **Bullets, not prose paragraphs.** One behavior per bullet. Nest one level for sub-cases.
- **Authoritative.** State what happens: "The run is scored once." Not "This should now mean that the run will be scored once."
- **No fluff.** No motivation paragraph, no "this PR …", no approach summary, no benefits list, no restating the diff.
- **Observable behavior first.** What the UI shows, what the API returns, what gets scored, stored or logged. Name a class, method or file only when the behavior makes no sense without it.

Pick the shape that fits the change — do not force one:

- **Before / After bullet lists** when a behavior changed and the contrast is the point.
- **A flat bullet list** for a new capability, where there is no "before".
- **One or two lines** when users cannot see the change (refactor, dependency bump) — say what is unchanged and what improved, then stop.

## Internationalized READMEs

`readme_CN.md`, `readme_ES.md`, `readme_FR.md`, `readme_DE.md` are AI machine-translated from the English `README.md`.

- Each non-English README has a blockquote notice at the top warning that it is AI-translated and welcoming improvements. Keep it.
- When the English README changes meaningfully, re-translate the affected files. Do not hand-edit translated READMEs for content changes — update the English source and re-translate.

## Forbidden and discouraged

- No real API keys, tokens, or workspace IDs in examples — always placeholders.
- Do not put new images outside `fern/img/`. `static/img/` is legacy-only and cannot be deleted because of external integrations.
- Do not infer URL paths from folder layout — always consult `navigation:` in `fern/docs.yml`.
- Do not add an inline `# H1` inside the body — the frontmatter `title` already provides it.

## Key files

- `apps/opik-documentation/documentation/fern/docs.yml` — site config: tabs, `navigation:` routing, and `redirects:`. Edit `navigation:` when adding pages.
- `apps/opik-documentation/documentation/fern/docs-v2/` — target directory for new pages.
- `apps/opik-documentation/documentation/fern/img/` — image storage.
- `apps/opik-documentation/AGENTS.md` — docs-module contribution rules.
- `.github/release-drafter.yml` — release notes template.
