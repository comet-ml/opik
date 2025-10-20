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

## 🔍 Quick Reference

### Architecture

**3 Layers**:
1. Public API (`opik.Opik`, `@track`)
2. Message Processing (async, observability only)
3. REST API Client (HTTP communication)

**Two Execution Paths**:
- **Async** (trace/span/feedback) → Message Processing → batching, retry
- **Sync** (dataset/experiment/search) → Direct REST → immediate return

### Key Components

| Component | Location |
|-----------|----------|
| `Opik` | `api_objects/opik_client.py` |
| `@track` | `decorator/tracker.py` |
| `Streamer` | `message_processing/streamer.py` |
| `OpikContextStorage` | `context_storage.py` |
| Message types | `message_processing/messages.py` |

### Testing

| Category | Backend | Speed | Files |
|----------|---------|-------|-------|
| Unit | Fake | ⚡ | `tests/unit/` |
| Library Integration | Fake | ⚡ | `tests/library_integration/` |
| E2E | Real | 🐌 | `tests/e2e/` |
| E2E Lib Integration | Real | 🐌 | `tests/e2e_library_integration/` |

Use `TraceModel`/`SpanModel` + `assert_equal()` for assertions.

## 🛠️ Adding Features

**New Integration**:
1. Choose pattern: [Integrations](INTEGRATIONS.md) - "Integration Patterns"
2. Reference existing: OpenAI (method patching) or LlamaIndex (callback)
3. Implement in `opik/integrations/mylib/`
4. Add usage builder in `opik/llm_usage/`, register in factory
5. Add tests in `tests/library_integration/mylib/`

**New Metric**:
1. Extend `BaseMetric`: [Evaluation](EVALUATION.md) - "Metrics Architecture"
2. Implement in `opik/evaluation/metrics/`
3. Add tests in `tests/unit/evaluation/metrics/`
4. Export in `__init__.py`

## 📋 Additional Resources

**Cursor Rules** (`.cursor/rules/`):
- `api-design.mdc` - API principles
- `architecture.mdc` - Architecture patterns
- `test-organization.mdc` - Testing guidelines

**External Docs**:
- `../README.md` - User documentation
- `../../apps/opik-documentation/` - Full documentation site

## 🤝 Contributing

**Before contributing**:
1. Read [API and Data Flow](API_AND_DATA_FLOW.md)
2. Read relevant domain doc
3. Look for similar implementations
4. Follow existing patterns
5. Add tests

**Code review checklist**:
- [ ] Follows patterns
- [ ] Has tests
- [ ] Non-blocking (if trace/span operation)
- [ ] Proper error handling
- [ ] Context management (if using `@track`)

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
