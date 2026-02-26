---
applyTo: "sdks/python/**/*.py"
---

# Opik Python SDK Code Review Guidelines

This document provides essential guidelines for reviewing Opik Python SDK code. It focuses on the most critical architectural patterns, design principles, and best practices that ensure code quality and maintainability.

## 🔧 Core Architecture Principles

### Three-Layer Architecture
The SDK uses a strict 3-layer architecture. Always ensure code belongs to the correct layer:

**Layer 1: Public API** (`opik.Opik`, `@opik.track`)
- User-facing interface, input validation, context management

**Layer 2: Message Processing** (observability operations only)
- Background workers for trace/span/feedback operations

**Layer 3: REST API Client**
- HTTP communication with Opik backend


### Execution Paths

**Asynchronous Path** (traces, spans, feedback):
```python
client.trace(name="test")           # Non-blocking, batched
client.log_traces_feedback_scores() # Background processing
```

**Synchronous Path** (datasets, experiments, search):
```python
dataset = client.create_dataset(name="test")  # Blocking, returns immediately
traces = client.search_traces(...)           # Direct REST call
```

## 📋 API Design Standards

### Opik.Opik is the Main Entry Point
```python
# ✅ Good: Always use Opik as factory
client = opik.Opik()
experiment = client.create_experiment(name="test")
dataset = client.create_dataset(name="data")

# ❌ Bad: Direct instantiation
from opik.api_objects.experiment import Experiment
experiment = Experiment(name="test")  # Bypasses main API
```

### Consistent Parameter Patterns
```python
# ✅ Good: Follow established patterns
def create_experiment(self, name: str, description: Optional[str] = None)
def create_dataset(self, name: str, description: Optional[str] = None)

# ❌ Bad: Inconsistent ordering
def create_experiment(self, description: str, name: str)  # Wrong order
def create_dataset(self, name: str, desc: str)           # Different param name
```

### Method Naming Conventions
- **CRUD**: `create_*`, `get_*`, `update_*`, `delete_*`
- **Search**: `search_*` for complex queries
- **Batch**: `batch_*` for bulk operations

## 🧪 Testing Standards

### Test Category Selection
- **Unit Tests** (`tests/unit/`): Fake backend, no external calls
- **Library Integration** (`tests/library_integration/`): Real libraries, fake Opik backend
- **E2E Tests** (`tests/e2e/`): Real backend, full integration

### Key Testing Rules
```python
# ✅ Good: Unit test with fake backend
def test_decorator_behavior(fake_backend):
    @opik.track
    def my_function(): return "result"

    my_function()
    opik.flush_tracker()  # Always flush!

    assert len(fake_backend.trace_trees) == 1

# ✅ Good: E2E test with real backend
def test_dataset_crud(opik_client, dataset_name):
    dataset = opik_client.create_dataset(name=dataset_name)
    retrieved = opik_client.get_dataset(name=dataset_name)
    assert retrieved.name == dataset_name

### Test Naming Convention
```
test_WHAT__CASE_DESCRIPTION__EXPECTED_RESULT
```
```python
# ✅ Good
def test_track__error_in_nested_function__captures_error_info()
def test_dataset_insert__duplicate_id__raises_error()

# ❌ Bad
def test_tracking()
def test_dataset()
```

### Fake Backend Usage
```python
def test_my_feature(fake_backend):
    # Execute code
    @opik.track
    def func(): return "result"

    func()
    opik.flush_tracker()  # Always flush!

    # Assert structure
    EXPECTED = TraceModel(
        id=ANY_BUT_NONE,  # Flexible ID matching
        name="func",
        output={"output": "result"}
    )
    assert_equal(EXPECTED, fake_backend.trace_trees[0])
```

## 🛡️ Error Handling Standards

### Exception Hierarchy
```python
# ✅ Good: Use specific exceptions
class MetricComputationError(OpikException):
    """Raised when metric computation fails"""
    pass

# ❌ Bad: Generic exceptions
raise Exception("Something went wrong")
raise ValueError("Invalid input")
```

### Metric Error Handling
```python
# ✅ Good: Metrics raise MetricComputationError on failure
class Hallucination(BaseMetric):
    def score(self, **kwargs):
        try:
            return compute_score(**kwargs)
        except Exception as e:
            raise MetricComputationError(f"Failed to compute: {e}") from e

# ❌ Bad: Hide errors or return 0
def score(self, **kwargs):
    try:
        return compute_score(**kwargs)
    except:
        return 0  # Silent failure!
```

## 🏛️ Code Structure Rules

### Module Logic Segregation

- **One module, one responsibility**: Each module should have a single, well-defined purpose
- **Avoid monolithic modules**: Don't create modules with many unrelated classes/functions
- **Group related functionality**: Keep related classes and functions together
- **Split when modules grow**: Break large modules into focused, cohesive units

```python
# ✅ Good: Focused module (one responsibility - HTTP client)
# httpx_client.py - Only HTTP client utilities
class HttpxClient:
    """HTTP client wrapper."""

# ✅ Good: Focused module (one responsibility - configuration)
# config.py - Only configuration management
class OpikConfig:
    """Configuration management."""

# ❌ Bad: Monolithic module (multiple responsibilities)
# utils.py - Everything dumped here!
class HttpClient: ...
class ConfigManager: ...
class DataProcessor: ...
class FileHandler: ...
def parse_json(...): ...
def format_date(...): ...
def calculate_hash(...): ...
```

### Import Organization
Always import modules, not names. General exceptions are standard library type hints and type aliases generally considered widely known types across the library (they are usually stored in `types.py` root module).

```python
# ✅ Good: Module imports, grouped by type (except for type hints and type aliases)
import atexit
import logging
from typing import Any, Dict, List

import httpx

from . import config, exceptions
from .message_processing import messages

# ✅ Good: Type hints and type aliases are allowed directly
from typing import Optional, Union, TypeVar
from .types import ErrorInfoDict, FeedbackScoreDict

# ❌ Bad: Importing names directly (violates "import module, not name" rule)
from opik.exceptions import OpikException, ValidationError
from opik.config import get_from_user_inputs
from httpx import Client
```

### Access Control
```python
# ✅ Good: Private methods for internal use
class Opik:
    def create_experiment(self, name: str):
        return self._create_experiment_internal(name)

    def _create_experiment_internal(self, name: str):
        # Internal logic
        pass

# ❌ Bad: Public methods only used internally
class DataProcessor:
    def process(self, data):
        return self.clean_data(data)  # clean_data only called here!

    def clean_data(self, data):  # Should be private
        pass
```

## 🔍 Key Review Checkpoints

### Architecture Review
- [ ] Code belongs to correct layer (Public API vs Message Processing vs REST)
- [ ] No complex business logic in Public API layer
- [ ] Module logic segregation (one responsibility per module)

### Code Quality Review
- [ ] Consistent parameter ordering and naming
- [ ] Proper access control (private methods when appropriate)
- [ ] Clean import organization
- [ ] Type hints where possible

### Error Handling Review
- [ ] Specific OpikException subclasses used
- [ ] Metrics raise MetricComputationError (never hide errors)

### Testing Review
- [ ] Appropriate test type used (unit vs integration vs e2e)
- [ ] Fake backend used for unit/library integration tests
- [ ] Test naming follows convention
- [ ] Verifiers used for E2E assertions
- [ ] Always flush tracker in tests that create spans/traces


## 🚫 Critical Anti-Patterns

1. **Import names instead of modules** - Always import modules, not names (except type hints and type aliases)
2. **Monolithic modules** - Don't create `utils.py` dumping grounds or god modules with unrelated classes/functions.
3. **Direct REST calls in Public API** - Always use API Object Clients for complex operations
4. **Silent error handling** - Never catch and ignore exceptions in metrics
5. **Incorrect test isolation** - Unit tests and library integration tests should use fake backend, not real backend
6. **Public internal methods** - Make methods private if only used within class/module

## 📖 Essential References

- **[API and Data Flow Design](../../sdks/python/design/API_AND_DATA_FLOW.md)** - Core architecture and execution paths
- **[Testing Design](../../sdks/python/design/TESTING.md)** - Test categories and fake backend usage
- **[Integrations Design](../../sdks/python/design/INTEGRATIONS.md)** - Integration patterns and streaming strategies
- **[Architecture Rules](../../.agents/skills/python-sdk/SKILL.md)** - Layered architecture details
- **[Test Organization Rules](../../.agents/skills/python-sdk/testing.md)** - Testing standards and patterns

## Code Structure Guidelines

### Import Organization

#### Import Grouping and Order

- **Always import modules, not names**: Allowed exceptions for this rule - `typing` module or similar commonly used types across the project (usually stored in `opik/types.py`)
- **Keep the namespace clean**
- **Group imports**: standard library, third-party, local imports
- **Order**: Standard library → Third-party → Local imports

```python
# ✅ Good: Proper import organization (from opik_client.py)
import atexit
import datetime
import functools
import logging
from typing import Any, Dict, List, Optional, TypeVar, Union, Literal

import httpx

from .threads import threads_client
from .. import (
    config,
    datetime_helpers,
    exceptions,
    httpx_client,
    id_helpers,
    llm_usage,
    rest_client_configurator,
    url_helpers,
)
from ..message_processing import messages, streamer_constructors, message_queue
from ..rest_api import client as rest_api_client
from ..types import ErrorInfoDict, FeedbackScoreDict, LLMProvider, SpanType

LOGGER = logging.getLogger(__name__)
```

#### Module-Level Imports

```python
# ✅ Good: Import modules for cleaner namespace
import opik.exceptions as exceptions
import opik.config as config
from opik.api_objects import opik_client
from opik.message_processing.messages import (
    GuardrailBatchItemMessage,
    GuardrailBatchMessage,
)

# ❌ Bad: Importing names directly (avoid unless necessary)
from opik.exceptions import OpikException, ValidationError
from opik.config import get_from_user_inputs
```

#### Typing Imports

```python
# ✅ Good: Typing imports are allowed directly
from typing import Any, Dict, List, Optional, TypeVar, Union, Literal

# ✅ Good: TYPE_CHECKING for avoiding circular imports
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from uuid import UUID
    from langchain_core.runnables.graph import Graph
    from langchain_core.messages import BaseMessage
```

### Access Control

#### Protected Methods and Attributes

- **Never violate access modifiers**
- **If method/attribute is used only inside its class** - it should be protected
- **If function/object is used only inside its module** - it should be protected
- **Classes may omit underscores in names** if they are used only in their modules
- **Review method visibility regularly**: Ask "Is this method called from outside this class?"

```python
# ✅ Good: Protected methods for internal use
class OpikConfigurator:
    def configure(self) -> None:
        """Public API method."""
        self._configure_cloud()

    def _configure_cloud(self) -> None:
        """Private method - only used within this class."""
        api_key = self._ask_for_api_key()
        workspace = self._get_default_workspace()
        self._update_config()

    def _ask_for_api_key(self) -> str:
        """Private method - only used within this class."""
        pass

    def _update_config(self) -> None:
        """Private method - only used within this class."""
        pass
```

#### Module-Level Protection

```python
# ✅ Good: Protected constants and functions at module level
LOGGER = logging.getLogger(__name__)

# Constants used only within module
_DEFAULT_TIMEOUT = 30
_MAX_RETRIES = 3

def _internal_helper_function(data: Any) -> Any:
    """Private function used only within this module."""
    return data

def public_api_function(input_data: Any) -> Any:
    """Public function available to other modules."""
    return _internal_helper_function(input_data)
```

#### Class Access Patterns

```python
# ✅ Good: Proper access control in classes
class Opik:
    def __init__(self, project_name: Optional[str] = None):
        """Public constructor."""
        self._project_name = project_name  # Protected attribute
        self._config = self._initialize_config()  # Protected attribute
        self._streamer = self._initialize_streamer()  # Protected attribute

    def create_experiment(self, name: str) -> Experiment:
        """Public API method."""
        return self._create_experiment_internal(name)

    def _create_experiment_internal(self, name: str) -> Experiment:
        """Protected method - used only within this class."""
        pass

    def _initialize_config(self) -> Config:
        """Protected method - used only within this class."""
        pass

    def _initialize_streamer(self) -> Streamer:
        """Protected method - used only within this class."""
        pass
```

## Dependency Management Guidelines

### Core Principles

- **Prioritize keeping existing dependencies** (stored in setup.py) and avoid adding new ones
- **Keep dependency versions flexible** with appropriate bounds
- **Avoid adding heavy dependencies** without strong justification
- **Use conditional imports** for optional dependencies (usually the case for integrations)
- **Make sure the python versions specified in setup.py** can execute new code

### Conditional Imports

#### Lazy Import Pattern

- **Pattern**: Use lazy imports for optional dependencies that may not be installed
- **Example**: LiteLLM integration uses conditional imports
- **Error Handling**: Handle ImportError gracefully

```python
# ✅ Good: Lazy import pattern (from opik_monitor.py)
from typing import Optional

def lazy_import_OpikLogger() -> Optional["litellm.integrations.opik.opik.OpikLogger"]:
    try:
        from litellm.integrations.opik.opik import OpikLogger
        return OpikLogger
    except ImportError:
        return None

def try_add_opik_monitoring_to_params(params: Dict[str, Any]) -> Dict[str, Any]:
    if lazy_import_OpikLogger() is None:
        return params

    # Continue with integration logic
    import litellm
    # ... rest of implementation
```

#### Integration Import Patterns

```python
# ✅ Good: Direct import for integration modules
# Integration files assume the dependency is available when imported
import haystack
from haystack import logging, tracing

import crewai
import anthropic
import openai

# ❌ Bad: Import at module level without lazy loading
import heavy_ml_library  # Always loads even if not needed
from heavy_ml_library import complex_function  # Wastes memory
```


## Design Principles Guidelines

### Single Responsibility Principle

- **Follow SOLID principles**
- **Organize modules by functionality, avoid generic utility modules**
- **Use meaningful module and class names** that reflect their purpose, no shortcuts
- **Keep modules, classes and functions focused** on single responsibilities

```python
# ✅ Good: Single responsibility - focused only on thread management
class ThreadsClient:
    """Client for managing and interacting with conversational threads."""
    def __init__(self, client: "opik.Opik"):
        self._opik_client = client

    def search_threads(self, project_name: Optional[str] = None) -> List[TraceThread]:
        """Single responsibility - only handles thread search operations."""
        pass

# ✅ Good: Single responsibility - focused only on message processing
class OpikMessageProcessor(BaseMessageProcessor):
    """Processes messages with single responsibility - message handling."""
    def __init__(self, rest_client: rest_api_client.OpikApi):
        self._rest_client = rest_client

    def process(self, message: messages.BaseMessage) -> None:
        """Single responsibility - only processes messages."""
        pass

# ❌ Bad: Multiple responsibilities in one class
class DataManager:  # Does everything!
    def __init__(self): pass

    # Database operations
    def save_to_db(self, data): pass
    def load_from_db(self, id): pass

    # File operations
    def save_to_file(self, data, filename): pass
    def read_from_file(self, filename): pass

    # Network operations
    def send_to_api(self, data): pass
    def fetch_from_api(self, url): pass

    # Data processing
    def validate_data(self, data): pass
    def transform_data(self, data): pass
```

### Open/Closed Principle

- **Design for extension without modification**
- **Use factory patterns for creating specialized objects**
- **Implement provider-specific behavior through abstraction**

```python
# ✅ Good: Open for extension via factory pattern (from opik_usage_factory.py)
_PROVIDER_TO_OPIK_USAGE_BUILDERS: Dict[
    Union[str, LLMProvider],
    List[Callable[[Dict[str, Any]], opik_usage.OpikUsage]],
] = {
    LLMProvider.OPENAI: [
        opik_usage.OpikUsage.from_openai_completions_dict,
        opik_usage.OpikUsage.from_openai_responses_dict,
    ],
    LLMProvider.ANTHROPIC: [opik_usage.OpikUsage.from_anthropic_dict],
    LLMProvider.BEDROCK: [opik_usage.OpikUsage.from_bedrock_dict],
}

def build_opik_usage(
    provider: Union[str, LLMProvider],
    usage: Dict[str, Any],
) -> opik_usage.OpikUsage:
    """Factory function open for extension - new providers can be added."""
    build_functions = _PROVIDER_TO_OPIK_USAGE_BUILDERS[provider]

    for build_function in build_functions:
        try:
            return build_function(usage)
        except Exception:
            continue

    raise ValueError(f"Failed to build OpikUsage for provider {provider}")
```

### Dependency Inversion Principle

- **Use builder functions for creating complex objects**
- **Follow dependency injection principles**
- **Inject dependencies rather than creating them directly**

```python
# ✅ Good: Dependency injection pattern (from streamer.py)
class Streamer:
    def __init__(
        self,
        queue: message_queue.MessageQueue[messages.BaseMessage],
        queue_consumers: List[queue_consumer.QueueConsumer],
        batch_manager: Optional[batch_manager.BatchManager],
        file_upload_manager: base_upload_manager.BaseFileUploadManager,
    ) -> None:
        """Dependencies are injected rather than created internally."""
        self._message_queue = queue
        self._queue_consumers = queue_consumers
        self._batch_manager = batch_manager
        self._file_upload_manager = file_upload_manager

        # Start injected components
        self._start_queue_consumers()
        if self._batch_manager is not None:
            self._batch_manager.start()

# ✅ Good: Factory function that builds dependencies (from streamer_constructors.py)
def construct_online_streamer(
    rest_client: rest_api_client.OpikApi,
    httpx_client: httpx.Client,
    use_batching: bool,
    file_upload_worker_count: int,
    n_consumers: int,
    max_queue_size: int,
) -> streamer.Streamer:
    """Factory function that creates and injects dependencies."""
    message_processor = message_processors.OpikMessageProcessor(rest_client=rest_client)
    file_uploader = upload_manager.FileUploadManager(
        rest_client=rest_client,
        httpx_client=httpx_client,
        worker_count=file_upload_worker_count,
    )

    return construct_streamer(
        message_processor=message_processor,
        file_upload_manager=file_uploader,
        n_consumers=n_consumers,
        use_batching=use_batching,
        max_queue_size=max_queue_size,
    )
```

### Interface Segregation

- **Create focused, specialized interfaces**
- **Avoid large, monolithic interfaces**
- **Group related functionality appropriately**

```python
# ✅ Good: Focused interfaces for different concerns
class ThreadsClient:
    """Focused only on thread operations."""
    def search_threads(self, project_name: Optional[str] = None) -> List[TraceThread]:
        pass
    def log_feedback_scores_to_thread(self, thread_id: str, scores: List[FeedbackScoreDict]):
        pass

# ✅ Good: Specialized client interfaces from OpikApi
class OpikApi:
    def __init__(self, ...):
        # Each client handles a specific domain
        self.datasets = DatasetsClient(client_wrapper=self._client_wrapper)
        self.experiments = ExperimentsClient(client_wrapper=self._client_wrapper)
        self.feedback_definitions = FeedbackDefinitionsClient(client_wrapper=self._client_wrapper)
        self.guardrails = GuardrailsClient(client_wrapper=self._client_wrapper)
```

## Error Handling Guidelines

### Exception Types and Hierarchy

#### Custom Exception Classes

- **Use specific exception types** for different error categories
- **Inherit custom exceptions** from appropriate base classes in `opik.exceptions`
- **Add new exception types** when existing ones don't fit the use case
- **Raise `opik.exceptions.MetricComputationError`** from `opik.evaluation.metrics.BaseMetric` subclasses instead of hiding or masking missing data or errors

```python
# ✅ Good: Specific exception types (from exceptions.py)
class OpikException(Exception):
    """Base exception for all Opik-related errors."""
    pass

class ConfigurationError(OpikException):
    """Raised when configuration is invalid."""
    pass

class MetricComputationError(OpikException):
    """Exception raised when a metric cannot be computed."""
    pass

class GuardrailValidationFailed(OpikException):
    """Exception raised when a guardrail validation fails."""

    def __init__(
        self,
        message: str,
        validation_results: List["schemas.ValidationResult"],
        failed_validations: List["schemas.ValidationResult"],
    ):
        self.message = message
        self.validation_results = validation_results
        self.failed_validations = failed_validations
        super().__init__(message)

    def __str__(self) -> str:
        return f"{self.message}. Failed validations: {self.failed_validations}\n"
```

#### Structured Exception Information

```python
# ✅ Good: Exception with structured data (from exceptions.py)
class ScoreMethodMissingArguments(OpikException):
    def __init__(
        self,
        score_name: str,
        missing_required_arguments: Sequence[str],
        available_keys: Sequence[str],
        unused_mapping_arguments: Optional[Sequence[str]] = None,
    ):
        self.score_name = score_name
        self.missing_required_arguments = missing_required_arguments
        self.available_keys = available_keys
        self.unused_mapping_arguments = unused_mapping_arguments
        super().__init__(self._get_error_message())

    def _get_error_message(self) -> str:
        message = (
            f"The scoring method {self.score_name} is missing arguments: {self.missing_required_arguments}. "
            f"These keys were not present in either the dataset item or the dictionary returned by the evaluation task. "
            f"You can either update the dataset or evaluation task to return this key or use the `scoring_key_mapping` to map existing items to the expected arguments. "
            f"The available keys found in the dataset item and evaluation task output are: {self.available_keys}. "
        )
        if self.unused_mapping_arguments:
            message += f" Some keys in `scoring_key_mapping` didn't match anything: {self.unused_mapping_arguments}"
        return message
```

### Error Handling Patterns

#### Specific Exception Handling

```python
# ✅ Good: Handling specific exceptions (from message_processors.py)
def process(self, message: messages.BaseMessage) -> None:
    try:
        handler(message)
    except rest_api_core.ApiError as exception:
        if exception.status_code == 409:
            # Sometimes a retry mechanism works in a way that it sends the same request 2 times.
            # If the backend rejects the second request, we don't want users to see an error.
            return
        elif exception.status_code == 429:
            if exception.headers is not None:
                rate_limiter = rate_limit.parse_rate_limit(exception.headers)
                if rate_limiter is not None:
                    raise exceptions.OpikCloudRequestsRateLimited(
                        headers=exception.headers,
                        retry_after=rate_limiter.retry_after(),
                    )

        error_tracking_extra = _generate_error_tracking_extra(exception, message)
        LOGGER.error(
            logging_messages.FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER,
            message_type.__name__,
            str(exception),
            extra={"error_tracking_extra": error_tracking_extra},
        )
    except tenacity.RetryError as retry_error:
        cause = retry_error.last_attempt.exception()
        error_tracking_extra = _generate_error_tracking_extra(cause, message)
        LOGGER.error(
            logging_messages.FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER,
            message_type.__name__,
            f"{cause.__class__.__name__} - {cause}",
            extra={"error_tracking_extra": error_tracking_extra},
        )
    except pydantic.ValidationError as validation_error:
        error_tracking_extra = _generate_error_tracking_extra(validation_error, message)
        LOGGER.error(
            "Failed to process message: '%s' due to input data validation error:\n%s\n",
            message_type.__name__,
            validation_error,
            exc_info=True,
            extra={"error_tracking_extra": error_tracking_extra},
        )
```


## Documentation and Style Guidelines

### Type Hints

#### Comprehensive Type Annotations

- **Use complete type hints** for all function signatures
- **Import typing utilities** for complex types
- **Use Union types** for multiple possible types
- **Use Optional** for nullable parameters

```python
# ✅ Good: Comprehensive type hints (from opik_client.py)
from typing import Any, Dict, List, Optional, TypeVar, Union

def search_spans(
    self,
    project_name: Optional[str] = None,
    trace_id: Optional[str] = None,
    filter_string: Optional[str] = None,
    max_results: int = 1000,
    truncate: bool = True,
) -> List[span_public.SpanPublic]:
    """Search spans with comprehensive type annotations."""
    pass

# ✅ Good: Complex type hints (from opik_usage.py)
ProviderUsage = Union[
    openai_chat_completions_usage.OpenAICompletionsUsage,
    google_usage.GoogleGeminiUsage,
    anthropic_usage.AnthropicUsage,
    bedrock_usage.BedrockUsage,
    openai_responses_usage.OpenAIResponsesUsage,
    unknown_usage.UnknownUsage,
]

# ✅ Good: Abstract method with type hints (from base_model.py)
@abc.abstractmethod
def generate_string(
    self,
    input: str,
    response_format: Optional[Type[pydantic.BaseModel]] = None,
    **kwargs: Any,
) -> str:
    """Type hints for abstract methods."""
    pass

# ❌ Bad: Missing type hints
def process_data(data):  # No type hints!
    return data.upper()

# ❌ Bad: Using Any everywhere
def handle_request(request: Any) -> Any:  # Too vague
    return request

# ❌ Bad: Incorrect Optional usage
def find_user(id: Optional[int] = None) -> User:  # Should handle None case
    return users[id]  # Will fail if id is None
```

### Type Variable Usage

```python
# ✅ Good: Type variable declaration (from opik_client.py)
from typing import TypeVar

T = TypeVar("T")

def process_data(self, data: T) -> T:
    """Generic type variable usage."""
    return data
```

### Docstring Standards

#### Class Documentation

```python
# ✅ Good: Class docstring (from base_model.py)
class OpikBaseModel(abc.ABC):
    """
    This class serves as an interface to LLMs.

    If you want to implement custom LLM provider in evaluation metrics,
    you should inherit from this class.
    """

    def __init__(self, model_name: str):
        """
        Initializes the base model with a given model name.

        Args:
            model_name: The name of the LLM to be used.
        """
        self.model_name = model_name

# ✅ Good: Class docstring with usage details (from opik_usage.py)
class OpikUsage(pydantic.BaseModel):
    """
    A class used to convert different formats of token usage dictionaries
    into format supported by Opik ecosystem.

    `from_PROVIDER_usage_dict methods` methods are used to parse original provider's token
    usage dicts and calculate openai-formatted extra key-value pairs (that can later be used on the FE and BE sides).
    """
```

#### Method Documentation

```python
# ✅ Good: Method docstring with Args (from opik_client.py)
def search_spans(
    self,
    project_name: Optional[str] = None,
    trace_id: Optional[str] = None,
    filter_string: Optional[str] = None,
    max_results: int = 1000,
    truncate: bool = True,
) -> List[span_public.SpanPublic]:
    """
    Search for spans in the given trace. This allows you to search spans based on the span input, output,
    metadata, tags, etc. or based on the trace ID.

    Args:
        project_name: The name of the project to search spans in. If not provided, will search across the project name configured when the Client was created which defaults to the `Default Project`.
        trace_id: The ID of the trace to search spans in. If provided, the search will be limited to the spans in the given trace.
        filter_string: A filter string to narrow down the search.
        max_results: The maximum number of spans to return.
        truncate: Whether to truncate image data stored in input, output, or metadata
    """
    pass

# ✅ Good: Method with Parameters and Returns (from opik_client.py)
def create_prompt(
    self,
    name: str,
    prompt: str,
    metadata: Optional[Dict[str, Any]] = None,
    type: PromptType = PromptType.MUSTACHE,
) -> Prompt:
    """
    Creates a new prompt with the given name and template.
    If a prompt with the same name already exists, it will create a new version of the existing prompt if the templates differ.

    Parameters:
        name: The name of the prompt.
        prompt: The template content of the prompt.
        metadata: Optional metadata to be included in the prompt.

    Returns:
        A Prompt object containing details of the created or retrieved prompt.

    Raises:
        ApiError: If there is an error during the creation of the prompt and the status code is not 409.
    """
    pass

# ✅ Good: Simple method docstring (from opik_client.py)
def get_trace_content(self, id: str) -> trace_public.TracePublic:
    """
    Args:
        id (str): trace id
    Returns:
        trace_public.TracePublic: pydantic model object with all the data associated with the trace found.
        Raises an error if trace was not found.
    """
    pass
```

### Comments and Code Clarity

#### When to Add Comments

- **Business logic explanation**: Why certain decisions were made
- **Non-obvious behavior**: When code behavior isn't immediately clear
- **External dependencies**: Explain interactions with external systems
- **Configuration details**: Document important configuration decisions

```python
# ✅ Good: Comments explaining business logic (from configure.py)
def _configure_url(self, url_override: Optional[str]) -> None:
    # Handle URL
    base_url = url_override or self._default_base_url

    # This URL set here might not be the final one.
    # It's possible that the URL will be extracted from the smart api key on the later stage.
    # In that case `self.base_url` field will be updated.
    self.base_url = base_url

def _determine_deployment_type(self, url_override: Optional[str]) -> str:
    if url_override:
        # Step 1: If the URL is provided and active, update the configuration
        return "provided"

    # Step 2: Check if the default local instance is active
    if self._check_local_deployment():
        # Step 3: Ask user if they want to use the found local instance
        return "local"

    # Step 4: Ask user for URL if no valid local instance is found or approved
    return "cloud"
```

#### Self-Documenting Code

```python
# ✅ Good: Self-explanatory code (no comments needed)
def validate_experiment_name(name: str) -> bool:
    return name and len(name.strip()) > 0 and len(name) <= 255

def build_api_client(base_url: str, api_key: str) -> OpikApi:
    return OpikApi(base_url=base_url, api_key=api_key)

# ✅ Good: Meaningful variable names
def process_llm_response(provider: LLMProvider, response_data: Dict[str, Any]) -> OpikUsage:
    usage_builders = _PROVIDER_TO_OPIK_USAGE_BUILDERS[provider]

    for build_function in usage_builders:
        try:
            return build_function(response_data)
        except Exception:
            continue

    raise ValueError(f"Failed to build usage for provider {provider}")
```

### Logic Duplication Detection

#### When to Extract Helper Methods

**Red Flag**: Similar code blocks with only minor differences - these are prime candidates for helper methods that extract the common pattern while parameterizing the differences.

```python
# ❌ Bad: Code duplication
def process_user_data(user):
    if user.age < 18:
        send_email(user.email, "minor_notification")
        log_event("minor_user_processed")
        return "minor"
    else:
        send_email(user.email, "adult_notification")
        log_event("adult_user_processed")
        return "adult"

def process_admin_data(admin):
    if admin.age < 18:
        send_email(admin.email, "minor_admin_notification")  # Similar logic!
        log_event("minor_admin_processed")                   # Similar logic!
        return "minor_admin"
    else:
        send_email(admin.email, "adult_admin_notification")  # Similar logic!
        log_event("adult_admin_processed")                   # Similar logic!
        return "adult_admin"

# ✅ Good: Extracted helper method
def _notify_and_log_user(user, age_category, user_type):
    notification_type = f"{age_category}_{user_type}_notification"
    send_email(user.email, notification_type)
    log_event(f"{age_category}_{user_type}_processed")
    return f"{age_category}_{user_type}"

def process_user_data(user):
    age_category = "minor" if user.age < 18 else "adult"
    return _notify_and_log_user(user, age_category, "user")

def process_admin_data(admin):
    age_category = "minor" if admin.age < 18 else "adult"
    return _notify_and_log_user(admin, age_category, "admin")
```

### Access Control Review

#### Method Visibility Analysis

**Question to Ask**: "Is this method called from outside this class?"

```python
# ❌ Bad: Public method only used internally
class DataProcessor:
    def process_data(self, data):
        cleaned = self.clean_data(data)      # Only called here
        validated = self.validate_data(data) # Only called here
        return self.format_data(validated)

    def clean_data(self, data):      # Should be private
        pass

    def validate_data(self, data):   # Should be private
        pass

    def format_data(self, data):     # Should be private
        pass
```

```python
# ✅ Good: Appropriate access control
class DataProcessor:
    def process_data(self, data):        # Public interface
        cleaned = self._clean_data(data)
        validated = self._validate_data(data)
        return self._format_data(validated)

    def _clean_data(self, data):         # Private helper
        pass

    def _validate_data(self, data):      # Private helper
        pass

    def _format_data(self, data):        # Private helper
        pass
```

### Parameter Redundancy Detection

#### Avoiding State Duplication

**Red Flag**: Passing data that's already stored in the object

```python
# ❌ Bad: Redundant parameter passing
class SpanTracker:
    def __init__(self):
        self._span_data = {}

    def set_span_data(self, data: Dict[str, Any]) -> None:
        self._span_data.update(data)

    def validate_span(self, data: Dict[str, Any]) -> bool:  # Redundant parameter
        span_id = data.get("span_id")                       # Could use self._span_data
        # Validate using external data instead of stored state
        return span_id is not None
```

```python
# ✅ Good: Use internal state
class SpanTracker:
    def __init__(self):
        self._span_data = {}

    def set_span_data(self, data: Dict[str, Any]) -> None:
        """Store span data in internal state."""
        self._span_data.update(data)

    def validate_span(self) -> bool:  # No redundant parameters
        """Validate span using internal state."""
        span_id = self._span_data.get("span_id")
        # Validate using internal state
        return span_id is not None
```

### Method Naming Improvement

#### Descriptive vs Generic Names

**Pattern**: Methods should describe **what** they do, not **how** they do it

```python
# ❌ Bad: Generic, unclear names
def process_span(self, value):          # What kind of processing?
def update_trace(self, name):           # What kind of update?
def handle_data(self, data):            # Too generic
def get_raw(self):                      # What does "raw" mean?
```

```python
# ✅ Good: Specific, action-oriented names
def _validate_and_store_span_input(self, value):   # Clear action + outcome
def _extract_and_set_trace_metadata(self, name):   # Clear action + target
def _parse_and_validate_feedback(self, data):      # Clear actions
def get_unprocessed_span_data(self):                # Clear what is returned
```

### Refactoring Decision Tree

When reviewing a method, ask these questions in order:

1. **Duplication**: Does this logic appear elsewhere with minor variations?
   - → Extract common patterns into helper methods

2. **Access**: Is this method only called from within this class?
   - → Make it private with `_` prefix

3. **Parameters**: Am I passing data that's already stored in `self`?
   - → Remove redundant parameters, use internal state

4. **Naming**: Does the method name clearly describe its action and purpose?
   - → Rename to be more descriptive and action-oriented

5. **Constants**: Are there magic strings/numbers that appear in multiple places?
   - → Extract to constants module

## Key References

- [API Design Guidelines](../../.agents/skills/python-sdk/good-code.md)
- [Architecture Guidelines](../../.agents/skills/python-sdk/SKILL.md)
- [Code Structure Guidelines](../../.agents/skills/python-sdk/good-code.md)
- [Error Handling Guidelines](../../.agents/skills/python-sdk/error-handling.md)
