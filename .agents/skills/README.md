# Skills Index

Domain-specific agent skills for the Opik monorepo. Each skill provides patterns, conventions, and guidance for a specific area of the codebase.

| Skill | Path | Description |
|-------|------|-------------|
| add-code-quality-hook | `add-code-quality-hook/` | Recipe for wiring a new linter into Opik's unified `🐙 Code Quality` pipeline (pre-commit + CI). Use when adding a pre-commit-driven linter — enumerates every file that must change, the non-obvious gotchas (mandatory explicit `files:`, blank-description trap, `TOOLCHAIN_BY_ID`/`TYPED_IDS`), the fix-vs-suppress policy, and the verification loop. |
| analytics-instrumentation | `analytics-instrumentation/` | Add analytics events to Opik features. Use when wiring PostHog events on the frontend or backend for product analytics tracking. |
| debugging-e2e-tests | `debugging-e2e-tests/` | Investigate a failed Opik E2E test and propose a fix (read-only). Use when a test goes red in CI, a TestOps launch, or locally — gathers the trace + history, classifies regression vs. flake, proposes a fix. |
| diagram-generation | `diagram-generation/` | Generate self-contained HTML architecture diagrams. Use when creating visual diagrams for PRs, task plans, or architectural explanations. |
| documentation | `documentation/` | Feature documentation and release notes patterns. Use when documenting changes, writing PR descriptions, or preparing releases. |
| local-dev | `local-dev/` | Local development environment setup and commands. Use when helping with dev server, Docker, or local testing. |
| metrics-instrumentation | `metrics-instrumentation/` | Instrument an opik-backend workflow with operational OpenTelemetry metrics and a flow-ordered Grafana dashboard. Use when a pipeline is a black box and you need per-stage throughput/latency/error visibility plus a per-customer drill. Distinct from analytics-instrumentation (PostHog product events). |
| opik-backend | `opik-backend/` | Java backend patterns for Opik. Use when working in `apps/opik-backend`, designing APIs, database operations, or services. |
| opik-external-integrations | `opik-external-integrations/` | Build an Opik integration that lives outside this repo — a standalone `opik-*` package or Opik support contributed into a third-party project (LiteLLM, Dify, …). Activates only for external-repo targets. |
| opik-frontend | `opik-frontend/` | React frontend patterns for Opik. Use when working in `apps/opik-frontend`, on components, state, or data fetching. |
| opik-integrations | `opik-integrations/` | Build, update, test, and document Opik SDK integrations (Python & TypeScript) that ship under `sdks/`. Runs a questionnaire-first, autonomous workflow: investigate → design → implement → verify via the Opik MCP → test → document → report. |
| playwright-pom-discovery | `playwright-pom-discovery/` | Choose stable selectors against the live UI when building a Page Object Model for the E2E suite (`tests_end_to_end/e2e/pom/`). Used as the discovery sub-step by `writing-e2e-tests`. |
| python-sdk | `python-sdk/` | Python SDK patterns for Opik. Use when working in `sdks/python`, on SDK APIs, integrations, or message processing. |
| typescript-sdk | `typescript-sdk/` | TypeScript SDK patterns for Opik. Use when working in `sdks/typescript`. |
| writing-e2e-tests | `writing-e2e-tests/` | Add an end-to-end test for an Opik feature in `tests_end_to_end/e2e/`. Use when a developer wants to write a test for a feature, page, or branch — runs the full loop: analyze, explore the live UI, write the POM + spec, and run it locally until green. |

Each skill directory contains a `SKILL.md` entry point plus supporting documents (testing, code quality, patterns, etc.).
