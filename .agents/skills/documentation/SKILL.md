---
name: documentation
description: Feature documentation and release notes patterns. Use when documenting changes, writing PR descriptions, or preparing releases.
---

# Documentation

## PR Description

```markdown
## Summary
- What this PR does (bullet points)

## Test Plan
- How to verify it works

## Related Issues
- Resolves #123
```

## Changelog Entry

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

## Feature Documentation

When documenting a feature, cover:

**User Impact**
- What capability does this add?
- How do users access it?

**Technical Changes**
- API changes (endpoints, params)
- SDK changes (new methods)
- Database migrations
- Config changes

**Breaking Changes** (if any)
- What breaks
- Migration steps

## Key Files

- `CHANGELOG.md` - Self-hosted deployment changelog (breaking/critical changes only)
- `apps/opik-documentation/documentation/fern/docs/changelog/` - Main product docs changelog entries (dated `.mdx` files)
- `apps/opik-documentation/documentation/fern/docs/agent_optimization/getting_started/changelog.mdx` - Agent Optimizer release changelog
- `apps/opik-documentation/documentation/fern/docs.yml` - Docs routing/navigation source of truth for changelog surfaces
- `.github/release-drafter.yml` - Release template

## Changelog Routing Rules

- Pick the changelog target by scope; do not default everything to root `CHANGELOG.md`.
- Use `CHANGELOG.md` only for self-hosted deployment breaking/critical/security-impacting notes.
- Use `apps/opik-documentation/documentation/fern/docs/changelog/*.mdx` for general Opik product release notes shown in `/docs/opik/changelog`.
- Use `apps/opik-documentation/documentation/fern/docs/agent_optimization/getting_started/changelog.mdx` for Agent Optimizer version updates (for example `sdks/opik_optimizer` releases like `3.1.0`).
- Liquibase `changelog.xml` files are migration manifests, not user-facing release-note changelogs.
- If unsure where an entry belongs, confirm the surface from `apps/opik-documentation/documentation/fern/docs.yml` before editing.

## Images in documentation

- **Use `fern/img`** for documentation images (e.g. `apps/opik-documentation/documentation/fern/img/...`).
- **Do not use `static/img`** for new assets; it is a legacy folder used by external integrations and cannot be deleted.
- Reference images in docs as `/img/...` (e.g. `/img/tracing/openai_integration.png`).
- In repos that define `docs.yaml`/`docs.yml`, treat that file as the routing source of truth; do not assume URLs mirror directory layout.

## Internationalized READMEs

Non-English README files (`readme_CN.md`, `readme_JP.md`, `readme_KO.md`, `readme_PT_BR.md`) are AI machine-translated from the English `README.md`.

- Each non-English README must have a notice at the top (as a blockquote) warning that the file is AI-translated and welcoming improvements.
- When the English README is updated with significant content changes, re-translate the affected non-English READMEs using AI and update accordingly.
- Do not manually edit translated READMEs for content changes; update the English source and re-translate.

## Style

- User perspective, not implementation details
- Specific (version numbers, dates)
- Code examples for API/SDK changes
- Concise - link to docs, don't duplicate
