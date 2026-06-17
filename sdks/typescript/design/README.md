# Opik TypeScript SDK Design Documentation

Comprehensive architecture documentation for contributors and team members. These guides explain how the SDK works internally, not how to use it.

## 📚 Documentation

| Document | Priority | Description |
|----------|----------|-------------|
| **[API and Data Flow](API_AND_DATA_FLOW.md)** | ⭐ Start Here | Core architecture, client design, batch queues, async patterns |
| **[Tracing](TRACING.md)** | ⭐ Start Here | Trace/span model, native API, `track` decorator, integrations, config — usage-focused onboarding |
| **[Testing](TESTING.md)** | 🔵 Essential | Test categories, MSW mocking, Vitest patterns |
| **[Integrations](INTEGRATIONS.md)** | 🟣 As Needed | Integration patterns (Proxy, Callback, Exporter), streaming support |
| **[Evaluation](EVALUATION.md)** | 🟣 As Needed | Evaluation engine, metrics architecture, prompt evaluation |

## 🚀 Quick Start

### First-Time Contributors

1. Read **[API and Data Flow](API_AND_DATA_FLOW.md)** - Understand core architecture
2. Read **[Testing](TESTING.md)** - Learn testing patterns
3. Choose domain doc based on your task

### By Task

| Task | Document | Key Sections |
|------|----------|--------------|
| Understanding `track` decorator | [API and Data Flow](API_AND_DATA_FLOW.md) | Decorator Implementation, AsyncLocalStorage |
| Adding integration | [Integrations](INTEGRATIONS.md) | Integration Patterns, existing integrations |
| Creating metrics | [Evaluation](EVALUATION.md) | Metrics Architecture, BaseMetric |
| Debugging | [API and Data Flow](API_AND_DATA_FLOW.md) | Batch Queue System |
| Writing tests | [Testing](TESTING.md) | Testing Patterns, MSW mocking |


### Module Architecture

```
opik/
├── client/           # OpikClient, batch queues, singleton
├── config/           # Configuration loading (env, file, defaults)
├── configure/        # CLI tool for project setup (npx opik-ts configure)
├── decorators/       # track decorator with AsyncLocalStorage
├── tracer/           # Trace and Span classes
├── dataset/          # Dataset management
├── experiment/       # Experiment tracking
├── evaluation/       # Evaluation engine and metrics
├── prompt/           # Prompt management (text + chat)
├── query/            # OQL parser
├── integrations/     # Separate packages (opik-openai, etc.)
├── rest_api/         # Auto-generated API client (Fern)
├── errors/           # Error classes
├── types/            # Shared TypeScript type definitions
└── utils/            # Logging, ID generation, helpers
```

#### Configure CLI (`configure/`)

A separate CLI tool (`npx opik-ts configure`) that helps set up Opik in Node.js projects:
- Interactive setup wizard for API key and workspace
- Environment variable configuration
- Editor rules integration (Cursor/VS Code)
- Local development mode (`--use-local`)

## 🔄 Maintenance

**Update documentation when**:
- Major architectural changes
- New patterns introduced
- New integrations added
- Performance optimizations

**Quality standards**:
- Accurate (reflects codebase)
- Clear (easy for newcomers)
- Practical (real examples)

---

**Last Updated**: 2026-01-20

**Questions?** Open an issue or contact the SDK team.
