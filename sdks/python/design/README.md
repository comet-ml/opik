# Opik Python SDK Design Documentation

Welcome to the Opik Python SDK design documentation! This directory contains comprehensive guides to help newcomers and contributors understand the SDK's architecture, integrations, evaluation framework, and testing strategy.

## 📚 Documentation Structure

### Core Documentation (Read First)

#### 1. [API and Data Flow](API_AND_DATA_FLOW.md) ⭐ **Start Here**
**Comprehensive guide to the SDK's core architecture and data flow.**

**What's Covered**:
- High-level API (`opik.Opik`, `@opik.track`, context management)
- Layered architecture (API → Message Processing → REST → Backend)
- Complete data flow diagrams (trace creation, decorator execution)
- Message processing deep dive (routing, queuing, consumers)
- **Batching system** (detailed: triggers, optimization, performance)
- Observability (logging, error tracking, metrics)
- Performance considerations and best practices

**Read this if you want to**:
- Understand how the SDK works internally
- Learn about the non-blocking architecture
- See detailed data flow diagrams
- Understand batching and optimization
- Troubleshoot performance issues

### Domain-Specific Documentation

#### 2. [Integrations](INTEGRATIONS.md)
**Engineering guide to integration architecture.**

**What's Covered**:
- Three integration patterns (decorator, callback, hybrid) with selection criteria
- Integration catalog grouped by pattern
- Decorator-based deep dive (OpenAI, Anthropic, Bedrock)
- Callback-based deep dive (LangChain, LlamaIndex, DSPy)
- Hybrid integrations (ADK, CrewAI)
- Token usage and cost tracking architecture
- Streaming support (3 patching techniques with implementations)
- Step-by-step guide for building decorator integrations
- Notable implementation details (Bedrock's extensible aggregator, ADK's OpenTelemetry patching)

**Read this if you want to**:
- Understand how integrations are architected
- Build a new integration from scratch
- Learn streaming response handling
- Understand token usage extraction patterns
- See real implementation examples

#### 3. [Evaluation](EVALUATION.md)
**Engineering guide to evaluation framework architecture.**

**What's Covered**:
- Evaluation engine architecture and components
- All 4 evaluation methods (`evaluate()`, `evaluate_prompt()`, `evaluate_experiment()`, `evaluate_threads()`)
- Data flow diagrams with module annotations
- Parallel execution model (ThreadPoolExecutor)
- Metrics architecture (3 types: heuristic, LLM judges, conversation)
- Task lifecycle and context management
- Error handling strategy
- Comparison with tracing architecture

**Read this if you want to**:
- Understand evaluation engine internals
- Build custom metrics
- Understand parallel execution
- See where each step is implemented (module references)

#### 4. [Testing](TESTING.md)
**Complete testing guide and strategy.**

**What's Covered**:
- Test directory structure
- 4 test categories (unit, library integration, e2e, e2e lib integration)
- Testing infrastructure (fake backend, models, fixtures)
- 6 common testing patterns with examples
- How to write and run tests
- Key insights from test analysis

**Read this if you want to**:
- Write tests for new features
- Understand the testing strategy
- Use the fake backend for testing
- Run different test categories

## 🚀 Quick Start Guides

### For First-Time Contributors

**Recommended reading order**:
1. **[API and Data Flow](API_AND_DATA_FLOW.md)** - Understand the foundation (30-45 min)
2. **[Testing](TESTING.md)** - Learn how to validate changes (20-30 min)
3. Choose based on your task:
   - Adding integration? → [Integrations](INTEGRATIONS.md)
   - Working on evaluation? → [Evaluation](EVALUATION.md)

### For Specific Tasks

| Task | Documentation | Key Sections |
|------|---------------|--------------|
| **Understanding tracing** | [API and Data Flow](API_AND_DATA_FLOW.md) | Decorator Data Flow, Context Management |
| **Adding new integration** | [Integrations](INTEGRATIONS.md) | Building New Integrations, Integration Patterns |
| **Creating custom metric** | [Evaluation](EVALUATION.md) | Custom Metrics |
| **Fixing a bug** | [API and Data Flow](API_AND_DATA_FLOW.md) + [Testing](TESTING.md) | Relevant component + Testing Patterns |
| **Improving performance** | [API and Data Flow](API_AND_DATA_FLOW.md) | Batching System, Performance Considerations |
| **Understanding message flow** | [API and Data Flow](API_AND_DATA_FLOW.md) | Message Processing Deep Dive |

## 📖 Documentation Overview

### API and Data Flow (103 KB, ~2,100 lines)

**Priority**: 🔴 **Highest - Read First**

The most comprehensive document covering:
- Complete API reference with examples
- Detailed architecture diagrams
- Step-by-step data flow explanations
- In-depth batching system documentation
- Performance optimization guides

**Key Sections**:
- **High-Level API**: User-facing methods and decorators
- **Data Flow**: Complete trace/span creation flow with state diagrams
- **Message Processing**: Detailed internals of async processing
- **Batching System**: How batching works, flush triggers, optimization
- **Observability**: Logging, error tracking, monitoring

### Integrations (27 KB, ~1,100 lines)

**Priority**: 🟡 **Medium - Task-Specific**

Comprehensive integration guide:
- Pattern explanations with architecture diagrams
- Individual integration documentation
- Building new integrations guide
- Provider-specific features

**Key Sections**:
- **Integration Patterns**: Decorator vs Callback
- **Supported Integrations**: OpenAI, Anthropic, LangChain, etc.
- **Building New Integrations**: Step-by-step template
- **Advanced Topics**: Streaming, distributed tracing, costs

### Evaluation (20 KB, ~900 lines)

**Priority**: 🟡 **Medium - Task-Specific**

Complete evaluation framework:
- Evaluation API documentation
- All built-in metrics explained
- Custom metric patterns
- Best practices

**Key Sections**:
- **Evaluation API**: `evaluate()`, `evaluate_experiment()`
- **Metrics**: Heuristic, LLM-based, and conversation metrics
- **Custom Metrics**: Implementation patterns
- **Advanced Usage**: Parallel execution, sampling

### Testing (28 KB, ~1,060 lines)

**Priority**: 🟢 **High - Essential for Contributors**

Comprehensive testing guide:
- Test organization and structure
- Testing patterns and examples
- Fake backend usage
- Running tests

**Key Sections**:
- **Test Categories**: Unit, library integration, e2e
- **Testing Infrastructure**: Fake backend, models, fixtures
- **Testing Patterns**: 6 common patterns with examples
- **Writing Tests**: How to add tests for new features

## 🎯 Common Scenarios

### Scenario 1: Understanding How `@opik.track` Works

**Documents to read**:
1. [API and Data Flow](API_AND_DATA_FLOW.md) - Section: "Decorator Data Flow"
   - Complete execution flow with state diagrams
   - Context management explained
   - Nested decorator behavior

2. [Testing](TESTING.md) - Section: "Pattern 1: Testing Decorator Behavior"
   - Examples of decorator tests
   - How to verify decorator behavior

### Scenario 2: Adding OpenAI Responses API Support

**Documents to read**:
1. [Integrations](INTEGRATIONS.md) - Section: "OpenAI"
   - Existing OpenAI integration patterns
   - Decorator-based integration approach

2. [Testing](TESTING.md) - Section: "Pattern 2: Testing Integration Tracking"
   - How to test integration changes
   - Fake backend usage

3. [API and Data Flow](API_AND_DATA_FLOW.md) - Section: "Message Processing"
   - Understanding message flow
   - How data reaches the backend

### Scenario 3: Creating Custom Evaluation Metric

**Documents to read**:
1. [Evaluation](EVALUATION.md) - Section: "Custom Metrics"
   - Implementation patterns
   - LLM-based metric examples
   - Error handling

2. [Testing](TESTING.md) - Section: "Pattern 6: Testing Metrics"
   - How to test metrics
   - Validation patterns

### Scenario 4: Debugging Performance Issues

**Documents to read**:
1. [API and Data Flow](API_AND_DATA_FLOW.md) - Sections:
   - "Batching System" - Understanding optimization
   - "Performance Considerations" - Best practices
   - "Observability" - Monitoring and logging

2. [Testing](TESTING.md) - Section: "Key Insights from Tests"
   - Async processing patterns
   - Flush behavior

### Scenario 5: Building LangGraph Integration

**Documents to read**:
1. [Integrations](INTEGRATIONS.md) - Sections:
   - "Pattern 2: Callback-Based Integration" - LangChain uses this
   - "LangGraph" - Existing integration
   - "Building New Integrations" - If extending functionality

2. [Testing](TESTING.md) - Section: "Library Integration Tests"
   - How to structure integration tests
   - Fake backend usage

3. [API and Data Flow](API_AND_DATA_FLOW.md) - Section: "Context Management"
   - How context works for nested operations

## 🔍 Key Concepts Quick Reference

### Architecture Layers

```
User Code
    ↓
API Layer (opik.Opik, @track)
    ↓
Message Processing (Streamer, Queue, Consumers)
    ↓
REST API (OpikApi)
    ↓
Backend
```

### Main Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `Opik` | `api_objects/opik_client.py` | Main entry point, factory |
| `@track` | `decorator/tracker.py` | Automatic tracing decorator |
| `Streamer` | `message_processing/streamer.py` | Message routing |
| `MessageQueue` | `message_processing/message_queue.py` | Thread-safe FIFO queue |
| `OpikMessageProcessor` | `message_processing/message_processors.py` | Message handlers |
| `OpikContextStorage` | `context_storage.py` | Context management |

### Data Flow Summary

1. **User calls API** (`client.trace()` or `@track`)
2. **API creates message** (`CreateTraceMessage`)
3. **Streamer routes message** (batch, upload, or queue)
4. **Queue consumer processes** (background thread)
5. **Message processor calls REST API**
6. **Backend stores data**

### Testing Categories

| Category | Backend | Speed | Use Case |
|----------|---------|-------|----------|
| Unit | Fake | ⚡ Fast | Logic, edge cases |
| Library Integration | Fake | ⚡ Fast | Integration logic |
| E2E | Real | 🐌 Slow | Core features |
| E2E Lib Integration | Real | 🐌 Slow | Full integration |

## 🛠️ Development Workflow

### Making Changes

1. **Understand**: Read relevant documentation sections
2. **Implement**: Follow existing patterns in code
3. **Test**: Add tests following patterns in [Testing](TESTING.md)
4. **Verify**: Run tests and check behavior
5. **Document**: Update docs if adding major features

### Adding Features

**Example: New Metric**

1. Read [Evaluation](EVALUATION.md) - "Custom Metrics"
2. Implement `BaseMetric` subclass
3. Add tests in `tests/unit/evaluation/metrics/`
4. Export in `opik/evaluation/metrics/__init__.py`
5. Update [Evaluation](EVALUATION.md) with example

**Example: New Integration**

1. Read [Integrations](INTEGRATIONS.md) - "Building New Integrations"
2. Choose pattern (decorator or callback)
3. Implement in `opik/integrations/mylib/`
4. Add tests in `tests/library_integration/mylib/`
5. Update [Integrations](INTEGRATIONS.md) with documentation

## 📋 Additional Resources

### Internal Documentation
- [API Design Rules](../.cursor/rules/api-design.mdc) - API design principles
- [Architecture Rules](../.cursor/rules/architecture.mdc) - Architecture patterns
- [Design Principles Rules](../.cursor/rules/design-principles.mdc) - SOLID principles
- [Testing Rules](../.cursor/rules/test-organization.mdc) - Testing guidelines

### External Documentation
- [Main README](../README.md) - User-facing documentation
- [Python SDK Docs](../../apps/opik-documentation/python-sdk-docs/) - API reference

## 🤝 Contributing

### Before Contributing

1. ✅ Read [API and Data Flow](API_AND_DATA_FLOW.md) to understand architecture
2. ✅ Read relevant domain docs ([Integrations](INTEGRATIONS.md), [Evaluation](EVALUATION.md), [Testing](TESTING.md))
3. ✅ Look for similar existing implementations
4. ✅ Follow patterns in existing code
5. ✅ Add comprehensive tests

### Code Review Checklist

- [ ] Follows existing patterns
- [ ] Has tests (unit and/or integration)
- [ ] Updates documentation if needed
- [ ] Non-blocking (doesn't block user code)
- [ ] Proper error handling
- [ ] Logging for debugging
- [ ] Context management (if using `@track`)

## 💡 Tips for Navigating Documentation

### Finding Information

1. **Use search** (Cmd/Ctrl + F) for specific terms
2. **Start with README** (this file) for navigation
3. **Check Quick Reference** sections for quick answers
4. **Read sequentially** for deep understanding

### Documentation Conventions

- **Code blocks**: Executable examples
- **Diagrams**: ASCII art for data flow
- **Tables**: Quick reference information
- **Sections**: Logical organization by topic
- **Cross-references**: Links to related sections

### Getting Help

If documentation doesn't answer your question:

1. Check if similar code exists in the codebase
2. Look at tests for examples
3. Check `.cursor/rules/` for specific guidelines
4. Ask in development channel

## 📊 Documentation Statistics

| Document | Lines | Read Time | Priority |
|----------|-------|-----------|----------|
| API and Data Flow | ~1,550 | 45-60 min | 🔴 Highest |
| Integrations | ~2,300 | 40-55 min | 🟡 Medium |
| Evaluation | ~1,260 | 25-35 min | 🟡 Medium |
| Testing | ~1,060 | 20-30 min | 🟢 High |
| **Total** | **~6,170** | **~2.5 hours** | |

## 🔄 Maintaining This Documentation

### When to Update

Update documentation when:
- ✏️ Major architectural changes
- ✏️ New patterns introduced
- ✏️ New integrations added
- ✏️ Evaluation framework changes
- ✏️ Testing infrastructure changes
- ✏️ Performance optimizations

### Documentation Quality Standards

Keep documentation:
- ✅ **Accurate**: Reflects current codebase
- ✅ **Complete**: Covers major components
- ✅ **Clear**: Easy for newcomers to understand
- ✅ **Practical**: Includes real examples
- ✅ **Maintained**: Updated with changes

---

**Last Updated**: 2024-01-15

**Questions or Issues?** Please open an issue or reach out to the SDK team.
