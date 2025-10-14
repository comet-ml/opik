# Opik Python SDK

[![PyPI version](https://img.shields.io/pypi/v/opik.svg)](https://pypi.org/project/opik/)
[![Python versions](https://img.shields.io/pypi/pyversions/opik.svg)](https://pypi.org/project/opik/)
[![Downloads](https://static.pepy.tech/badge/opik)](https://pepy.tech/project/opik)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)

The Opik Python SDK allows you to integrate your Python applications with the Opik platform, enabling comprehensive tracing, evaluation, and monitoring of your LLM systems. Opik helps you build, evaluate, and optimize LLM systems that run better, faster, and cheaper.

Opik is an open-source LLM evaluation platform by [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_readme&utm_campaign=opik). For more information about the broader Opik ecosystem, visit our main [GitHub repository](https://github.com/comet-ml/opik), [Website](https://www.comet.com/site/products/opik/), or [Documentation](https://www.comet.com/docs/opik/).

## Quickstart

Get started quickly with Opik using our interactive notebook:

<a href="https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb">
  <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open Quickstart In Colab"/>
</a>

## Installation

Install the `opik` package using pip or uv:

```bash
# using pip
pip install opik

# using uv (faster)
uv pip install opik
```

## Configuration

Configure the Python SDK by running the `opik configure` command. This will prompt you for your Opik server address (for self-hosted instances) or your API key and workspace (for Comet.com):

```bash
opik configure
```

You can also configure the SDK programmatically in your Python code:
```python
import opik

# For Comet.com Cloud
opik.configure(
    api_key="YOUR_API_KEY",
    workspace="YOUR_WORKSPACE", # Usually found in your Comet URL: https://www.comet.com/YOUR_WORKSPACE/...
    project_name="optional-project-name" # Optional: set a default project for traces
)

# For self-hosted Opik instances
# opik.configure(use_local=True, project_name="optional-project-name")
```
Refer to the [Python SDK documentation](https://www.comet.com/docs/opik/python-sdk-reference/) for more configuration options.

### Dynamic Tracing Control

Control tracing behavior at runtime without code changes:

```python
import opik

# Disable tracing globally
opik.set_tracing_active(False)

# Check current state
print(opik.is_tracing_active())  # False

# Re-enable tracing
opik.set_tracing_active(True)

# Reset to configuration default
opik.reset_tracing_to_config_default()
```

This is useful for:
- Performance optimization in high-throughput systems
- Conditional tracing based on user type or request parameters
- Debugging and troubleshooting without redeployment
- Implementing sampling strategies
- Calls already in progress when you disable tracing still finish logging.

See `examples/dynamic_tracing_cookbook.py` for comprehensive usage patterns.

## Basic Usage: Tracing

The easiest way to log traces is to use the `@opik.track` decorator:

```python
import opik

# Ensure Opik is configured (see Configuration section above)
# opik.configure(...)

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM call or business logic here
    # For example:
    # response = openai.ChatCompletion.create(...)
    response = f"Echoing: {user_question}"

    # You can add metadata to your trace
    opik.set_tags(["example", "basic-usage"])
    opik.log_metadata({"question_length": len(user_question)})

    return response

my_llm_function("Hello, Opik!")
```
Traces will appear in your configured Opik project. Opik also offers many direct [integrations](https://www.comet.com/docs/opik/integrations/overview/) for popular LLM frameworks.

## Integrations

Opik provides seamless integrations with popular LLM frameworks and libraries. Simply import the integration and start tracing automatically:

### OpenAI
```python
import opik
from opik.integrations.openai import opik_openai

# Automatically traces all OpenAI calls
response = openai.ChatCompletion.create(
    model="gpt-3.5-turbo",
    messages=[{"role": "user", "content": "Hello!"}]
)
```

### Anthropic
```python
import opik
from opik.integrations.anthropic import opik_anthropic

# Automatically traces all Anthropic calls
response = anthropic.messages.create(
    model="claude-3-sonnet-20240229",
    messages=[{"role": "user", "content": "Hello!"}]
)
```

### LangChain
```python
import opik
from opik.integrations.langchain import opik_langchain

# Automatically traces all LangChain operations
llm = ChatOpenAI(model="gpt-3.5-turbo")
response = llm.invoke("Hello!")
```

### LiteLLM
```python
import opik
from opik.integrations.litellm import opik_litellm

# Automatically traces all LiteLLM calls
response = litellm.completion(
    model="gpt-3.5-turbo",
    messages=[{"role": "user", "content": "Hello!"}]
)
```

### Other Supported Integrations
- **AWS Bedrock**: Native support for Amazon Bedrock models
- **Google Generative AI**: Integration with Google's Gemini models
- **CrewAI**: Multi-agent framework integration
- **DSPy**: Framework for building LLM applications
- **Guardrails**: AI safety and reliability framework
- **Haystack**: End-to-end NLP framework
- **LlamaIndex**: Data framework for LLM applications
- **SageMaker**: AWS SageMaker integration

For detailed integration documentation, visit our [Integrations Guide](https://www.comet.com/docs/opik/integrations/overview/).

## Examples

Explore our comprehensive examples to get started with Opik:

### Basic Usage
- [`decorators.py`](examples/decorators.py) - Basic decorator usage and tracing
- [`dynamic_tracing_example.py`](examples/dynamic_tracing_example.py) - Dynamic tracing control
- [`distributed_tracing_example.py`](examples/distributed_tracing_example.py) - Distributed tracing patterns

### Integrations
- [`openai_integration_example.py`](examples/openai_integration_example.py) - OpenAI integration
- [`langchain_integration_example.py`](examples/langchain_integration_example.py) - LangChain integration
- [`manual_chain_building.py`](examples/manual_chain_building.py) - Manual chain building

### Evaluation
- [`evaluation_example.py`](examples/evaluation_example.py) - Basic evaluation setup
- [`evaluate_prompt.py`](examples/evaluate_prompt.py) - Prompt evaluation
- [`evaluate_existing_experiment.py`](examples/evaluate_existing_experiment.py) - Experiment evaluation
- [`trajectory_accuracy_evaluation.py`](examples/trajectory_accuracy_evaluation.py) - Trajectory accuracy
- [`trajectory_accuracy_judge_example.py`](examples/trajectory_accuracy_judge_example.py) - Judge-based evaluation

### Data Management
- [`cli_download_upload_example.py`](examples/cli_download_upload_example.py) - CLI export/import
- [`evaluation_rules_download_upload_example.py`](examples/evaluation_rules_download_upload_example.py) - Evaluation rules management

### Advanced Features
- [`feedback_scores_example.py`](examples/feedback_scores_example.py) - Feedback scoring
- [`metrics.py`](examples/metrics.py) - Custom metrics
- [`search_traces_and_spans.py`](examples/search_traces_and_spans.py) - Trace searching
- [`threaded_decorators.py`](examples/threaded_decorators.py) - Threading support

### Data Generation
- [`demo_data_generator.py`](examples/demo_data_generator.py) - Generate demo data
- [`demo_data.py`](examples/demo_data.py) - Demo data examples

## CLI Commands

### Configure Command

Configure the Opik Python SDK for your environment:

```bash
# Interactive configuration (recommended)
opik configure

# Configure for local Opik deployment
opik configure --use-local

# Auto-approve all prompts (useful for CI/CD)
opik configure --yes
```

**Options:**
- `--use-local, --use_local`: Configure for local Opik deployments (default: false)
- `-y, --yes`: Automatically answer 'yes' to all prompts (useful for automated setups)

The configure command will:
- Create a configuration file for the Opik Python SDK
- Prompt for your Opik server address (self-hosted) or API key and workspace (Comet.com)
- Overwrite existing configuration if it already exists
- Support both cloud and self-hosted deployments

### Healthcheck Command

Perform a comprehensive health check of your Opik setup:

```bash
# Basic health check
opik healthcheck

# Include installed packages information
opik healthcheck --show-installed-packages
```

**Options:**
- `--show-installed-packages`: Print the list of installed packages to the console (default: true)

The healthcheck command will:
- Validate your Opik configuration
- Verify library installations and dependencies
- Check the availability of the backend workspace
- Print diagnostic information to help with troubleshooting
- Show installed packages and their versions

### Proxy Command

Run a proxy server to integrate local LLM servers with Opik:

```bash
# Proxy for Ollama (default: http://localhost:11434)
opik proxy --ollama

# Proxy for Ollama with custom host
opik proxy --ollama --ollama-host http://localhost:8080

# Proxy for LM Studio (default: http://localhost:1234)
opik proxy --lm-studio

# Proxy for LM Studio with custom host
opik proxy --lm-studio --lm-studio-host http://localhost:5678

# Custom host and port
opik proxy --ollama --host 0.0.0.0 --port 8000
```

**Options:**
- `--ollama`: Run as a proxy server for Ollama
- `--ollama-host`: Ollama server URL (default: http://localhost:11434)
- `--lm-studio`: Run as a proxy server for LM Studio
- `--lm-studio-host`: LM Studio server URL (default: http://localhost:1234)
- `--host`: Host to bind to (default: localhost)
- `--port`: Port to bind to (default: 7860)

**Requirements:**
- Install proxy dependencies: `pip install opik[proxy]`
- You must specify either `--ollama` or `--lm-studio` (but not both)

The proxy server will:
- Forward requests from your application to the local LLM server
- Automatically trace and log all interactions with Opik
- Provide a unified interface for local LLM development
- Support both Ollama and LM Studio backends

## Additional CLI Commands

The Opik Python SDK has two commands to help you quickly
export and import your project's data, making it really easy to sync,
back up, share, or analyze your data in third-party tools and
environments.

### Export Command

Export data from an Opik workspace or workspace/project to local files:

```bash
# Export from a specific project
opik export WORKSPACE/PROJECT_NAME

# Export from all projects in a workspace
opik export WORKSPACE

# Export all data types from a specific project
opik export WORKSPACE/PROJECT_NAME --all

# Export all data types from all projects in a workspace
opik export WORKSPACE --all

# Export specific data types from a project
opik export WORKSPACE/PROJECT_NAME --include traces datasets prompts

# Export specific data types from all projects in a workspace
opik export WORKSPACE --include traces datasets prompts

# Export all except experiments from a project
opik export WORKSPACE/PROJECT_NAME --all --exclude experiments

# Export with custom path and filters
opik export WORKSPACE/PROJECT_NAME --path ./my-data --filter 'name contains "test"' --max-results 100

# Export only items with names starting with "test"
opik export WORKSPACE --name "^test"

# Export only datasets containing "evaluation" from all projects
opik export WORKSPACE --include datasets --name ".*evaluation.*"

# Export with name filtering and custom path
opik export WORKSPACE/PROJECT_NAME --name ".*prod.*" --path ./production-data
```

**Options:**
- `--all`: Include all data types (traces, datasets, experiments, prompts, threads)
- `--include`: Data types to include (can be specified multiple times)
- `--exclude`: Data types to exclude (can be specified multiple times)
- `--path, -p`: Directory to save exported data (defaults to current directory)
- `--max-results`: Maximum number of items to export per data type (default: 1000)
- `--filter`: Filter string using Opik Query Language (OQL)
- `--name`: Filter items by name using Python regex patterns (matches trace names, dataset names, experiment names, or prompt names)

**Supported Data Types:**
- `traces`: Execution traces with spans and timing information (project-specific)
- `datasets`: Evaluation datasets with input/output pairs (workspace-level)
- `experiments`: Evaluation experiments and their results (workspace-level)
- `prompts`: Prompt templates and their versions (workspace-level)

**Note:** Thread metadata is automatically derived from traces with the same `thread_id`, so threads don't need to be exported or imported separately.

### Import Command

Import data from local files to an Opik workspace or workspace/project:

```bash
# Import to a specific project
opik import ./my-data WORKSPACE/PROJECT_NAME

# Import to all projects in a workspace
opik import ./my-data WORKSPACE

# Import all data types to a specific project
opik import ./my-data WORKSPACE/PROJECT_NAME --all

# Import all data types to all projects in a workspace
opik import ./my-data WORKSPACE --all

# Import specific data types to a project
opik import ./my-data WORKSPACE/PROJECT_NAME --include traces datasets

# Import specific data types to all projects in a workspace
opik import ./my-data WORKSPACE --include traces datasets

# Import with dry run to preview
opik import ./my-data WORKSPACE/PROJECT_NAME --all --dry-run

# Import only items with names matching a pattern
opik import ./my-data WORKSPACE --name ".*test.*"

# Import with name filtering and dry run
opik import ./my-data WORKSPACE/PROJECT_NAME --all --name "^prod" --dry-run

# Import from a specific directory
opik import ./my-data WORKSPACE/PROJECT_NAME

# Import from a specific directory with all data types
opik import ./my-data WORKSPACE --all
```

**Options:**
- `--all`: Include all data types (traces, datasets, experiments, prompts, threads)
- `--include`: Data types to include (can be specified multiple times)
- `--exclude`: Data types to exclude (can be specified multiple times)
- `--path, -p`: Directory containing JSON files to import (defaults to current directory)
- `--dry-run`: Show what would be imported without actually importing
- `--name`: Filter items by name using Python regex patterns (matches trace names, dataset names, experiment names, or prompt names)

**File Organization:**
Exported files are organized in a hierarchical structure that mirrors the workspace/project hierarchy:

```
path/
├── WORKSPACE/
│   └── PROJECT_NAME/
│       ├── trace_*.json          # Individual traces with their spans
│       ├── dataset_*.json         # Dataset definitions and items
│       ├── experiment_*.json      # Experiment configurations and results
│       └── prompt_*.json         # Prompt templates and versions
```

**Name Filtering:**
Both export and import commands support filtering by name using Python regex patterns:

```bash
# Export only traces with names starting with "test"
opik export WORKSPACE --name "^test"

# Export datasets containing "evaluation" (case-insensitive)
opik export WORKSPACE --include datasets --name "(?i).*evaluation.*"

# Import only items with names ending in "_v2"
opik import WORKSPACE --name ".*_v2$"

# Import prompts with names containing "template"
opik import WORKSPACE --include prompts --name ".*template.*"
```

**Regex Pattern Examples:**
- `^test` - Names starting with "test"
- `.*prod.*` - Names containing "prod"
- `.*_v[0-9]+$` - Names ending with version numbers like "_v1", "_v2", etc.
- `(?i).*template.*` - Names containing "template" (case-insensitive)
- `^[A-Z].*` - Names starting with uppercase letters

**Examples:**
```bash
# Export from default workspace
opik export default/ez-mcp-chatbot --all
# Creates: ./default/ez-mcp-chatbot/ with all data files

# Export from different workspace (cloud/enterprise only)
opik export production/my-project --all
# Creates: ./production/my-project/ with all data files

# Import to different workspace/project (cloud/enterprise only)
opik import production/copy --path ./default/ez-mcp-chatbot --all
# Imports data from default/ez-mcp-chatbot to production/copy

# Note: Open source installations only support the "default" workspace
```

**Note:** Thread metadata is automatically calculated from traces with the same `thread_id` when imported, so no separate thread files are needed.

**Important:**
- **Traces and spans** are project-specific and will be imported to the specified project
- **Datasets, experiments, and prompts** belong to the workspace and are shared across all projects in that workspace

## Development & Contribution Guidelines

For a more general contribution guide (backend + frontend + SDK) see our root [Contribution guide](../../CONTRIBUTING.md).

### Coding Guidelines
This guide is still in progress, however, it already contains useful information that you should know before submitting your PR.

#### General
We care a lot about the code maintainability. Well-organized logic which is easy to extend, re-factor and, most importantly - **read**, is what we are striving for.
1. Follow [SOLID](https://realpython.com/solid-principles-python/) principles. Pay special attention to the "Single Responsibility" one.
2. Avoid large modules, large classes, and large functions. Separate the code properly and describe this separation with names, not with comments. (See [1])
3. If the name is not used outside of the class/module - it should be `_protected`.
4. Don't violate the access rules! We know that Python allows you to access _protected/__private variables, but in Opik we are quite strict about not abusing that, whether it's an internal code or a test (don't forget about [3]!).
5. Use comments only for something non-trivial that is hard to describe in any other way. Apart from these cases, comments should be used to answer the question "Why?" not "What?".

#### Imports
1. Import module - not name.
    Instead of this:
    ```python
    from threading import Thread  # bad!
    thread = Thread()
    ```
    do this:
    ```python
    import threading  # good!
    thread = threading.Thread
    ```

2. If the import statement is too big, you can do the following
    ```python
    from opik.rest_api.core import error as rest_api_error  # ok!
    ```

3. If you are working in the namespace, you likely don't need to keep most of the parent namespaces
    ```python
    # inside opik.api_objects.dataset
    from . import dataset_item  # ok!
    ```

4. Of course, there might be exceptions from this rule, for example, some common types can be imported as is.
    ```python
    from typing import Dict, List  # ok!
    from opik.types import FeedbackScoreDict  # ok!
    ```

#### Naming
1. Avoid abbreviations. In the vast majority of cases, it is not a problem to use variable names. People spend more time understanding what "fs" means than reading the word "files" or "file_system".
   ```python
   for d in dataset_items:  # bad!

   for item in dataset_items:  # ok!
       ...
   for dataset_item in dataset_items  # ok!
       ...
   ```
2. Avoid creating modules like `utils.py`, `helpers.py`, `misc.py` etc. Especially in the big namespaces. They can quickly become dumps where people put everything that they haven't been able to create a better place for in 10 seconds after they started thinking about it. You can create those files though, but they should be localized in their namespaces designed for some specific features. In vast majority of cases there are better module names.

#### Testing
We highly encourage writing tests and we develop a lot of features in a test-driven way.
1. Test public API, don't violate privacy.
2. If you are an external contributor - make sure that the unit tests and e2e tests are green (they can be executed anywhere because they don't require any API keys or permissions). For internal Opik developers everything should be green in the CI.
3. If you have `if-statements` in your code or some non-trivial boiler-plate code - it's probably a reason to think about add some unit tests for that. The more complex your code, the higher chance you'll be asked to provide unit tests for it.
4. If you are introducing a new feature that includes communication with the backend - it's better to add some e2e tests for that (at least the happy flow one).
5. Avoid testing with e2e tests something that can be tested with unit tests. E2E tests are time-consuming.
6. If you are introducing a change in one of the integrations (or a new integration), make sure the integration tests are working. They usually require API keys configured for the services the integration works with. When the external contributor opens a PR, their tests will not use our Github secrets so consider providing your repo with an API key required for the integration. In that case, we will see that the tests are green.
7. We are using `fake_backend` fixture together with a special Opik assertions DSL(domain-specific language) for a lot of unit tests and library integration tests. We encourage you to use it as well! There is plenty of examples, you can take a look at `tests/unit/decorator/test_tracker_outputs.py` or `tests/library_integration/openai/test_openai.py`. It provides a pretty simple API for specifying the traces content you expect your feature to log.
