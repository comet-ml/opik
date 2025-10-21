# Opik Python SDK Design Documentation

Comprehensive architecture documentation for contributors and team members. These guides explain how the SDK works internally, not how to use it.

## 📚 Documentation

| Document | Priority | Description |
|----------|----------|-------------|
| **[API and Data Flow](API_AND_DATA_FLOW.md)** | ⭐ Start Here | Core architecture, 3 layers, sync vs async operations, batching, message processing |
| **[Testing](TESTING.md)** | 🔵 Essential | Test categories, fake backend, TraceModel/SpanModel patterns |
| **[Integrations](INTEGRATIONS.md)** | 🟣 As Needed | Integration patterns (method patching, callback, hybrid), streaming strategies |
| **[Evaluation](EVALUATION.md)** | 🟣 As Needed | Evaluation engine, all 4 evaluation methods, metrics architecture |

## 🚀 Quick Start

### First-Time Contributors

1. Read **[API and Data Flow](API_AND_DATA_FLOW.md)** - Understand core architecture
2. Read **[Testing](TESTING.md)** - Learn testing patterns
3. Choose domain doc based on your task

### By Task

| Task | Document | Key Sections |
|------|----------|--------------|
| Understanding `@opik.track` | [API and Data Flow](API_AND_DATA_FLOW.md) | Decorator Data Flow, Context Management |
| Adding integration | [Integrations](INTEGRATIONS.md) | Integration Patterns, existing integrations |
| Creating metric, evaluation pipelines | [Evaluation](EVALUATION.md) | Metrics Architecture |
| Debugging performance | [API and Data Flow](API_AND_DATA_FLOW.md) | Batching System, Performance |
| Writing tests | [Testing](TESTING.md) | Testing Patterns, fake backend |

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

**Last Updated**: 2025-01-20

**Questions?** Open an issue or contact the SDK team.
