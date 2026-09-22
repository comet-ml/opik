# Integration Documentation Templates

This directory contains templates for creating integration documentation for Opik.

## 📋 Integration Type Decision Matrix

Use this matrix to determine which template to use:

| Integration Type              | Requirements                                                                                                                            | Template to Use                   | Examples                                                            |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------- | ------------------------------------------------------------------- |
| **Code Integration**          | • Users install Opik Python SDK<br>• Users modify their code<br>• Uses `track_*()` wrapper functions<br>• Direct Python integration     | `integration_template_code.md`    | LangChain, CrewAI, DSPy, Haystack                                   |
| **OpenAI-Based Integration**  | • Uses OpenAI-compatible API<br>• Users install Opik Python SDK<br>• Uses `track_openai()` wrapper<br>• Compatible with OpenAI SDK      | `integration_template_openai.md`  | BytePlus, OpenRouter, Any OpenAI-compatible API                     |
| **LiteLLM Integration**       | • LLM provider supported by LiteLLM<br>• Uses OpikLogger callback<br>• Unified LiteLLM interface<br>• API key configuration required    | `integration_template_litellm.md` | OpenAI, Anthropic, Groq, Fireworks AI, Cohere, Mistral AI, xAI Grok |
| **OpenTelemetry Integration** | • Users configure OTEL endpoints<br>• No code changes required<br>• Configuration via env vars<br>• Works through OTEL instrumentations | `integration_template_otel.md`    | Ruby SDK, Pydantic AI (via Logfire), Direct OTEL Python             |

## 📁 Available Templates

### `integration_template_code.md`

**Use for**: Code integrations that require users to install Opik Python SDK and use `track_*()` wrapper functions.

**Examples**: OpenAI, Anthropic, LangChain, CrewAI, DSPy, Haystack, etc.

**Pattern**: Users modify their code to import and wrap clients with Opik tracking.

### `integration_template_openai.md`

**Use for**: OpenAI-based integrations that use OpenAI-compatible APIs.

**Examples**: BytePlus, OpenRouter, Any OpenAI-compatible API.

**Pattern**: Users use `track_openai()` wrapper with OpenAI SDK.

### `integration_template_litellm.md`

**Use for**: LiteLLM integrations that use OpikLogger callback.

**Examples**: OpenAI, Anthropic, Groq, Fireworks AI, Cohere, Mistral AI, xAI Grok.

**Pattern**: Users configure LiteLLM with OpikLogger callback.

### `integration_template_otel.md`

**Use for**: OpenTelemetry integrations that only require configuration changes.

**Examples**: Ruby SDK, Pydantic AI (via Logfire), Direct OTEL Python.

**Pattern**: Users configure OTEL endpoints and headers, no code changes needed.

## 🎯 How to Use These Templates

1. **Use the decision matrix above** to determine which template fits your integration
2. **Copy the appropriate template** to the correct documentation location:
   - All integrations: `fern/docs-v2/integrations/[integration_name].mdx`
3. **Replace all placeholder text** with actual values
4. **Test all code examples** in a fresh environment
5. **Add realistic examples** - avoid "hello world" scenarios
6. **Include screenshots** of traces in Opik UI
7. **Update integration tables** in main README files

## 📸 Screenshot File Placement

**⚠️ CRITICAL: Screenshot File Locations**

Screenshots must be placed in the correct directory structure:

**File System Location (Git root relative):**

- `apps/opik-documentation/documentation/fern/img/tracing/[integration_name]_integration.png`

**Documentation Reference Path:**

- `/img/tracing/[integration_name]_integration.png`

**Examples:**

- Fireworks AI: `fern/img/tracing/fireworks_ai_integration.png`
- OpenAI: `fern/img/tracing/openai_integration.png`
- LangChain: `fern/img/tracing/langchain_integration.png`

**⚠️ Common Mistakes:**

- ❌ Placing screenshots in `static/img/tracing/` (incorrect location)
- ❌ Using absolute paths in documentation
- ❌ Inconsistent naming conventions

## 📋 Quick Reference

### Code Integration Placeholders

- `[INTEGRATION_NAME]` → "OpenAI"
- `[integration_name]` → "openai"
- `[integration_module]` → "openai"
- `[integration_package]` → "openai"
- `[ClientClass]` → "OpenAI"
- `[INTEGRATION_API_KEY_NAME]` → "OPENAI_API_KEY"

### OpenAI-Based Integration Placeholders

- `[INTEGRATION_NAME]` → "BytePlus"
- `[INTEGRATION_WEBSITE_URL]` → "https://www.byteplus.com/"
- `[INTEGRATION_DESCRIPTION]` → "ByteDance's AI-native enterprise platform"
- `[SPECIFIC_DESCRIPTION]` → "OpenAI-compatible API endpoints"
- `[INTEGRATION_BASE_URL]` → "https://ark.ap-southeast.bytepluses.com/api/v3"
- `[INTEGRATION_API_KEY_NAME]` → "BYTEPLUS_API_KEY"
- `[EXAMPLE_MODEL_NAME]` → "kimi-k2-250711"

### OTEL Integration Placeholders

- `[FRAMEWORK_NAME]` → "PydanticAI"
- `[framework_name]` → "pydantic-ai"
- `[framework_otel_packages]` → "pydantic-ai[logfire]"

## 📖 Complete Guidelines

For detailed guidelines on integration documentation, see:
**`.agents/rules/integration-documentation.mdc`**

This includes:

- Quality checklist
- Integration-specific guidance
- Publication process
- Maintenance guidelines
