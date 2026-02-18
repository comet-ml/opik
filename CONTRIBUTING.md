# Contributing to Opik

Thanks for contributing to Opik. This file is intentionally lean and policy-focused.
Detailed setup and component workflows live in Fern docs.

Review the CLA before contributing:
- https://github.com/comet-ml/opik/blob/main/CLA.md

## Fast path
1. Open or confirm a tracked issue first (`Fixes #...` or `Resolves #...`).
2. Pick the touched area (`apps/`, `sdks/`, `tests_end_to_end/`) and follow its guide.
3. Create a branch: `{username}/{ticket}-{summary}` where ticket is `OPIK-####`, `issue-####`, or `NA`.
4. Make scoped changes only; avoid unrelated refactors.
5. Run relevant formatter/lint/tests for your area.
6. Open a draft PR with GitHub CLI: `gh pr create --draft`.
7. Fill `.github/pull_request_template.md` completely.

## Canonical contribution guides (Fern)
Use these as the source of operational detail:
- Overview: `apps/opik-documentation/documentation/fern/docs/contributing/overview.mdx`
- Local development: `apps/opik-documentation/documentation/fern/docs/contributing/local-development.mdx`
- Backend: `apps/opik-documentation/documentation/fern/docs/contributing/backend.mdx`
- Frontend: `apps/opik-documentation/documentation/fern/docs/contributing/frontend.mdx`
- Python SDK: `apps/opik-documentation/documentation/fern/docs/contributing/python-sdk.mdx`
- TypeScript SDK: `apps/opik-documentation/documentation/fern/docs/contributing/typescript-sdk.mdx`
- Documentation: `apps/opik-documentation/documentation/fern/docs/contributing/documentation.mdx`
- Optimizer SDK: `apps/opik-documentation/documentation/fern/docs/contributing/agent-optimizer-sdk.mdx`

## Commit and PR conventions
- First commit (used as PR title source):
  - `[<TICKET-KEY>] [BE|FE|SDK|DOCS|INFRA|NA] <type>: <summary>`
- Follow-up commits:
  - `<type>(<scope>): <summary>` (`feat`, `fix`, `refactor`, `test`, `docs`, `chore`)
- Include screenshots/videos for user-facing UI changes.
- Keep customer names, internal tickets beyond required references, and sensitive operational context out of public PR text.

## AI-assisted contribution policy (required)
AI assistance is allowed. Human authors remain fully accountable for correctness, licensing, and security.

Every PR must include an AI disclosure watermark block in the PR description:

```markdown
## AI Assistance
- AI-WATERMARK: yes|no
- Tool(s): <tool names>
- Model(s): <model IDs>
- Scope: <files/sections generated or edited>
- Human verification: <tests/checks/manual review performed - default is no/na>
```

Rules:
- Allways run relevant tests/linters for touched code.
- Allways be explicit about human/users interaction with produced output.
- Allways review prior issue, pull-requests and code-base for existing solutions.
- Allways address any system generated reviews (Baz, Greptile).
- Never submit unreviewed AI output.
- Never include secrets, tokens, private prompts, internal system instructions, or customer-sensitive data in generated/public content.
- Never disclose vulnerabilities, exploit steps, or incident details in public issues/PRs. Use private maintainer/security channels.

## Agent/editor setup
- Cursor compatibility: `make cursor` (`.cursor -> .agents`)
- Claude sync: `make claude` (syncs `.agents` to `.claude`)
- Git hooks: `make hooks`
