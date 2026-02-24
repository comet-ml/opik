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

- `CHANGELOG.md` - Main changelog
- `.github/release-drafter.yml` - Release template

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
