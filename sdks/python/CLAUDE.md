# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Architecture Overview

The Opik Python SDK follows a **layered architecture** with clear separation of concerns:

### Core Layers
- **API Objects** (`src/opik/api_objects/`): High-level client interfaces (`Opik`, `Trace`, `Span`, `Dataset`, `Experiment`, `Prompt`)
- **Message Processing** (`src/opik/message_processing/`): Queue-based background operations for non-blocking trace/span creation
- **REST API** (`src/opik/rest_api/`): Auto-generated OpenAPI client (do not edit manually, excluded from linting)
- **Integrations** (`src/opik/integrations/`): Third-party library support (OpenAI, Anthropic, LangChain, LlamaIndex, etc.)

### Key Patterns
- **Non-blocking Operations**: Traces/spans created via background message processing
- **Context Management**: Thread-safe context using `opik_context.py` and `context_storage.py`
- **Decorator Pattern**: `@opik.track` for automatic function tracing
- **Integration Consistency**: Standardized patterns across different library integrations

The main entry point is `opik.Opik()` class in `api_objects/opik_client.py`. Background processing happens via `message_processing/streamer.py`.

## Development Commands

### Environment Setup
```bash
# Install in development mode
pip install .

# Install test dependencies
pip install -r tests/test_requirements.txt
pip install -r tests/unit/test_requirements.txt
```

### Linting and Formatting
```bash
# Install and setup pre-commit (required)
pip install pre-commit
pre-commit install

# Run all linting checks
pre-commit run --all-files --show-diff-on-failure
```

### Testing
```bash
# Unit tests with coverage
python -m pytest --cov=src/opik -vv tests/unit/

# Integration tests (requires API keys)
python -m pytest tests/library_integration/

# End-to-end tests
python -m pytest tests/e2e/

# Single test file
python -m pytest tests/unit/test_specific_file.py -v
```

## Code Standards

### Import Strategy (from architecture guidelines)
- Import modules, not names: `import opik.config` not `from opik.config import OPIK_BASE_URL`
- Exception: typing imports (`from typing import Optional`)
- Avoid generic utils.py files - use specific module names

### Access Control
- Strict adherence to protected (`_`) and private (`__`) conventions
- Public API defined in `src/opik/__init__.py`
- Internal APIs should not be accessed directly by users

### Architecture Principles
- **Single Responsibility**: Each module has one clear purpose
- **Dependency Direction**: Higher layers depend on lower layers, not vice versa
- **Context Isolation**: Use proper context management for concurrent operations
- **Error Handling**: Comprehensive error handling with optional Sentry integration

### Testing Philosophy
- Test public APIs, not internal implementation details
- Use `fake_backend` for integration tests to avoid external dependencies
- Component tests for integration testing
- Comprehensive coverage of edge cases and error conditions

## Configuration Files
- **setup.py**: Main package configuration with dependencies
- **.ruff.toml**: Comprehensive linting configuration (replaces black, flake8, isort)
- **.pre-commit-config.yaml**: Pre-commit hooks
- **pyproject.toml**: Minimal mypy configuration
- **.cursor/rules/**: Extensive development guidelines and architectural patterns

## Important Notes
- `rest_api/` is auto-generated from OpenAPI specs - never edit manually
- Python 3.8+ supported with comprehensive CI testing
- Background message processing ensures non-blocking operations
- Context management is critical for proper trace/span relationships
- Integration patterns should be consistent across different libraries
