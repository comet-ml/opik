# Repository Guidelines

## Scope & Inheritance
- This file contains docs-module specifics only.
- Follow `../../AGENTS.md` for shared monorepo workflow, PR, and security policy.

## Project Structure & Module Organization
`apps/opik-documentation` contains the Fern documentation pipeline:

- `documentation/` (primary Fern docs site)
  - `fern/docs/` for documentation pages and navigation content
  - `docs/cookbook/` for source Jupyter notebooks
  - `static/` and `fern/img/` for assets (use only `fern/img` for new images)
  - `update_cookbooks.sh` to regenerate cookbook markdown from notebooks
- `README.md` is present in this folder; follow `documentation/fern/docs/contributing/*` for contribution process details and conventions.

## Build, Test, and Development Commands
See also `../../AGENTS.md#build-test-and-development-commands` for full monorepo commands.

- `cd documentation && npm install`
  - Install Fern docs dependencies (run once or after dependency updates).
- `cd documentation && npm run dev`
  - Run docs website locally with live reload.
- `cd documentation && ./update_cookbooks.sh`
  - Regenerates `fern/docs/cookbook/*.mdx` from `docs/cookbook/*.ipynb`.
- `cd documentation/fern && fern check --warnings`
  - Validate Fern docs configuration and navigation.
- `cd documentation/fern && fern docs md generate`
  - Generate Fern native library markdown/navigation output before preview/deploy.

## Coding Style & Naming Conventions
- Write concise, user-oriented docs (avoid internal implementation detail unless needed).
- Use existing Markdown/MDX style in `documentation/fern/docs/**`.
- Keep file names descriptive and kebab-case (`quickstart.mdx`, `api-reference.mdx`).
- Keep 2-space indentation in YAML/JSON snippets and frontmatter.
- Treat `documentation/fern/docs.yml` (or `docs.yaml` where used) as the routing source of truth; do not infer URL paths from folder layout alone.
- Store new images under `documentation/fern/img/`.

### Python SDK Reference Location Rule
- Python SDK reference docs live under `documentation/fern/docs/reference/python-sdk/**`.
- Generated Core API pages are produced via `documentation/fern/docs.yml` `libraries` configuration.
- Migrated legacy SDK narrative content lives under `documentation/fern/docs/reference/python-sdk/sphinx-migrated/**`.

## Testing Guidelines
- There is no dedicated automated docs test suite in this directory.
- Validation is primarily local render verification:
  - run `npm run dev` for Fern pages,
  - run `fern check --warnings` and `fern docs md generate` in `documentation/fern`.
- For generated artifacts (cookbooks/SDK docs), verify output in local preview before merging.

## Agent Contribution Workflow
- This module is part of the Opik monorepo; follow the shared workflow in `../../AGENTS.md#agent-contribution-workflow`.
- Run docs-local validation commands in this file before requesting review.

## Commit & Pull Request Guidelines
- Follow shared commit/PR policy in `../../AGENTS.md`.
- Docs-specific additions: include validation steps and screenshots for visible docs changes when relevant.

## Security & Configuration Tips
- Follow shared security policy in `../../AGENTS.md`.
- Docs-specific rule: use placeholders (`<API_KEY>`, `<TOKEN>`) and never publish real credentials in docs or notebooks.
