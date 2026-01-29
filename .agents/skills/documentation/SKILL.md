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

## Style

- User perspective, not implementation details
- Specific (version numbers, dates)
- Code examples for API/SDK changes
- Concise - link to docs, don't duplicate
