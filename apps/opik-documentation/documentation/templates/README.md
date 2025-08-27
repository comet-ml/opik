# Integration Documentation Templates

This directory contains templates for creating integration documentation for Opik.

## 📁 Available Templates

### `integration_template_code.md`
**Use for**: Code integrations that require users to install Opik Python SDK and use `track_*()` wrapper functions.

**Examples**: OpenAI, Anthropic, LangChain, CrewAI, DSPy, Haystack, etc.

**Pattern**: Users modify their code to import and wrap clients with Opik tracking.

### `integration_template_otel.md`
**Use for**: OpenTelemetry integrations that only require configuration changes.

**Examples**: Ruby SDK, Pydantic AI (via Logfire), Direct OTEL Python, etc.

**Pattern**: Users configure OTEL endpoints and headers, no code changes needed.

## 🎯 How to Use These Templates

1. **Copy the appropriate template** to the correct documentation location:
   - Code integrations: `fern/docs/cookbook/[integration_name].mdx`
   - OTEL integrations: `fern/docs/tracing/opentelemetry/[framework_name].mdx`

2. **Replace all placeholder text** (see guidelines in `.cursor/rules/integration-documentation.mdc`)

3. **Test all code examples** in a fresh environment

4. **Add realistic examples** - avoid "hello world" scenarios

5. **Include screenshots** of traces in Opik UI

6. **Update integration tables** in main README files

## 📋 Quick Reference

### Code Integration Placeholders
- `[INTEGRATION_NAME]` → "OpenAI"
- `[integration_name]` → "openai"
- `[integration_module]` → "openai"
- `[integration_package]` → "openai"
- `[ClientClass]` → "OpenAI"
- `[INTEGRATION_API_KEY_NAME]` → "OPENAI_API_KEY"

### OTEL Integration Placeholders
- `[FRAMEWORK_NAME]` → "PydanticAI"
- `[framework_name]` → "pydantic-ai"
- `[framework_otel_packages]` → "pydantic-ai[logfire]"

## 📖 Complete Guidelines

For detailed guidelines on integration documentation, see:
**`.cursor/rules/integration-documentation.mdc`**

This includes:
- Decision matrix for choosing templates
- Quality checklist
- Integration-specific guidance
- Publication process
- Maintenance guidelines 