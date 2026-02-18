# Repository Guidelines

## Scope & Inheritance
- This file contains docs-module specifics only.
- Follow `../../AGENTS.md` for shared monorepo workflow, PR, and security policy.

## Project Structure & Module Organization
`apps/opik-documentation` contains two documentation pipelines:

- `documentation/` (primary Fern docs site)
  - `fern/docs/` for documentation pages and navigation content
  - `docs/cookbook/` for source Jupyter notebooks
  - `static/` and `fern/img/` for assets (use only `fern/img` for new images)
  - `update_cookbooks.sh` to regenerate cookbook markdown from notebooks
- `python-sdk-docs/` (Python SDK reference docs)
  - `source/` for `.rst` content
  - `Makefile` and `requirements.txt` for doc builds
- `README.md` is present in this folder; follow `documentation/fern/docs/contributing/*` for contribution process details and conventions.

## Build, Test, and Development Commands
- `cd documentation && npm install`
  - Install Fern docs dependencies (run once or after dependency updates).
- `cd documentation && npm run dev`
  - Run docs website locally with live reload.
- `cd documentation && ./update_cookbooks.sh`
  - Regenerates `fern/docs/cookbook/*.mdx` from `docs/cookbook/*.ipynb`.
- `cd python-sdk-docs && pip install -r requirements.txt`
  - Install Sphinx tooling for SDK references.
- `cd python-sdk-docs && make dev`
  - Serve Python SDK docs at `http://127.0.0.1:8000`.
- `cd python-sdk-docs && make build`
  - Generate static HTML into `python-sdk-docs/build/html`.

## Coding Style & Naming Conventions
- Write concise, user-oriented docs (avoid internal implementation detail unless needed).
- Use existing Markdown/MDX style in `documentation/fern/docs/**` and reStructuredText style in `python-sdk-docs/source/**`.
- Keep file names descriptive and kebab-case (`quickstart.mdx`, `api-reference.mdx`).
- Keep 2-space indentation in YAML/JSON snippets and frontmatter.
- Store new images under `documentation/fern/img/`.

## Testing Guidelines
- There is no dedicated automated docs test suite in this directory.
- Validation is primarily local render verification:
  - run `npm run dev` for Fern pages,
  - run `make dev` for Sphinx reference docs.
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
