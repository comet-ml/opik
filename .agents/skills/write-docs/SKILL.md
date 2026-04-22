---
name: write-docs
description: Authoring Fern MDX documentation pages for the Opik docs site, plus release-note and changelog routing. Use when writing or updating pages under apps/opik-documentation/documentation/fern/, drafting PR descriptions, or picking the right changelog surface.
---

# Write Docs

The Opik docs site is built with [Fern](https://buildwithfern.com/) from MDX sources under `apps/opik-documentation/documentation/fern/`. Two content surfaces coexist:

- `fern/docs/` — **v1** (the established surface, source of style truth)
- `fern/docs-v2/` — **latest** (where new pages go)

New pages should land in `docs-v2/`. v1 is the reference for writing style and component usage because it is far richer.

## Where new pages live

- Create the file at `apps/opik-documentation/documentation/fern/docs-v2/<section>/<page-name>.mdx`.
- Register it in `fern/versions/latest.yml` under the right `section:` block.
- Only touch `fern/versions/v1.yml` if the page must also ship in the v1 build (rare).
- **Do not edit** `fern/docs.yml` when adding a page — that file is the global site config (tabs, redirects), not per-version routing.
- Routing is not implied by folder layout. Always check the version YAML.

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

Pull examples from v1 pages when unsure — `fern/docs/tracing/log_traces.mdx`, `fern/docs/tracing/concepts.mdx`, and `fern/docs/quickstart.mdx` are good anchors.

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

Absolute, slug-based paths only. No relative paths (`../foo`).

```mdx
[Python SDK](/reference/python-sdk/overview)
[Log traces](/tracing/log_traces)
[Integrations overview](/integrations/overview)
```

In-page anchors use the heading slug: `[Concepts](#concepts)`.

## Routing: adding a page to `latest.yml`

Add a page entry under the correct `section:` (keep the YAML at 2-space indent):

```yaml
- page: Page Title
  path: ../docs-v2/section/page-name.mdx
  slug: page-name
```

If the page also needs to ship in v1, mirror the entry in `fern/versions/v1.yml`. Leave `fern/docs.yml` alone.

## File naming

- Kebab-case for new files: `getting-started.mdx`, `log-traces.mdx`.
- When editing an existing section that uses snake_case (common in v1), match neighbors rather than renaming. Renames require redirect entries in `docs.yml`.

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

Pick the changelog target by scope — do not default everything to the root `CHANGELOG.md`.

- `CHANGELOG.md` (repo root) — self-hosted deployment changelog. Breaking, critical, or security-impacting changes only.
- `apps/opik-documentation/documentation/fern/docs/changelog/*.mdx` — general product release notes shown at `/docs/opik/changelog`. One dated `.mdx` per entry.
- `apps/opik-documentation/documentation/fern/docs/agent_optimization/getting_started/changelog.mdx` — Agent Optimizer version updates (e.g. `sdks/opik_optimizer` releases like `3.1.0`).
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

```markdown
## Summary
- What this PR does (bullet points)

## Test Plan
- How to verify it works

## Related Issues
- Resolves #123
```

## Internationalized READMEs

`readme_CN.md`, `readme_JP.md`, `readme_KO.md`, `readme_PT_BR.md`, `readme_AR.md`, `readme_DE.md`, `readme_ES.md`, `readme_FR.md` are AI machine-translated from the English `README.md`.

- Each non-English README has a blockquote notice at the top warning that it is AI-translated and welcoming improvements. Keep it.
- When the English README changes meaningfully, re-translate the affected files. Do not hand-edit translated READMEs for content changes — update the English source and re-translate.

## Forbidden and discouraged

- No real API keys, tokens, or workspace IDs in examples — always placeholders.
- Do not edit `fern/docs/cookbook/*.mdx` by hand. Those files are regenerated from `docs/cookbook/*.ipynb` by `update_cookbooks.sh`.
- Do not put new images outside `fern/img/`. `static/img/` is legacy-only and cannot be deleted because of external integrations.
- Do not infer URL paths from folder layout — always consult the version YAML.
- Do not add an inline `# H1` inside the body — the frontmatter `title` already provides it.

## Key files

- `apps/opik-documentation/documentation/fern/versions/latest.yml` — routing for the latest version (edit when adding pages).
- `apps/opik-documentation/documentation/fern/versions/v1.yml` — routing for v1 (edit only if the page ships in v1 too).
- `apps/opik-documentation/documentation/fern/docs.yml` — global site config (tabs, redirects). Treat as read-only for page adds.
- `apps/opik-documentation/documentation/fern/docs-v2/` — target directory for new pages.
- `apps/opik-documentation/documentation/fern/img/` — image storage.
- `apps/opik-documentation/documentation/update_cookbooks.sh` — regenerates cookbook MDX from notebooks.
- `apps/opik-documentation/AGENTS.md` — docs-module contribution rules.
- `.github/release-drafter.yml` — release notes template.
