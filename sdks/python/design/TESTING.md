# Opik Python SDK Testing Guide

## Table of Contents

- [Overview](#overview)
- [Test Directory Structure](#test-directory-structure)
- [Test Categories](#test-categories)
- [Testing Infrastructure](#testing-infrastructure)
- [Testing Patterns](#testing-patterns)
- [Writing Tests](#writing-tests)
- [Running Tests](#running-tests)

## Overview

The Opik Python SDK has a comprehensive test suite organized into multiple categories:

1. **Unit Tests**: Fast, isolated tests with no external dependencies
2. **Library Integration Tests**: Tests integrations using fake backend
3. **E2E Tests**: Real backend tests for core functionality
4. **E2E Library Integration Tests**: Real backend tests for library integrations
5. **Smoke Tests**: Quick sanity checks

### Testing Philosophy

- **Fast Feedback**: Unit tests run quickly for rapid development
- **Isolation**: Use fake backends to avoid network dependencies
- **Realism**: E2E tests validate against real backend
- **Coverage**: Test both happy paths and edge cases
- **Maintainability**: Shared utilities and clear patterns

## Test Directory Structure

```
tests/
├── conftest.py                     # Root fixtures (context cleanup, client shutdown)
├── pytest.ini                      # Pytest configuration
├── test_requirements.txt           # Test dependencies
│
├── testlib/                        # Shared testing utilities
│   ├── models.py                   # Test data models (TraceModel, SpanModel, etc.)
│   ├── backend_emulator_message_processor.py  # Fake backend
│   ├── assert_helpers.py           # Assertion utilities
│   ├── any_compare_helpers.py      # Flexible matchers (ANY, ANY_BUT_NONE)
│   ├── fake_message_factory.py     # Message creation helpers
│   ├── noop_file_upload_manager.py # No-op file uploader
│   └── environment.py              # Environment utilities
│
├── unit/                           # Unit tests (no external dependencies)
│   ├── conftest.py                 # Unit test fixtures
│   ├── api_objects/                # Tests for API objects
│   │   ├── test_opik_client.py
│   │   ├── dataset/
│   │   ├── experiment/
│   │   ├── trace/
│   │   └── ...
│   ├── decorator/                  # Decorator tests
│   │   ├── test_tracker_outputs.py         # Comprehensive decorator tests
│   │   ├── test_dynamic_tracing.py
│   │   ├── test_span_context_manager.py
│   │   └── ...
│   ├── evaluation/                 # Evaluation framework tests
│   │   ├── test_evaluate.py
│   │   ├── metrics/                # Metric tests
│   │   └── ...
│   ├── message_processing/         # Message processing tests
│   │   ├── test_message_streaming.py
│   │   ├── batching/
│   │   └── ...
│   └── ...                         # Other unit tests
│
├── library_integration/            # Integration tests with fake backend
│   ├── conftest.py                 # Shared fixtures
│   ├── openai/                     # OpenAI integration tests
│   │   ├── requirements.txt
│   │   ├── constants.py
│   │   ├── test_openai_responses.py
│   │   └── ...
│   ├── anthropic/                  # Anthropic integration tests
│   ├── langchain/                  # LangChain integration tests
│   ├── bedrock/                    # AWS Bedrock tests
│   ├── litellm/                    # LiteLLM tests
│   └── ...                         # Other integrations
│
├── e2e/                            # End-to-end tests (real backend)
│   ├── conftest.py                 # E2E fixtures
│   ├── verifiers.py                # Backend verification helpers
│   ├── test_tracing.py             # Core tracing tests
│   ├── test_dataset.py             # Dataset tests
│   ├── test_prompt.py              # Prompt tests
│   ├── evaluation/                 # Evaluation E2E tests
│   └── ...
│
├── e2e_library_integration/        # E2E library integration (real backend)
│   ├── conftest.py                 # E2E lib integration fixtures
│   ├── litellm/                    # LiteLLM E2E tests
│   ├── adk/                        # ADK E2E tests
│   └── ...
│
└── e2e_smoke/                      # Quick smoke tests
    ├── dry_run_import.py
    └── smoke_tests_runner.sh
```

## Test Categories

### 1. Unit Tests (`tests/unit/`)

**Purpose**: Fast, isolated tests with no external dependencies.

**Characteristics**:
- Use fake backend (`fake_backend` fixture)
- No network calls
- Test internal logic and edge cases
- Run in milliseconds (almost always)

**Key Fixtures**:
```python
@pytest.fixture
def fake_backend(patch_streamer):
    """
    Replaces Streamer with fake backend emulator.
    Captures messages and builds trace/span trees.
    Access via: fake_backend.trace_trees, fake_backend.span_trees
    """
```

**Example Structure**:
```python
def test_track__one_nested_function__happyflow(fake_backend):
    @opik.track
    def f_inner(x):
        return "inner-output"

    @opik.track
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    opik.flush_tracker()

    # Verify against expected tree structure
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        spans=[
            SpanModel(name="f_outer", spans=[
                SpanModel(name="f_inner", spans=[])
            ])
        ]
    )

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
```

**What to Test**:
- Decorator behavior (input/output capture, nesting)
- Message creation and processing
- Batching logic
- Context management
- Error handling
- Metric calculations
- Data transformations

### 2. Library Integration Tests (`tests/library_integration/`)

**Purpose**: Test integrations with external libraries using fake backend.

**Characteristics**:
- Real integration library calls (OpenAI, LangChain, etc.)
- Fake Opik backend (no backend network calls)
- Verify tracing structure without backend dependency
- Requires API keys for external services

**Directory Structure**:
```
library_integration/
├── openai/
│   ├── requirements.txt          # OpenAI-specific dependencies
│   ├── constants.py              # Test constants (models, etc.)
│   ├── test_openai_responses.py
│   └── test_openai_chat_completions.py
├── anthropic/
├── langchain/
└── ...
```

**Example Structure**:
```python
def test_openai_client_responses_create__happyflow(fake_backend):
    client = openai.OpenAI()
    wrapped_client = track_openai(client, project_name="test")

    # Real OpenAI API call
    response = wrapped_client.responses.create(
        model=MODEL_FOR_TESTS,
        input=[{"role": "user", "content": "Hello"}]
    )

    opik.flush_tracker()

    # Verify trace structure with fake backend
    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]
    assert trace.name == "responses_create"
    assert trace.spans[0].type == "llm"
    assert trace.spans[0].provider == "openai"
```

**What to Test**:
- Integration decorator wrapping
- Input/output capture from library responses
- Usage tracking (tokens, costs)
- Provider-specific metadata
- Streaming responses
- Error handling
- Nested calls

**Requirements Files**:
Each integration has its own `requirements.txt`:
```txt
# openai/requirements.txt
openai>=1.0.0

# langchain/requirements.txt
langchain>=0.1.0
langchain-openai>=0.1.0
```

### 3. E2E Tests (`tests/e2e/`)

**Purpose**: Test core functionality against real Opik backend.

**Characteristics**:
- Real backend calls
- Slower (network + backend processing)
- Full system validation
- Requires configured Opik backend

**Key Fixtures**:
```python
@pytest.fixture()
def opik_client(configure_e2e_tests_env, shutdown_cached_client_after_test):
    """Real Opik client for E2E tests"""
    opik_client_ = opik.Opik(_use_batching=True)
    yield opik_client_
    opik_client_.end()

@pytest.fixture
def dataset_name(opik_client):
    """Generate unique dataset name"""
    name = f"e2e-tests-dataset-{random_chars()}"
    yield name
```

**Example Structure**:
```python
def test_trace_creation_and_retrieval(opik_client, temporary_project_name):
    # Create trace
    trace_id = opik_client.trace(
        name="test_trace",
        input={"query": "test"},
        project_name=temporary_project_name
    )
    opik_client.flush()

    # Verify against real backend
    verify_trace(
        opik_client,
        trace_id=trace_id,
        name="test_trace",
        input={"query": "test"},
        project_name=temporary_project_name
    )
```

**What to Test**:
- Trace/span creation and retrieval
- Dataset CRUD operations
- Experiment tracking
- Prompt management
- Feedback scores
- Attachments
- Search operations
- Thread management

**Verifiers (`verifiers.py`)**:
```python
def verify_trace(opik_client, trace_id, name, input, output, ...):
    """Wait for trace to appear in backend and verify fields"""
    if not synchronization.until(
        lambda: opik_client.get_trace_content(id=trace_id) is not None,
        allow_errors=True
    ):
        raise AssertionError(f"Failed to get trace {trace_id}")

    trace = opik_client.get_trace_content(id=trace_id)
    assert trace.name == name
    assert trace.input == input
    # ... more assertions

def verify_span(opik_client, span_id, ...):
    """Similar verification for spans"""

def verify_experiment_items(opik_client, experiment_id, expected_items):
    """Verify experiment items match expected"""
```

### 4. E2E Library Integration Tests (`tests/e2e_library_integration/`)

**Purpose**: Test library integrations against real backend.

**Characteristics**:
- Real library calls + Real backend calls
- Slowest test category
- Full integration validation
- Requires both service API keys and backend

**Example Structure**:
```python
def test_litellm_chat_model_e2e(opik_client_unique_project_name):
    """Test LiteLLM integration with real backend"""
    from litellm import completion
    from opik.integrations.litellm import track_litellm

    track_litellm()

    # Real LiteLLM call (which calls real LLM provider)
    response = completion(
        model="gpt-3.5-turbo",
        messages=[{"role": "user", "content": "Hello"}]
    )

    opik.flush_tracker()

    # Verify in real backend
    traces = opik_client_unique_project_name.search_traces()
    assert len(traces) > 0
```

**When to Use**:
- Critical integration paths
- Features that require real backend state
- Complex multi-step workflows
- Release validation

### 5. Smoke Tests (`tests/e2e_smoke/`)

**Purpose**: Quick sanity checks that SDK can be imported and basic operations work.

**Example**:
```python
# dry_run_import.py
import opik
import opik.evaluation.metrics as metrics

# Verify basic imports work
client = opik.Opik()
```

## Testing Infrastructure

### Test Models (`testlib/models.py`)

Domain-specific models for test assertions:

```python
@dataclasses.dataclass
class SpanModel:
    """Represents expected span structure"""
    id: str
    name: Optional[str] = None
    input: Any = None
    output: Any = None
    type: str = "general"
    usage: Optional[Dict[str, Any]] = None
    spans: List["SpanModel"] = dataclasses.field(default_factory=list)
    # ... more fields

@dataclasses.dataclass
class TraceModel:
    """Represents expected trace structure"""
    id: str
    name: Optional[str]
    input: Any = None
    output: Any = None
    spans: List[SpanModel] = dataclasses.field(default_factory=list)
    # ... more fields

@dataclasses.dataclass
class FeedbackScoreModel:
    """Represents expected feedback score"""
    id: str
    name: str
    value: float
    reason: Optional[str] = None
```

### Fake Backend (`testlib/backend_emulator_message_processor.py`)

**Purpose**: Emulate backend behavior for unit and library integration tests.

**Key Features**:
- Processes messages without network calls
- Builds trace and span trees from messages in memory
- Supports duplicate merging (simulates backend behavior)
- Tracks feedback scores and attachments

```python
class BackendEmulatorMessageProcessor(BaseMessageProcessor):
    def __init__(self, merge_duplicates: bool = True):
        self.processed_messages: List[messages.BaseMessage] = []
        self._trace_trees: List[TraceModel] = []
        self._span_trees: List[SpanModel] = []
        # ... internal state

    @property
    def trace_trees(self) -> List[TraceModel]:
        """Build and return trace trees from processed messages"""

    @property
    def span_trees(self) -> List[SpanModel]:
        """Build and return span trees from processed messages"""

    def process(self, message: messages.BaseMessage) -> None:
        """Process message and update internal state"""
```

**Usage**:
```python
def test_example(fake_backend):
    # Execute code that creates traces/spans
    @opik.track
    def my_function():
        return "result"

    my_function()
    opik.flush_tracker()

    # Access built trees
    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].name == "my_function"
```

### Flexible Matchers (`testlib/any_compare_helpers.py`)

Special matchers for flexible assertions:

```python
ANY = SpecialValue("ANY")  # Matches anything
ANY_BUT_NONE = SpecialValue("ANY_BUT_NONE")  # Matches anything except None
ANY_STRING = StringMatcher()  # String-specific matcher
ANY_DICT = DictMatcher()  # Dict-specific matcher

# Usage
assert_equal(
    expected=TraceModel(
        id=ANY_BUT_NONE,  # Don't care about ID, but must exist
        name="test",
        start_time=ANY_BUT_NONE,  # Don't care about time but must exist
        input={"key": "value"}  # Exact match
    ),
    actual=fake_backend.trace_trees[0]
)

# String matchers
ANY_STRING.starting_with("gpt-")
ANY_STRING.ending_with(".txt")
ANY_STRING.containing("test")
```

### Assertion Helpers (`testlib/assert_helpers.py`)

```python
def assert_equal(expected, actual):
    """
    Deep equality check with support for:
    - SpecialValue matchers (ANY, ANY_BUT_NONE)
    - Nested dataclasses
    - Lists and dicts
    - Provides detailed diff on mismatch
    """

def assert_dict_has_keys(dict_obj, required_keys):
    """Verify dict contains all required keys"""
```

### Fixtures

#### Root Fixtures (`tests/conftest.py`)

```python
@pytest.fixture(autouse=True)
def clear_context_storage():
    """Automatically clear context after each test"""
    yield
    context_storage.clear_all()

@pytest.fixture(autouse=True)
def shutdown_cached_client_after_test():
    """Clean up cached Opik client after each test"""
    yield
    if opik_client.get_client_cached.cache_info().currsize > 0:
        opik_client.get_client_cached().end()
        opik_client.get_client_cached.cache_clear()

@pytest.fixture
def fake_backend(patch_streamer):
    """Fake backend for unit/library integration tests"""
    streamer, fake_message_processor = patch_streamer
    # ... setup
    yield fake_message_processor
    # ... cleanup

@pytest.fixture
def patch_streamer():
    """Create streamer with fake backend"""
    fake_processor = BackendEmulatorMessageProcessor()
    fake_upload_manager = NoopFileUploadManager()
    streamer = streamer_constructors.construct_streamer(
        message_processor=fake_processor,
        n_consumers=1,
        use_batching=True,
        upload_preprocessor=file_upload_preprocessor.FileUploadPreprocessor(fake_upload_manager),
        max_queue_size=None
    )
    yield streamer, fake_processor
    streamer.close(timeout=5)
```

#### E2E Fixtures (`tests/e2e/conftest.py`)

```python
@pytest.fixture()
def opik_client(configure_e2e_tests_env):
    """Real Opik client with batching enabled"""
    client = opik.Opik(_use_batching=True)
    yield client
    client.end()

@pytest.fixture
def dataset_name(opik_client):
    """Generate unique dataset name for test"""
    name = f"e2e-tests-dataset-{random_chars()}"
    yield name

@pytest.fixture
def temporary_project_name(opik_client):
    """Create and cleanup temporary project"""
    name = f"e2e-tests-temporary-project-{random_chars()}"
    yield name
    # Cleanup
    project_id = opik_client.rest_client.projects.retrieve_project(name=name).id
    opik_client.rest_client.projects.delete_project_by_id(project_id)
```

#### Library Integration Fixtures

```python
# tests/library_integration/conftest.py
@pytest.fixture(autouse=True)
def reset_tracing_to_config_default():
    """Reset tracing config between tests"""
    opik.reset_tracing_to_config_default()
    yield
    opik.reset_tracing_to_config_default()

# tests/library_integration/openai/conftest.py
@pytest.fixture
def ensure_openai_configured():
    """Verify OpenAI API key is configured"""
    if not os.getenv("OPENAI_API_KEY"):
        pytest.skip("OPENAI_API_KEY not configured")
```

## Testing Patterns

### Pattern 1: Testing Decorator Behavior

**Location**: `tests/unit/decorator/test_tracker_outputs.py`

```python
def test_track__one_nested_function__happyflow(fake_backend):
    """
    Test naming convention:
    test_WHAT__CASE_DESCRIPTION__EXPECTED_RESULT
    """
    @opik.track
    def f_inner(x):
        return "inner-output"

    @opik.track
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    opik.flush_tracker()  # Wait for async processing

    # Build expected tree structure
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                spans=[
                    SpanModel(
                        name="f_inner",
                        input={"x": "inner-input"},
                        output={"output": "inner-output"},
                        spans=[]
                    )
                ]
            )
        ]
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
```

### Pattern 2: Testing Integration Tracking

**Location**: `tests/library_integration/openai/test_openai_responses.py`

```python
@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("custom-project", "custom-project"),
    ],
)
def test_openai_client_responses_create__happyflow(
    fake_backend, project_name, expected_project_name
):
    # Setup integration
    client = openai.OpenAI()
    wrapped_client = track_openai(client, project_name=project_name)

    # Real API call
    response = wrapped_client.responses.create(
        model=MODEL_FOR_TESTS,
        input=[{"role": "user", "content": "Tell a fact"}],
        max_output_tokens=50
    )

    opik.flush_tracker()

    # Build expected structure
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="responses_create",
        input={"input": ANY_BUT_NONE},
        output={"output": ANY_BUT_NONE, "reasoning": ANY},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="responses_create",
                provider="openai",
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                usage=ANY_BUT_NONE,
                metadata=ANY_DICT,
                tags=["openai"],
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[]
            )
        ]
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])

    # Optional: Verify specific metadata keys if needed
    assert_dict_has_keys(
        fake_backend.trace_trees[0].spans[0].metadata,
        ["created_from", "model"]
    )
```

### Pattern 3: Testing E2E with Backend Verification

**Location**: `tests/e2e/test_tracing.py`

```python
def test_trace_creation_with_spans(opik_client, temporary_project_name):
    # Create trace
    trace_id = opik_client.trace(
        name="parent_trace",
        input={"query": "test"},
        project_name=temporary_project_name
    )

    # Create spans
    span_id_1 = opik_client.span(
        name="span_1",
        trace_id=trace_id,
        input={"step": 1}
    )

    span_id_2 = opik_client.span(
        name="span_2",
        trace_id=trace_id,
        parent_span_id=span_id_1,
        input={"step": 2}
    )

    opik_client.flush()

    # Verify in backend
    verify_trace(
        opik_client,
        trace_id=trace_id,
        name="parent_trace",
        input={"query": "test"},
        project_name=temporary_project_name
    )

    verify_span(
        opik_client,
        span_id=span_id_1,
        name="span_1",
        trace_id=trace_id,
        parent_span_id=None
    )

    verify_span(
        opik_client,
        span_id=span_id_2,
        name="span_2",
        trace_id=trace_id,
        parent_span_id=span_id_1
    )
```

### Pattern 4: Testing Error Handling

```python
def test_track__function_raises_exception__error_info_captured(fake_backend):
    @opik.track
    def failing_function():
        raise ValueError("Test error")

    with pytest.raises(ValueError, match="Test error"):
        failing_function()

    opik.flush_tracker()

    # Build expected structure with error_info
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="failing_function",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="failing_function",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info={
                    "exception_type": "ValueError",
                    "message": ANY_STRING.containing("Test error"),
                    "traceback": ANY_BUT_NONE
                },
                spans=[]
            )
        ]
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
```

### Pattern 5: Testing Streaming Responses

```python
def test_openai_streaming_response(fake_backend):
    client = openai.OpenAI()
    wrapped_client = track_openai(client)

    # Stream response
    stream = wrapped_client.chat.completions.create(
        model=MODEL_FOR_TESTS,
        messages=[{"role": "user", "content": "Count to 5"}],
        stream=True
    )

    # Consume stream
    for chunk in stream:
        pass  # Consume all chunks

    opik.flush_tracker()

    # Verify accumulated data using models
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completions_create",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat_completions_create",
                type="llm",
                provider="openai",
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS),
                usage=ANY_BUT_NONE,  # Usage accumulated from chunks
                output=ANY_BUT_NONE,  # Output accumulated from chunks
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[]
            )
        ]
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
```

### Pattern 6: Testing Metrics

```python
def test_hallucination_metric__happyflow():
    metric = Hallucination()

    result = metric.score(
        input="What is the capital of France?",
        output="Paris is the capital of France.",
        context=["Paris is the capital and largest city of France."]
    )

    assert isinstance(result, ScoreResult)
    assert 0 <= result.value <= 1
    assert result.name == "hallucination_metric"
    assert result.reason is not None
```

## Writing Tests

### Test Naming Convention

Follow the pattern: `test_WHAT__CASE_DESCRIPTION__EXPECTED_RESULT`

```python
# ✅ Good
def test_track__one_nested_function__happyflow(fake_backend):
def test_track__function_raises_exception__error_info_captured(fake_backend):
def test_evaluate__with_custom_metric__scores_computed_correctly(fake_backend):

# ❌ Bad
def test_tracking():
def test_error():
def test_evaluate():
```

### Using Fake Backend

```python
def test_my_feature(fake_backend):
    # 1. Execute code that creates traces/spans
    @opik.track
    def my_function(x):
        return x * 2

    result = my_function(5)
    opik.flush_tracker()  # Always flush!

    # 2. Build expected structure
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="my_function",
        input={"x": 5},
        output={"output": 10},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="my_function",
                input={"x": 5},
                output={"output": 10},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[]
            )
        ]
    )

    # 3. Assert
    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
```

### Testing with Real Backend

```python
def test_my_e2e_feature(opik_client, temporary_project_name):
    # 1. Create resources
    trace_id = opik_client.trace(
        name="test_trace",
        project_name=temporary_project_name
    )
    opik_client.flush()

    # 2. Verify using verifiers
    verify_trace(
        opik_client,
        trace_id=trace_id,
        name="test_trace",
        project_name=temporary_project_name
    )
```

### Parametrized Tests

```python
@pytest.mark.parametrize(
    "input_value, expected_output",
    [
        (5, 10),
        (10, 20),
        (0, 0),
    ],
)
def test_double_function__various_inputs__correct_outputs(
    fake_backend, input_value, expected_output
):
    @opik.track
    def double(x):
        return x * 2

    result = double(input_value)
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].spans[0].output == {"output": expected_output}
```

### Integration Test Requirements

Each integration should have:
1. `requirements.txt` with integration dependencies
2. `conftest.py` with integration-specific fixtures
3. `constants.py` for test constants (models, etc.)
4. Tests for main integration features

```python
# library_integration/myintegration/requirements.txt
myintegration>=1.0.0

# library_integration/myintegration/conftest.py
import pytest
import os

@pytest.fixture
def ensure_myintegration_configured():
    if not os.getenv("MYINTEGRATION_API_KEY"):
        pytest.skip("MYINTEGRATION_API_KEY not configured")

# library_integration/myintegration/test_myintegration.py
def test_myintegration_basic(fake_backend, ensure_myintegration_configured):
    # Test implementation
```

## Running Tests

### Run All Tests
```bash
pytest tests/
```

### Run Specific Category
```bash
# Unit tests only (fast)
pytest tests/unit/

# Library integration tests
pytest tests/library_integration/

# E2E tests
pytest tests/e2e/

# Specific integration
pytest tests/library_integration/openai/
```

### Environment Variables

Some library integration and E2E tests require certain environment variables to be configured:
```bash
# Backend configuration
export OPIK_URL_OVERRIDE="http://localhost:5000"
export OPIK_API_KEY="your_api_key"

# LLM provider keys (for library integration tests)
export OPENAI_API_KEY="..."
export ANTHROPIC_API_KEY="..."
export GOOGLE_API_KEY="..."
```

## Best Practices

1. **Always Use Fake Backend for Unit and Library Integration Tests**: Avoid network calls
2. **Test Public API Only**: Don't test private methods
3. **Use Flexible Matchers**: Use `ANY`, `ANY_BUT_NONE` for non-critical fields
4. **Build Expected Structures**: Make tests readable with clear expected output
5. **Clean Up Resources**: Use fixtures for cleanup (especially E2E tests)
6. **Parametrize Similar Tests**: Reduce duplication with `@pytest.mark.parametrize`
7. **Document Test Purpose**: Use clear names and docstrings
8. **Test Edge Cases**: Include error cases, empty inputs, etc.
9. **Keep Tests Fast**: Unit tests should run in milliseconds
10. **Use Verifiers for E2E**: Leverage existing verification helpers

For more information, see:
- [API and Data Flow](API_AND_DATA_FLOW.md) - Core architecture and data flow
- [Integrations](INTEGRATIONS.md) - Integration patterns and testing
- [Evaluation](EVALUATION.md) - Evaluation framework architecture
- [Test Organization Rules](../../../.agents/skills/python-sdk/testing.md)
- [Test Implementation Rules](../../../.agents/skills/python-sdk/good-code.md)
