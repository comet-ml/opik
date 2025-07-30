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
Traces will appear in your configured Opik project. Opik also offers many direct [integrations](https://www.comet.com/docs/opik/tracing/integrations/overview/) for popular LLM frameworks.

## Development & Contribution Guidelines

For a more general contribution guide (backend + frontend + SDK) see our root [Contribution guide](../../CONTRIBUTING.md).

# Coding guidelines
This guide is still in progress, however, it already contains useful information that you should know before submitting your PR.

## General
We care a lot about the code maintainability. Well-organized logic which is easy to extend, re-factor and, most importantly - **read**, is what we are striving for.
1. Follow [SOLID](https://realpython.com/solid-principles-python/) principles. Pay special attention to the "Single Responsibility" one.
2. Avoid large modules, large classes, and large functions. Separate the code properly and describe this separation with names, not with comments. (See [1])
3. If the name is not used outside of the class/module - it should be `_protected`.
4. Don't violate the access rules! We know that Python allows you to access _protected/__private variables, but in Opik we are quite strict about not abusing that, whether it's an internal code or a test (don't forget about [3]!).
5. Use comments only for something non-trivial that is hard to describe in any other way. Apart from these cases, comments should be used to answer the question "Why?" not "What?".

## Imports
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

## Naming
1. Avoid abbreviations. In the vast majority of cases, it is not a problem to use variable names. People spend more time understanding what "fs" means than reading the word "files" or "file_system".
   ```python
   for d in dataset_items:  # bad!

   for item in dataset_items:  # ok!
       ...
   for dataset_item in dataset_items  # ok!
       ...
   ```
2. Avoid creating modules like `utils.py`, `helpers.py`, `misc.py` etc. Especially in the big namespaces. They can quickly become dumps where people put everything that they haven't been able to create a better place for in 10 seconds after they started thinking about it. You can create those files though, but they should be localized in their namespaces designed for some specific features. In vast majority of cases there are better module names.

## Testing
We highly encourage writing tests and we develop a lot of features in a test-driven way.
1. Test public API, don't violate privacy.
2. If you are an external contributor - make sure that the unit tests and e2e tests are green (they can be executed anywhere because they don't require any API keys or permissions). For internal Opik developers everything should be green in the CI.
3. If you have `if-statements` in your code or some non-trivial boiler-plate code - it's probably a reason to think about add some unit tests for that. The more complex your code, the higher chance you'll be asked to provide unit tests for it.
4. If you are introducing a new feature that includes communication with the backend - it's better to add some e2e tests for that (at least the happy flow one).
5. Avoid testing with e2e tests something that can be tested with unit tests. E2E tests are time-consuming.
6. If you are introducing a change in one of the integrations (or a new integration), make sure the integration tests are working. They usually require API keys configured for the services the integration works with. When the external contributor opens a PR, their tests will not use our Github secrets so consider providing your repo with an API key required for the integration. In that case, we will see that the tests are green.
7. We are using `fake_backend` fixture together with a special Opik assertions DSL(domain-specific language) for a lot of unit tests and library integration tests. We encourage you to use it as well! There is plenty of examples, you can take a look at `tests/unit/decorator/test_tracker_outputs.py` or `tests/library_integration/openai/test_openai.py`. It provides a pretty simple API for specifying the traces content you expect your feature to log.
