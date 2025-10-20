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
- Three integration patterns (method patching, callback, hybrid) with selection criteria
- Integration catalog: 12+ integrations grouped by pattern
- Method patching integrations: OpenAI, Anthropic, Bedrock (with extensible aggregator), GenAI, AISuite
- Callback integrations: LangChain (external context support), LlamaIndex, DSPy (isolated context), Haystack
- Hybrid integrations: ADK (OpenTelemetry interception), CrewAI (LiteLLM delegation)
- Streaming strategies: 3 patching techniques explained
- Token usage and cost tracking architecture (registry pattern)
- Key implementation details integrated into each integration section

**Read this if you want to**:
- Understand integration architecture patterns
- See how different frameworks are integrated
- Learn streaming response handling strategies
- Understand why hybrid patterns are needed
- Reference actual implementations (file locations provided)

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
- Test directory structure and organization
- 5 test categories (unit, library integration, e2e, e2e lib integration, smoke)
- Testing infrastructure (fake backend, TraceModel/SpanModel, flexible matchers)
- 6 testing patterns with real examples using TraceModel/SpanModel
- Fixtures for each test category
- How to write and run tests
- Best practices for SDK testing

**Read this if you want to**:
- Write tests using fake backend and test models
- Understand testing patterns (decorator, integration, E2E, errors, streaming, metrics)
- Use flexible matchers (ANY, ANY_BUT_NONE, ANY_STRING)
- Set up integration test requirements

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
| **Adding new integration** | [Integrations](INTEGRATIONS.md) | Integration Patterns, existing integrations as reference |
| **Creating custom metric** | [Evaluation](EVALUATION.md) | Metrics Architecture, implementation examples |
| **Fixing a bug** | [API and Data Flow](API_AND_DATA_FLOW.md) + [Testing](TESTING.md) | Relevant component + Testing Patterns |
| **Improving performance** | [API and Data Flow](API_AND_DATA_FLOW.md) | Batching System, Performance Considerations |
| **Understanding message flow** | [API and Data Flow](API_AND_DATA_FLOW.md) | Message Processing Deep Dive |

## 📖 Documentation Overview

### API and Data Flow (~1,550 lines)

**Priority**: 🔴 **Highest - Read First**

Most comprehensive document covering core SDK architecture:
- High-level API (Opik client, decorators, context management)
- 3-layer architecture with clear separation
- **Sync vs Async operations**: Which operations use background processing
- Complete data flow diagrams with step-by-step execution
- Batching system deep dive (5 flush triggers, which messages batch)
- Message processing internals
- Observability and performance

**Key Sections**:
- **High-Level API**: `opik.Opik`, `@opik.track`, context management
- **Core Architecture**: 3-layer architecture, key components
- **Synchronous vs Asynchronous Operations**: Critical distinction - trace/span/feedback are async, dataset/experiment/search are sync
- **Data Flow**: Trace creation, decorator execution, nested functions
- **Message Processing Deep Dive**: Routing, queuing, consumers, handlers
- **Batching System**: Why batching, which messages support it, flush triggers, performance
- **Observability**: Logging, error tracking, health checks

### Integrations (~550 lines)

**Priority**: 🟡 **Medium - Task-Specific**

Engineering guide to integration architecture:
- Pattern selection (method patching, callback, hybrid)
- 12+ integrations with architecture details
- Key implementation highlights per integration
- Streaming strategies
- Token usage and cost tracking

**Key Sections**:
- **Integration Patterns**: Pattern selection criteria and comparison
- **Method Patching**: OpenAI, Anthropic, Bedrock (extensible aggregator)
- **Callback**: LangChain (external context support), LlamaIndex, DSPy, Haystack
- **Hybrid**: ADK (OpenTelemetry), CrewAI (LiteLLM delegation)
- **Streaming Strategies**: 3 patching techniques
- **Token Usage**: Registry pattern, provider-specific builders

### Evaluation (~1,260 lines)

**Priority**: 🟡 **Medium - Task-Specific**

Engineering guide to evaluation architecture:
- Evaluation engine internals
- All 4 evaluation methods explained
- Data flow with module annotations
- Parallel execution model
- Metrics architecture

**Key Sections**:
- **Evaluation Engine**: EvaluationEngine architecture, task execution
- **Evaluation Methods**: `evaluate()`, `evaluate_prompt()`, `evaluate_experiment()`, `evaluate_threads()`
- **Data Flow**: Complete flows with module/class annotations
- **Metrics Architecture**: 3 types (heuristic, LLM judges, conversation)
- **Parallel Execution**: ThreadPoolExecutor design
- **Error Handling**: Strategy and implementation

### Testing (~1,036 lines)

**Priority**: 🟢 **High - Essential for Contributors**

Complete testing guide and strategy:
- Test organization and categories
- Testing infrastructure (fake backend, TraceModel/SpanModel)
- 6 testing patterns with examples
- Fixtures for each category
- Best practices

**Key Sections**:
- **Test Categories**: 5 categories (unit, library integration, e2e, e2e lib, smoke)
- **Testing Infrastructure**: Fake backend, test models, flexible matchers, fixtures
- **Testing Patterns**: 6 patterns using TraceModel/SpanModel + assert_equal()
- **Writing Tests**: Conventions and fake backend usage
- **Running Tests**: Commands and environment setup

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

### Scenario 2: Adding Support for New LLM Provider

**Documents to read**:
1. [Integrations](INTEGRATIONS.md) - Section: "OpenAI Integration" or "Anthropic Integration"
   - Existing method patching integration patterns
   - File structure and implementation approach

2. [Integrations](INTEGRATIONS.md) - Section: "Token Usage and Cost Tracking"
   - How to add usage builder for new provider
   - Registry pattern for provider builders

3. [Testing](TESTING.md) - Section: "Pattern 2: Testing Integration Tracking"
   - How to test integration with fake backend
   - Using TraceModel/SpanModel for assertions

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
   - "Batching System" - Understanding optimization and flush triggers
   - "Performance Considerations" - Best practices and troubleshooting
   - "Observability" - Logging, error tracking, monitoring

### Scenario 5: Understanding Callback Integration Pattern

**Documents to read**:
1. [Integrations](INTEGRATIONS.md) - Section: "LangChain Integration"
   - Callback pattern with BaseTracer
   - External context support
   - Provider-specific usage extraction

2. [Integrations](INTEGRATIONS.md) - Section: "Callback Reliability Issues"
   - Why callbacks may need augmentation
   - Context isolation challenges

3. [Testing](TESTING.md) - Section: "Library Integration Tests"
   - How to test callback integrations
   - Using fake backend with library calls

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

1. Read [Integrations](INTEGRATIONS.md) - "Integration Patterns" to choose approach
2. Reference existing integration (e.g., OpenAI for method patching, LlamaIndex for callback)
3. Implement in `opik/integrations/mylib/` following pattern from reference
4. Add usage builder in `opik/llm_usage/` and register in factory
5. Add tests in `tests/library_integration/mylib/`
6. Add entry to catalog in [Integrations](INTEGRATIONS.md)

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
| Integrations | ~550 | 15-25 min | 🟡 Medium |
| Evaluation | ~1,260 | 25-35 min | 🟡 Medium |
| Testing | ~1,036 | 20-30 min | 🟢 High |
| **Total** | **~4,400** | **~2 hours** | |

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

**Last Updated**: 2025-01-20

**Questions or Issues?** Please open an issue or reach out to the SDK team.
