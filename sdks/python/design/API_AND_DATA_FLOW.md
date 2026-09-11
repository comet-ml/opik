# Opik Python SDK: API and Data Flow

## Table of Contents

- [Overview](#overview)
- [High-Level API](#high-level-api)
- [Core Architecture](#core-architecture)
- [Data Flow](#data-flow)
- [Message Processing Deep Dive](#message-processing-deep-dive)
- [Batching System](#batching-system)
- [Observability](#observability)
- [Performance Considerations](#performance-considerations)

## Overview

The Opik Python SDK provides **lightweight, non-blocking tracing** for LLM applications. The architecture prioritizes minimal performance impact on user code through asynchronous message processing, intelligent batching, and background workers.

### Key Design Goals

1. **Non-blocking**: User code never waits for backend communication
2. **Low overhead**: Minimal CPU and memory footprint
3. **Reliable**: Message queues and retries ensure data delivery
4. **Observable**: Comprehensive logging and error tracking
5. **Scalable**: Handles high-throughput applications

## High-Level API

### Main Entry Point: `opik.Opik`

The `Opik` class is the central entry point and factory for all SDK operations.

**Location**: `opik/api_objects/opik_client.py`

```python
import opik

# Initialize client
client = opik.Opik(
    project_name="my_project",    # Optional: defaults to "Default Project"
    workspace="my_workspace",     # Optional: defaults to "default"
    host="https://api.opik.com",  # Optional: custom backend URL
    api_key="your_api_key"        # Optional: for cloud deployments
)
```

#### Configuration Priority

Configuration is resolved in this order (highest to lowest):
1. **Direct parameters** to `Opik()` constructor
2. **Environment variables** (`OPIK_PROJECT_NAME`, `OPIK_WORKSPACE`, etc.)
3. **Configuration file** (`~/.opik.config`)
4. **Default values**

### Core API Methods

#### Manual Tracing

```python
# Create a trace
trace = client.trace(
    name="my_operation",
    input={"query": "What is AI?"},
    metadata={"version": "1.0"},
    tags=["production"]
)

# Create a span (must be within trace context or provide trace_id)
span = client.span(
    name="llm_call",
    trace_id=trace.id,           # Required if no trace context
    parent_span_id=None,         # Optional: for nested spans
    input={"prompt": "..."},
    type="llm",                  # Types: "llm", "tool", "general"
    model="gpt-4",
    provider="openai"
)

# Update trace/span
client.trace(
    id=trace.id,
    output={"answer": "..."},
    metadata={"tokens": 150}
)

# Add feedback scores
client.log_traces_feedback_scores(
    scores=[{
        "id": trace.id,
        "name": "accuracy",
        "value": 0.95,
        "reason": "Accurate response"
    }]
)

# Ensure all data is sent
client.flush(timeout=30)  # Wait up to 30 seconds
```

#### Automatic Tracing with Decorators

```python
import opik

# Simplest usage
@opik.track
def my_function(input: str) -> str:
    # Automatically creates trace and span
    # Captures input and output
    return process(input)

# With options
@opik.track(
    name="custom_name",              # Override function name
    project_name="my_project",       # Set project
    capture_input=True,              # Capture inputs (default: True)
    capture_output=True,             # Capture outputs (default: True)
    tags=["production"],             # Add tags
    metadata={"version": "1.0"},     # Add metadata
    type="llm"                       # Set span type
)
def llm_call(prompt: str) -> str:
    return call_llm(prompt)

# Nested functions create nested spans
@opik.track
def outer_function(data):
    preprocessed = preprocess(data)  # Creates nested span
    result = process(preprocessed)   # Creates nested span
    return result

@opik.track
def preprocess(data):
    return {"cleaned": data}

@opik.track
def process(data):
    return {"result": data}
```

#### Context Management

```python
import opik

# Access current context
trace_data = opik.get_current_trace_data()
span_data = opik.get_current_span_data()

# Update current trace/span
opik.update_current_span(
    metadata={"key": "value"},
    tags=["important"],
    usage={"completion_tokens": 100, "prompt_tokens": 50, "total_tokens": 150}
)

opik.update_current_trace(
    output={"result": "success"}
)

# Context managers for manual control
with opik.start_as_current_trace(name="my_trace", input={"data": "test"}) as trace:
    # Trace context active
    with opik.start_as_current_span(name="step1", type="tool") as span:
        # Span context active
        do_work()
    # Span auto-closed
# Trace auto-closed

# Distributed tracing
headers = opik.get_distributed_trace_headers()
# Pass headers to remote service
# Remote service continues trace: opik.track(distributed_headers=headers)
```

#### Resource Management

```python
# Create and manage datasets
dataset = client.create_dataset(
    name="my_dataset",
    description="Test dataset"
)

dataset.insert([
    {"input": "query1", "expected": "answer1"},
    {"input": "query2", "expected": "answer2"}
])

# Create experiments
experiment = client.create_experiment(
    name="exp_v1",
    dataset_name="my_dataset"
)

# Manage prompts
prompt = client.create_prompt(
    name="my_prompt",
    prompt="Answer this question: {{question}}",
    type="text"
)

# Create new version
prompt_v2 = prompt.create_version(
    prompt="Enhanced: {{question}}\nContext: {{context}}"
)
```

## Core Architecture

### Layered Architecture

The SDK is organized into 3 layers:

```
┌──────────────────────────────────────────────────────────────┐
│                    Layer 1: Public API                       │
│                                                              │
│   opik.Opik, @opik.track, opik_context                       │
│   - User-facing interface                                    │
│   - Input validation                                         │
│   - Context management                                       │
└─────────────┬────────────────────────────┬───────────────────┘
              │                            │
              │ Observability              │ Resource Management
              │ (trace, span, feedback)    │ (dataset, experiment,
              │                            │  prompt, search, etc.)
              │                            │
              ▼                            ▼
┌──────────────────────────────┐  ┌──────────────────────────┐
│  Layer 2: Message Processing │  │  API Object Clients      │
│  (Observability operations)  │  │  (Resource operations)   │
│                              │  │                          │
│  Streamer                    │  │  Dataset, Experiment,    │
│    ↓                         │  │  Prompt, Attachment,     │
│  Queue                       │  │  Threads clients         │
│    ↓                         │  │                          │
│  Consumers                   │  │  - Manage state          │
│    ↓                         │  │  - Handle complex logic  │
│  MessageProcessor            │  │  - Wrap REST calls       │
│                              │  │                          │
│  - Background async          │  │  Delegates to ↓          │
│  - Batching                  │  └──────────────┬───────────┘
│  - Retry logic               │                 │
│                              │                 │
│  Delegates to ↓              │                 │
└───────────────┬──────────────┘                 │
                │                                │
                └────────────────┬───────────────┘
                                 ▼
              ┌───────────────────────────────────────────────┐
              │       Layer 3: REST API Client                │
              │                                               │
              │  OpikApi (auto-generated from OpenAPI)        │
              │  - HTTP client                                │
              │  - Request/response serialization             │
              │  - Connection pooling                         │
              │                                               │
              │  ═══════════════════════════════════════      │
              │  ║  HTTP requests to Opik Backend      ║      │
              │  ║  (External service, not part of SDK)║      │
              │  ═══════════════════════════════════════      │
              └───────────────────────────────────────────────┘
```

**Key Points**:
- **Layer 1 (Public API)**: What users interact with directly (`opik.Opik`, `@opik.track`)
- **Layer 2 (Message Processing)**: Background workers - **only for observability operations** (trace/span/feedback)
- **API Object Clients**: Intermediate layer for resource management - handle state and complex logic
- **Layer 3 (REST API Client)**: HTTP communication layer (used by both Layer 2 and API Object Clients)
- **Opik Backend**: External service (not part of SDK) that receives HTTP requests

**Two Execution Paths**:

1. **Observability operations** (trace/span/feedback):
   - `opik.Opik` → **Message Processing** (Layer 2) → REST API Client → Backend
   - Non-blocking, uses background workers

2. **Resource management operations** (dataset/experiment/prompt):
   - `opik.Opik` → **API Object Clients** → REST API Client → Backend
   - Blocking, returns objects

**API Object Clients** (`opik/api_objects/`):

For complex resource types, intermediate client classes provide:
- **State management**: Dataset items, experiment state, prompt versions
- **Business logic**: Item insertion, versioning, validation
- **Convenience methods**: `dataset.insert()`, `experiment.get_items()`, `prompt.format()`
- **REST abstraction**: Wrap multiple REST calls into higher-level operations

**Examples**:
- `Dataset` (`dataset/dataset.py`) - Manages dataset items, handles insertion/deletion
- `Experiment` (`experiment/experiment.py`) - Tracks experiment items, links to dataset
- `Prompt` (`prompt/prompt.py`) - Manages prompt versions and templating
- `AttachmentClient` (`attachment/client.py`) - Handles file attachments
- `ThreadsClient` (`threads/threads_client.py`) - Manages conversational threads

For simple operations (search, get), `opik.Opik` calls REST client directly without intermediate client.

### Synchronous vs Asynchronous Operations

The Opik client provides two types of operations with different execution paths:

#### Asynchronous Operations (via Layer 2: Message Processing)

**Observability operations** that use background processing:

| Operation | Purpose | Returns |
|-----------|---------|---------|
| `trace()` | Create/update trace | None (fire-and-forget) |
| `span()` | Create/update span | None (fire-and-forget) |
| `log_traces_feedback_scores()` | Add feedback to traces | None |
| `log_spans_feedback_scores()` | Add feedback to spans | None |
| `experiment.insert()` | Create experiment items | None |
| Attachment uploads | Upload files to S3 | None |

**Flow**: API → **Message** → **Streamer** → **Queue** → **Consumer** → REST Client → Backend

**Characteristics**:
- ⚡ Non-blocking (returns immediately)
- 📦 Supports batching (Create messages batch together)
- 🔁 Automatic retries
- ⚠️ Requires `flush()` before app exit

#### Synchronous Operations (via API Object Clients or Direct REST)

**Resource management and query operations** that bypass message processing:

| Category | Operations | Uses |
|----------|-----------|------|
| **Dataset** | `create_dataset()`, `get_dataset()`, `delete_dataset()` | Dataset client |
| **Experiment** | `create_experiment()`, `get_experiment_by_id()` | Experiment client |
| **Prompt** | `create_prompt()`, `get_prompt()`, `update_prompt()` | Prompt client |
| **Search** | `search_traces()`, `search_spans()` | Direct REST |
| **Retrieval** | `get_trace_content()`, `get_span_content()` | Direct REST |
| **Delete** | `delete_traces()`, `delete_*_feedback_score()` | Direct REST |

**Flow (with API Object Client)**:
```
client.create_dataset(name) → Dataset.__init__() → REST Client → Backend
                              ↓
                              Returns Dataset object with methods
```

**Flow (direct REST)**:
```
client.search_traces() → REST Client → Backend → Returns List[TracePublic]
```

**Characteristics**:
- 🔒 Blocking (waits for response)
- ✅ Returns data immediately
- 🚫 No batching
- ⏱️ No flush needed

#### Why Different Paths?

**Async path** (observability):
- High frequency (100s-1000s per second)
- Performance-critical (shouldn't slow down user code)
- Can be batched (many traces/spans combined)
- Fire-and-forget (no immediate result needed)

**Sync path** (resources):
- Low frequency (setup/teardown operations)
- Returns objects needed for further operations
- Can't be batched (unique operations)
- User expects to wait for result

### Key Components

#### 1. Context Storage (`opik/context_storage.py`)

Manages trace and span context using Python's `contextvars` for proper isolation.

```python
class OpikContextStorage:
    def __init__(self):
        # Context variables for isolation
        self._current_trace_data_context: ContextVar[Optional[TraceData]]
        self._spans_data_stack_context: ContextVar[Tuple[SpanData, ...]]

    def set_trace_data(self, trace_data: TraceData) -> None:
        """Set current trace in context"""

    def add_span_data(self, span_data: SpanData) -> None:
        """Push span onto stack"""

    def pop_span_data(self) -> Optional[SpanData]:
        """Pop span from stack"""

    def top_span_data(self) -> Optional[SpanData]:
        """Get current span without removing"""
```

**Why contextvars?**
- Automatic isolation across threads
- Works with async/await
- No manual cleanup needed
- Thread-safe by design

#### 2. Streamer (`opik/message_processing/streamer.py`)

Routes messages to appropriate handlers (queue, batch, or upload).

```python
class Streamer:
    def __init__(
        self,
        queue: MessageQueue,
        queue_consumers: List[QueueConsumer],
        batch_manager: Optional[BatchManager],
        file_upload_manager: FileUploadManager
    ):
        self._message_queue = queue
        self._queue_consumers = queue_consumers
        self._batch_manager = batch_manager
        self._file_upload_manager = file_upload_manager

    def put(self, message: BaseMessage) -> None:
        """Route message based on type"""
        if self._batch_manager and message.supports_batching:
            self._batch_manager.process_message(message)
        elif message.supports_upload:
            self._file_upload_manager.upload(message)
        else:
            self._message_queue.put(message)

    def flush(self, timeout: Optional[float]) -> bool:
        """Wait for all messages to be processed"""

    def close(self, timeout: Optional[int]) -> bool:
        """Stop processing and cleanup"""
```

#### 3. Message Queue (`opik/message_processing/message_queue.py`)

Thread-safe FIFO queue with backpressure handling.

```python
class MessageQueue(Generic[T]):
    def __init__(self, max_length: Optional[int] = None):
        self._queue: queue.Queue[T] = queue.Queue()
        self._max_length = max_length

    def put(self, item: T) -> None:
        """Add message, discard oldest if full"""
        if self._max_length and self._queue.qsize() >= self._max_length:
            # Remove oldest message
            try:
                self._queue.get_nowait()
            except queue.Empty:
                pass
        self._queue.put(item)

    def get(self, timeout: float) -> Optional[T]:
        """Get next message"""
        return self._queue.get(timeout=timeout)

    def empty(self) -> bool:
        """Check if queue is empty"""
        return self._queue.empty()
```

#### 4. Queue Consumer (`opik/message_processing/queue_consumer.py`)

Worker thread that processes messages from the queue.

```python
class QueueConsumer(threading.Thread):
    def __init__(
        self,
        queue: MessageQueue,
        message_processor: MessageProcessor,
        name: Optional[str] = None
    ):
        super().__init__(daemon=True, name=name)
        self._message_queue = queue
        self._message_processor = message_processor
        self.next_message_time = 0.0  # For rate limiting

    def run(self) -> None:
        """Main worker loop"""
        while not self._processing_stopped:
            self._loop()

    def _loop(self) -> None:
        """Process one message"""
        # Check rate limiting
        if time.monotonic() < self.next_message_time:
            time.sleep(SLEEP_INTERVAL)
            return

        # Get and process message
        try:
            message = self._message_queue.get(timeout=SLEEP_INTERVAL)
            if message and message.delivery_time <= time.monotonic():
                self._message_processor.process(message)
        except RateLimitError as e:
            # Re-queue message with delay
            self.next_message_time = time.monotonic() + e.retry_after
            self._message_queue.put(message)
```

#### 5. Message Processor (`opik/message_processing/message_processors.py`)

Maps message types to REST API handlers.

```python
class OpikMessageProcessor(BaseMessageProcessor):
    def __init__(self, rest_client: OpikApi):
        self._rest_client = rest_client

        # Map message types to handlers
        self._handlers: Dict[Type, MessageHandler] = {
            CreateTraceMessage: self._process_create_trace_message,
            CreateSpanMessage: self._process_create_span_message,
            UpdateTraceMessage: self._process_update_trace_message,
            UpdateSpanMessage: self._process_update_span_message,
            AddTraceFeedbackScoresBatchMessage: self._process_feedback_scores,
            CreateSpansBatchMessage: self._process_create_spans_batch,
            CreateTraceBatchMessage: self._process_create_traces_batch,
            # ... more handlers
        }

    def process(self, message: BaseMessage) -> None:
        """Process message by calling appropriate handler"""
        handler = self._handlers.get(type(message))
        if handler:
            try:
                handler(message)
            except ApiError as e:
                # Handle specific error cases
                if e.status_code == 409:
                    return  # Duplicate, ignore
                elif e.status_code == 429:
                    raise RateLimitError(e)
                else:
                    LOGGER.error(f"Failed to process message: {e}")
```

## Data Flow

### Complete Trace Creation Flow

Let's follow a trace creation from user code to backend in detail.

#### Step 1: User Creates Trace

```python
import opik

client = opik.Opik(project_name="my_project")
trace = client.trace(
    name="my_trace",
    input={"query": "test"},
    metadata={"version": "1.0"}
)
```

#### Step 2: API Layer - Message Creation

```python
# In opik_client.py: Opik.trace()

def trace(self, name: str, input: dict, metadata: dict, **kwargs):
    # 1. Generate ID if not provided
    trace_id = kwargs.get("id") or id_helpers.generate_id()

    # 2. Validate inputs
    validation.validate_trace_parameters(name, input, metadata)

    # 3. Create TraceData
    trace_data = TraceData(
        id=trace_id,
        name=name,
        input=input,
        metadata=metadata,
        project_name=self._project_name,
        start_time=datetime_helpers.now()
    )

    # 4. Create message
    message = CreateTraceMessage(
        trace_id=trace_data.id,
        name=trace_data.name,
        input=trace_data.input,
        metadata=trace_data.metadata,
        project_name=trace_data.project_name,
        start_time=trace_data.start_time,
        # ... other fields
    )

    # 5. Send to streamer (non-blocking!)
    self._streamer.put(message)

    # 6. Return immediately
    return trace_id
```

**Key Point**: User code continues immediately. Message processing happens asynchronously.

#### Step 3: Message Processing Layer - Routing

```python
# In streamer.py: Streamer.put()

def put(self, message: BaseMessage) -> None:
   with self._lock:
      # Check if draining
      if self._drain:
         return

      # do embedded attachments pre-processing first (MUST ALWAYS BE DONE FIRST)
      preprocessed_message = self._attachments_preprocessor.preprocess(
        message
      )

      # do batching pre-processing third
      preprocessed_message = self._batch_preprocessor.preprocess(
        preprocessed_message
      )

      # Route to queue
      if not self._message_queue.accept_put_without_discarding():
         LOGGER.warning("Queue full, discarding oldest message")
      self._message_queue.put(preprocessed_message)
```

**Decision Tree**:
```
Message arrives
    │
    ├─► Supports batching? ──Yes──► BatchManager
    ├─► Has file upload? ───Yes──► FileUploadManager
    └─► Default ─────────────────► MessageQueue
```

#### Step 4: Batching (Optional)

If batching is enabled, certain message types accumulate before sending.

**Messages that support batching** (current implementation):
- `CreateSpanMessage` → batched into `CreateSpansBatchMessage`
- `CreateTraceMessage` → batched into `CreateTraceBatchMessage`
- `AddTraceFeedbackScoresBatchMessage` → already a batch message
- `AddSpanFeedbackScoresBatchMessage` → already a batch message
- `CreateExperimentItemsBatchMessage` → already a batch message

**Messages that don't support batching** (sent individually):
- `UpdateSpanMessage` - Updates sent immediately
- `UpdateTraceMessage` - Updates sent immediately
- Other message types

```python
# In batch_manager.py

class BatchManager:
    def process_message(self, message: BaseMessage) -> None:
        # Find or create batcher for this message type
        batcher = self._get_or_create_batcher(type(message))

        # Add to batch
        batcher.add(message)

        # Check if should flush
        if batcher.should_flush():
            self._flush_batcher(batcher)

    def _flush_batcher(self, batcher: BaseBatcher) -> None:
        # Create batch message
        batch_message = batcher.create_batch_message()

        # Send to queue
        self._message_queue.put(batch_message)

        # Clear batcher
        batcher.clear()
```

**Flush Triggers**:
1. **Time-based**: Periodic timer (e.g., every 1 second)
2. **Size-based**: Batch reaches size limit (e.g., 100 messages)
3. **Memory-based**: Batch reaches memory limit (e.g., 50MB)
4. **Manual**: User calls `flush()`
5. **Shutdown**: Manager stopping

#### Step 5: Queue and Consumer

```python
# Queue consumer pulls message
message = self._message_queue.get(timeout=0.1)

# Check delivery time (for rate limiting)
if message.delivery_time > time.monotonic():
    # Re-queue for later
    self._message_queue.put(message)
    return

# Process message
self._message_processor.process(message)
```

#### Step 6: Message Processing - Handler Execution

```python
# In message_processors.py

def _process_create_trace_message(
    self, message: CreateTraceMessage
) -> None:
    # Map message to REST request
    trace_write = trace_write.TraceWrite(
        id=message.trace_id,
        name=message.name,
        input=message.input,
        metadata=message.metadata,
        project_name=message.project_name,
        start_time=message.start_time.isoformat(),
        # ... more fields
    )

    # Make REST API call
    self._rest_client.traces.create_trace(
        request=trace_write
    )
```

#### Step 7: REST API Layer

```python
# In rest_api (auto-generated)

def create_trace(self, request: TraceWrite) -> TracePublic:
    # Serialize request
    json_data = request.dict(exclude_none=True)

    # Make HTTP request
    response = self._client.post(
        "/v1/traces",
        json=json_data,
        headers={"Authorization": f"Bearer {self._api_key}"}
    )

    # Handle response
    if response.status_code == 201:
        return TracePublic.parse_obj(response.json())
    else:
        raise ApiError(response)
```

#### Step 8: Backend Storage

Backend receives request and stores in database:
- MySQL: Metadata, relationships
- ClickHouse: Time-series data, spans

### Decorator Data Flow

The `@opik.track` decorator provides automatic tracing. Here's the complete flow:

#### Initial Setup

```python
@opik.track
def my_function(x: int) -> int:
    return x * 2

# Behind the scenes
_decorator = OpikTrackDecorator()
my_function = _decorator.track(my_function)
```

#### Execution Flow

```python
# User calls function
result = my_function(5)
```

**Step-by-Step Execution**:

```
1. Decorator intercepts call
   ↓
2. Check if tracing is active
   ↓
3. Extract function inputs
   │
   ├─► Arguments: (5,)
   ├─► Keyword arguments: {}
   └─► Combined: {"x": 5}
   ↓
4. Check for existing trace in context
   │
   ├─► No trace? Create TraceData
   │   │
   │   ├─► Generate trace_id
   │   ├─► Set start_time
   │   ├─► Store in context: context_storage.set_trace_data()
   │   │
   │   Context state: trace_stack = [TraceData]
   │
   └─► Trace exists? Reuse
   ↓
5. Create SpanData
   │
   ├─► Generate span_id
   ├─► Get trace_id from context
   ├─► Check for parent span
   │   │
   │   ├─► Parent exists? Set parent_span_id
   │   └─► No parent? parent_span_id = None
   │
   ├─► Capture input: {"x": 5}
   ├─► Set start_time
   ├─► Set type, name, metadata, tags
   │
   Context state: span_stack = [..., SpanData]
   ↓
6. Push span to context
   context_storage.add_span_data(span_data)
   ↓
7. Execute wrapped function
   │
   ├─► try:
   │       result = my_function(5)  # Original function
   │   except Exception as e:
   │       error_info = collect_error_info(e)
   │       span_data.error_info = error_info
   │       raise  # Re-raise to user
   │
   └─► Returns: 10
   ↓
8. Capture output
   │
   ├─► If capture_output=True:
   │   │
   │   ├─► Output is dict? Use as-is
   │   └─► Not dict? Wrap: {"output": 10}
   │
   └─► span_data.output = {"output": 10}
   ↓
9. Set end_time
   span_data.end_time = datetime_helpers.now()
   ↓
10. Pop span from context
    span_data = context_storage.pop_span_data()

    Context state: span_stack = [...]
    ↓
11. Send span to backend
    │
    ├─► Create CreateSpanMessage from span_data
    ├─► streamer.put(message)
    │
    [Async processing begins]
    ↓
12. Check if top-level function
    │
    ├─► span_stack is empty?
    │   │
    │   ├─► Yes: Also send trace
    │   │   │
    │   │   ├─► trace_data = context_storage.pop_trace_data()
    │   │   ├─► Set trace end_time
    │   │   ├─► Create CreateTraceMessage
    │   │   └─► streamer.put(message)
    │   │
    │   Context state: trace_stack = [], span_stack = []
    │
    └─► No: Leave trace in context (more spans coming)
    ↓
13. Return result to user
    return 10
```

#### Nested Function Flow

```python
@opik.track
def outer(x):
    result = inner(x)
    return result * 2

@opik.track
def inner(x):
    return x + 1

# Call
outer(5)
```

**Context State Timeline**:

```
Time  │ Action                  │ Trace Stack    │ Span Stack
──────┼─────────────────────────┼────────────────┼─────────────────
T0    │ outer() called          │ []             │ []
T1    │ Create trace            │ [Trace-A]      │ []
T2    │ Create span-outer       │ [Trace-A]      │ [Span-1]
T3    │ inner() called          │ [Trace-A]      │ [Span-1]
T4    │ Reuse trace             │ [Trace-A]      │ [Span-1]
T5    │ Create span-inner       │ [Trace-A]      │ [Span-1, Span-2]
      │ (parent=Span-1)         │                │
T6    │ inner() executes        │ [Trace-A]      │ [Span-1, Span-2]
T7    │ inner() returns         │ [Trace-A]      │ [Span-1, Span-2]
T8    │ Pop span-inner          │ [Trace-A]      │ [Span-1]
T9    │ Send Span-2 message     │ [Trace-A]      │ [Span-1]
T10   │ outer() continues       │ [Trace-A]      │ [Span-1]
T11   │ outer() returns         │ [Trace-A]      │ [Span-1]
T12   │ Pop span-outer          │ [Trace-A]      │ []
T13   │ Send Span-1 message     │ [Trace-A]      │ []
T14   │ Stack empty! Send trace │ []             │ []
T15   │ Pop trace, send message │ []             │ []
```

**Resulting Tree**:
```
Trace-A
  └─ Span-1 (outer)
      └─ Span-2 (inner)
```

## Message Processing Deep Dive

### Message Types

All messages inherit from `BaseMessage`:

```python
class BaseMessage:
    delivery_time: float = 0.0  # For rate limiting

    def dict(self) -> Dict[str, Any]:
        """Convert to dictionary"""

# Core message types
class CreateTraceMessage(BaseMessage):
    trace_id: str
    name: Optional[str]
    input: Optional[Dict]
    output: Optional[Dict]
    metadata: Optional[Dict]
    tags: Optional[List[str]]
    start_time: datetime
    end_time: Optional[datetime]
    # ... more fields

class CreateSpanMessage(BaseMessage):
    span_id: str
    trace_id: str
    parent_span_id: Optional[str]
    name: Optional[str]
    type: str
    input: Optional[Dict]
    output: Optional[Dict]
    usage: Optional[Dict]
    model: Optional[str]
    provider: Optional[str]
    # ... more fields

class UpdateSpanMessage(BaseMessage):
    span_id: str
    # Only fields to update

class UpdateTraceMessage(BaseMessage):
    trace_id: str
    # Only fields to update

# Batch message types
class CreateSpansBatchMessage(BaseMessage):
    spans: List[CreateSpanMessage]

class CreateTraceBatchMessage(BaseMessage):
    traces: List[CreateTraceMessage]

# Feedback messages
class AddTraceFeedbackScoresBatchMessage(BaseMessage):
    feedback_scores: List[FeedbackScoreDict]

class AddSpanFeedbackScoresBatchMessage(BaseMessage):
    feedback_scores: List[FeedbackScoreDict]

# Experiment item messages
class ExperimentItemMessage(BaseMessage):
    id: str
    experiment_id: str
    trace_id: str
    dataset_item_id: str

class CreateExperimentItemsBatchMessage(BaseMessage):
    batch: List[ExperimentItemMessage]
```

### Message Routing Logic

```python
def put(self, message: BaseMessage) -> None:
   """Route message to appropriate handler"""

   # 1. Check if draining
   if self._drain:
      return  # Drop message (shutdown in progress)

   # 2. Check batching support
   if (
           self._batch_manager is not None
           and self._batch_manager.message_supports_batching(message)
   ):
      # Messages that support batching:
      # - CreateSpanMessage
      # - CreateTraceMessage
      # - Feedback score messages (always batched)
      self._batch_manager.process_message(message)
      return

   # 3. Check file upload support
   if base_upload_manager.message_supports_upload(message):
      # Messages with attachments
      # - Uploaded to S3 first
      # - Then regular message sent with S3 URLs
      self._upload_preprocessor.upload(message)
      return

   # 4. Default: Add to queue
   if not self._message_queue.accept_put_without_discarding():
      # Queue is full
      LOGGER.warning("Queue full, discarding oldest message")

   self._message_queue.put(message)
```

### Attachment Extraction Preprocessing

Before messages reach the queue or batch manager, the SDK can optionally preprocess them to extract embedded base64-encoded attachments (images, PDFs, etc.) from trace/span input, output, and metadata fields.

#### Preprocessing Pipeline

```python
# In streamer.py: Streamer.__init__()

def __init__(self, ...):
    # Create preprocessing pipeline
    self._message_preprocessors = []

    # 1. Attachments preprocessor (conditionally wraps messages)
    attachments_preprocessor = AttachmentsPreprocessor(enabled=True)
    self._message_preprocessors.append(attachments_preprocessor)

    # 2. Batching preprocessor (groups batchable messages)
    batching_preprocessor = BatchingPreprocessor(...)
    self._message_preprocessors.append(batching_preprocessor)

# Messages flow through preprocessors before routing
def put(self, message: BaseMessage) -> None:
    # Apply preprocessors in order
    for preprocessor in self._message_preprocessors:
        message = preprocessor.preprocess(message)

    # Then route to queue/batch/upload
    self._route_message(message)
```

#### AttachmentsPreprocessor: Selective Wrapping

The `AttachmentsPreprocessor` decides which messages need attachment extraction:

```python
class AttachmentsPreprocessor(MessagePreprocessor):
    def preprocess(self, message: BaseMessage) -> BaseMessage:
        """
        Wraps messages that need attachment extraction in AttachmentSupportingMessage.

        Only wraps if:
        1. Update messages (UpdateSpanMessage, UpdateTraceMessage) - always process
        2. Create messages with end_time set - final data, ready to extract

        Does NOT wrap:
        - Create messages without end_time - in-progress operations
        """
        if _has_potential_content_with_attachments(message):
            return AttachmentSupportingMessage(message)
        return message

def _has_potential_content_with_attachments(message: BaseMessage) -> bool:
    # Check if it's an Update message - always process these
    if isinstance(message, (UpdateSpanMessage, UpdateTraceMessage)):
        return _message_has_field_of_interest_set(message)

    # Check if it's a Create message with end_time set - only process these
    if isinstance(message, (CreateSpanMessage, CreateTraceMessage)):
        if message.end_time is not None:
            return _message_has_field_of_interest_set(message)
        return False

    return False

def _message_has_field_of_interest_set(message) -> bool:
    """Check if message has input, output, or metadata fields set"""
    return (
        message.input is not None
        or message.output is not None
        or message.metadata is not None
    )
```

**Key Design Decision**: Why skip Create messages without `end_time`?

```
Trace/Span Lifecycle:
    │
    ├─► Create (no end_time) ──► In-progress operation
    │   │                         - May be updated multiple times
    │   │                         - Extracting attachments now is wasteful
    │   │                         - Will extract on final update anyway
    │   │
    │   └─► Update (sets end_time) ──► Completed operation
    │       │                           - Contains final data
    │       │                           - Extract attachments now ✓
    │       │
    │       └─► Backend storage
    │
    └─► Create (with end_time) ──► Synchronous/completed operation
        │                          - Contains final data upfront
        │                          - Extract attachments now ✓
        │
        └─► Backend storage
```

**Performance Impact**:
- For 1000 concurrent traces: 50% reduction in attachment processing
- Avoids duplicate extraction (create + update)
- Only processes messages with final data

#### AttachmentSupportingMessage Wrapper

```python
class AttachmentSupportingMessage(BaseMessage):
    """
    Wrapper that signals a message needs attachment extraction.

    The wrapped message is processed by AttachmentsExtractionProcessor
    before being sent to backend.
    """
    original_message: Union[
        CreateSpanMessage,
        UpdateSpanMessage,
        CreateTraceMessage,
        UpdateTraceMessage
    ]
```

#### Attachment Extraction Flow

```
1. Message arrives at Streamer
   │
   ▼
2. AttachmentsPreprocessor.preprocess()
   │
   ├─► Should extract? (Update or Create with end_time)
   │   │
   │   ├─► Yes: Wrap in AttachmentSupportingMessage
   │   │   │
   │   │   └─► Route to AttachmentsExtractionProcessor
   │   │       │
   │   │       ├─► Extract base64 attachments from input/output/metadata
   │   │       │   - Handles nested dictionaries and lists
   │   │       │   - Supports PNG, JPEG, PDF, GIF, WebP, SVG, JSON
   │   │       │   - Replaces base64 with placeholder: [filename.png]
   │   │       │
   │   │       ├─► Upload attachments to S3
   │   │       │
   │   │       └─► Forward original message (now sanitized) to queue
   │   │
   │   └─► No: Pass through unchanged
   │       │
   │       └─► Route directly to queue/batch/upload
   │
   ▼
3. Message continues through normal pipeline
```

#### AttachmentsExtractor: Nested Structure Support

The extractor recursively processes nested data structures:

```python
class AttachmentsExtractor:
    def extract_and_replace(
        self,
        data: Dict[str, Any],
        entity_type: Literal["span", "trace"],
        entity_id: str,
        project_name: str,
        context: Literal["input", "output", "metadata"],
    ) -> List[AttachmentWithContext]:
        """
        Extract attachments from data and replace with placeholders.

        Handles:
        - Simple strings: {"image": "data:image/png;base64,..."}
        - Nested dicts: {"user": {"avatar": "data:image/png;base64,..."}}
        - Lists: {"images": ["data:image/png;base64,...", "data:image/jpeg;base64,..."]}
        - Mixed: {"messages": [{"role": "user", "content": [{"image": "data:..."}]}]}
        """
        attachments = []
        for key, value in data.items():
            result = self._try_extract_attachments(value, context)
            if result.attachments:
                data[key] = result.sanitized_data
                attachments.extend(...)
        return attachments

    def _try_extract_attachments(self, data: Any, context: str) -> ExtractionResult:
        """Recursively extract from any data type"""
        if isinstance(data, str):
            return self._extract_from_string(data, context)
        elif isinstance(data, dict):
            return self._extract_from_dict(data, context)
        elif isinstance(data, list):
            return self._extract_from_list(data, context)
        else:
            # int, bool, None, etc. - return as-is
            return ExtractionResult(attachments=[], sanitized_data=data)
```

**Example**: Nested structure extraction

```python
# Input
trace_input = {
    "messages": [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "What's in this image?"},
                {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0K..."}}
            ]
        }
    ]
}

# After extraction
trace_input = {
    "messages": [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "What's in this image?"},
                {"type": "image_url", "image_url": {"url": "[input-attachment-abc123.png]"}}
            ]
        }
    ]
}

# Extracted attachment uploaded to S3
# Attachment: {file_name: "input-attachment-abc123.png", content_type: "image/png", ...}
```

**Supported Formats**:
- Images: PNG, JPEG, GIF, WebP, SVG
- Documents: PDF, JSON
- Pattern: `data:<mime-type>;base64,<base64-data>`

#### Integration with Message Pipeline

```
User Code
   │
   ▼
CreateSpanMessage(
    input={"image": "data:image/png;base64,..."},
    end_time=now()  # ← Key: end_time is set
)
   │
   ▼
Streamer.put()
   │
   ├─► AttachmentsPreprocessor
   │   │
   │   └─► Has end_time? YES → Wrap in AttachmentSupportingMessage
   │
   ▼
Route to AttachmentsExtractionProcessor
   │
   ├─► Extract attachments
   │   - Find base64 data in input
   │   - Decode and identify type (PNG)
   │   - Save to temporary file
   │   - Replace with placeholder
   │
   ├─► Upload to S3
   │   - CreateAttachmentMessage → S3
   │
   └─► Forward sanitized CreateSpanMessage
       - input={"image": "[input-attachment-123.png]"}
       │
       ▼
   BatchManager (or Queue)
       │
       ▼
   Backend storage
```

### Consumer Processing Loop

```python
class QueueConsumer(threading.Thread):
    def run(self) -> None:
        """Main worker loop"""
        while not self._processing_stopped:
            self._loop()

    def _loop(self) -> None:
        """Process one message"""

        # 1. Check rate limiting
        now = time.monotonic()
        if now < self.next_message_time:
            self.idling = False
            time.sleep(SLEEP_BETWEEN_LOOP_ITERATIONS)
            return

        # 2. Get message from queue
        try:
            self.idling = True
            message = self._message_queue.get(
                timeout=SLEEP_BETWEEN_LOOP_ITERATIONS
            )
            self.idling = False

            if message is None:
                return

            # 3. Check delivery time
            if message.delivery_time <= now:
                # Ready to process
                self._message_processor.process(message)
            else:
                # Not ready yet, re-queue
                self._push_message_back(message)

        except Empty:
            time.sleep(SLEEP_BETWEEN_LOOP_ITERATIONS)

        except OpikCloudRequestsRateLimited as e:
            # 4. Handle rate limiting
            LOGGER.info(
                "Rate limited, retrying in %s seconds",
                e.retry_after
            )

            # Update next processing time
            self.next_message_time = now + e.retry_after

            # Re-queue message with delay
            if message is not None:
                message.delivery_time = self.next_message_time
                self._push_message_back(message)

        except Exception as ex:
            LOGGER.error("Unexpected error: %s", ex, exc_info=ex)
```

### Error Handling in Message Processing

```python
def process(self, message: BaseMessage) -> None:
    """Process message with comprehensive error handling"""

    message_type = type(message)
    handler = self._handlers.get(message_type)

    if handler is None:
        LOGGER.debug("Unknown message type: %s", message_type.__name__)
        return

    try:
        # Execute handler
        handler(message)

    except ApiError as exception:
        # 1. Handle duplicate requests
        if exception.status_code == 409:
            # Retry mechanism sent duplicate, ignore
            return

        # 2. Handle rate limiting
        elif exception.status_code == 429:
            # Extract retry-after from headers
            if exception.headers is not None:
                rate_limiter = rate_limit.parse_rate_limit(exception.headers)
                if rate_limiter is not None:
                    raise OpikCloudRequestsRateLimited(
                        headers=exception.headers,
                        retry_after=rate_limiter.retry_after()
                    )

        # 3. Other API errors
        LOGGER.error(
            "Failed to process %s: %s",
            message_type.__name__,
            str(exception),
            extra={"error_tracking_extra": error_info}
        )

    except RetryError as retry_error:
        # 4. Retry exhausted
        cause = retry_error.last_attempt.exception()
        LOGGER.error(
            "Retries exhausted for %s: %s",
            message_type.__name__,
            cause
        )
        LOGGER.warning("Check Opik configuration")

    except ValidationError as validation_error:
        # 5. Data validation failed
        LOGGER.error(
            "Validation failed for %s: %s",
            message_type.__name__,
            validation_error
        )
```

## Batching System

### Why Batching?

Batching reduces overhead by:
1. **Fewer HTTP requests**: 100 spans → 1 request
2. **Lower latency**: Amortized network cost
3. **Better throughput**: More efficient use of connections
4. **Reduced backend load**: Fewer requests to process

### Batch Manager Architecture

```python
class BatchManager:
    def __init__(self, message_queue: MessageQueue):
        self._message_queue = message_queue
        self._batchers: Dict[Type, BaseBatcher] = {}
        self._lock = threading.RLock()

        # Timer for periodic flushing
        self._timer: Optional[threading.Timer] = None
        self._flush_interval = 1.0  # seconds

    def start(self) -> None:
        """Start periodic flushing"""
        self._schedule_flush()

    def _schedule_flush(self) -> None:
        """Schedule next flush"""
        self._timer = threading.Timer(
            self._flush_interval,
            self._periodic_flush
        )
        self._timer.daemon = True
        self._timer.start()

    def _periodic_flush(self) -> None:
        """Flush all batchers periodically"""
        self.flush()
        if not self._stopped:
            self._schedule_flush()
```

### Batcher Types

#### Spans Batcher

```python
class SpansBatcher(BaseBatcher):
    def __init__(self, max_batch_size: int = 100, max_memory_mb: int = 50):
        self._messages: List[CreateSpanMessage] = []
        self._max_batch_size = max_batch_size
        self._max_memory_bytes = max_memory_mb * 1024 * 1024
        self._current_memory = 0

    def add(self, message: CreateSpanMessage) -> None:
        """Add span to batch"""
        self._messages.append(message)
        self._current_memory += self._estimate_size(message)

    def should_flush(self) -> bool:
        """Check if batch should be flushed"""
        return (
            len(self._messages) >= self._max_batch_size
            or self._current_memory >= self._max_memory_bytes
        )

    def create_batch_message(self) -> CreateSpansBatchMessage:
        """Create batch message from accumulated spans"""
        return CreateSpansBatchMessage(
            spans=self._messages.copy()
        )

    def clear(self) -> None:
        """Clear batch"""
        self._messages.clear()
        self._current_memory = 0

    def _estimate_size(self, message: CreateSpanMessage) -> int:
        """Estimate message size in bytes"""
        # Rough estimation based on serialized size
        data = message.dict()
        return len(json.dumps(data).encode('utf-8'))
```

### Batch Processing Flow

```
Individual messages arrive
    │
    ▼
┌────────────────────────────────────┐
│ BatchManager.process_message()    │
│                                    │
│ 1. Find batcher for message type  │
│ 2. Add to batch                   │
│ 3. Check flush conditions         │
└────────┬───────────────────────────┘
         │
         ▼
    Should flush?
         │
    ┌────┴────┐
    │         │
   No        Yes
    │         │
    │         ▼
    │    ┌──────────────────────────┐
    │    │ Create batch message     │
    │    │ (e.g., 100 spans)       │
    │    └───┬──────────────────────┘
    │        │
    │        ▼
    │    ┌──────────────────────────┐
    │    │ Add to MessageQueue      │
    │    └───┬──────────────────────┘
    │        │
    │        ▼
    │    ┌──────────────────────────┐
    │    │ Clear batcher            │
    │    └──────────────────────────┘
    │
    └──► Continue accumulating
```

### Flush Triggers

#### 1. Time-Based Flush

```python
def _periodic_flush(self) -> None:
    """Called every flush_interval seconds"""
    with self._lock:
        for batcher in self._batchers.values():
            if not batcher.is_empty():
                batch_message = batcher.create_batch_message()
                self._message_queue.put(batch_message)
                batcher.clear()
```

**Example**: Every 1 second, flush all non-empty batchers.

#### 2. Size-Based Flush

```python
def process_message(self, message: BaseMessage) -> None:
    """Add message to batch, flush if size limit reached"""
    batcher = self._get_or_create_batcher(type(message))
    batcher.add(message)

    if batcher.should_flush():
        batch_message = batcher.create_batch_message()
        self._message_queue.put(batch_message)
        batcher.clear()
```

**Example**: Batch reaches 100 messages.

#### 3. Memory-Based Flush

```python
def should_flush(self) -> bool:
    """Check memory threshold"""
    return (
        len(self._messages) >= self._max_batch_size
        or self._current_memory >= self._max_memory_bytes
    )
```

**Example**: Batch reaches 50MB.

#### 4. Manual Flush

```python
def flush(self) -> None:
    """User-triggered flush"""
    client.flush(timeout=30)

    # Internally:
    # 1. Flush all batchers
    batch_manager.flush()

    # 2. Wait for queue to empty
    while not message_queue.empty() and not timeout:
        time.sleep(0.1)
```

#### 5. Shutdown Flush

```python
def stop(self) -> None:
    """Flush on shutdown"""
    self._stopped = True

    # Cancel timer
    if self._timer:
        self._timer.cancel()

    # Flush all remaining messages
    self.flush()
```

### Batch Message Processing

```python
def _process_create_spans_batch_message(
    self, message: CreateSpansBatchMessage
) -> None:
    """Process batch of spans"""

    # Split into chunks if too large
    chunks = sequence_splitter.split_into_chunks(
        message.spans,
        max_chunk_size=self._batch_memory_limit_mb
    )

    for chunk in chunks:
        # Convert to REST request format
        span_writes = [
            span_write.SpanWrite.from_message(span_msg)
            for span_msg in chunk
        ]

        # Make single API call for all spans
        self._rest_client.spans.create_spans_batch(
            request=span_writes
        )
```

**Benefits**:
- 100 individual spans → 1 API call
- Reduced network overhead
- Better backend performance

## Observability

### Logging

The SDK uses Python's standard logging with structured extra data.

#### Logger Configuration

```python
import logging

# Module-level loggers
LOGGER = logging.getLogger(__name__)

# Logging hierarchy
opik                        # Root logger
├── opik.api_objects       # API objects
├── opik.decorator         # Decorator logic
├── opik.message_processing # Message processing
│   ├── streamer
│   ├── message_processors
│   └── batching
└── opik.evaluation        # Evaluation
```

#### Log Levels

- **DEBUG**: Detailed information for debugging
- **INFO**: General informational messages
- **WARNING**: Warnings (queue full, rate limits)
- **ERROR**: Error conditions (API failures, processing errors)

#### Example Log Messages

```python
# Queue full warning
LOGGER.warning(
    "Queue size limit reached. Message added, oldest discarded."
)

# Rate limiting info
LOGGER.info(
    "Rate limited, retrying in %s seconds, queue size: %d",
    retry_after,
    queue_size
)

# Processing error
LOGGER.error(
    "Failed to process %s: %s",
    message_type.__name__,
    str(exception),
    extra={"error_tracking_extra": error_info}
)

# Configuration warning
LOGGER.warning(
    "Opik may not be configured correctly. "
    "Run 'opik configure' to set up."
)
```

### Error Tracking

The SDK integrates with Sentry for error tracking (opt-in, randomized).

#### Error Filtering

```python
# Only track specific errors
class ErrorLevelCountFilter:
    """Only send ERROR and CRITICAL level events"""
    def __call__(self, event, hint):
        return event['level'] in ['error', 'fatal']

class ResponseStatusCodeFilter:
    """Filter out expected HTTP errors"""
    def __call__(self, event, hint):
        # Don't track 409 (Conflict - duplicate)
        # Don't track 429 (Rate Limit - expected)
        status_code = extract_status_code(event)
        return status_code not in [409, 429]
```

#### Error Context

```python
def _generate_error_tracking_extra(
    exception: Exception,
    message: BaseMessage
) -> Dict[str, Any]:
    """Generate structured error context"""
    return {
        "message_type": type(message).__name__,
        "exception_type": type(exception).__name__,
        "status_code": getattr(exception, 'status_code', None),
        "message_data": message.dict(),
        "timestamp": datetime.now().isoformat()
    }
```

### Performance Metrics

#### Internal Metrics

The SDK tracks internal performance:

```python
# Message queue metrics
queue_size = message_queue.qsize()
queue_full_events = metric_counter["queue_full"]

# Batch metrics
batch_size = len(batcher._messages)
batch_memory = batcher._current_memory
batches_flushed = metric_counter["batches_flushed"]

# Consumer metrics
consumer_idle = consumer.idling
messages_processed = metric_counter["messages_processed"]
processing_errors = metric_counter["processing_errors"]
```

#### User-Facing Metrics

```python
# Flush status
success = client.flush(timeout=30)
if not success:
    LOGGER.warning("Flush timeout, some messages may not be sent")

# Queue info (logged automatically)
LOGGER.info("Queue size: %d messages", queue_size)
```

### Health Checks

```python
from opik.healthcheck import check_health

# Check SDK health
result = check_health()

# Returns:
{
    "backend_reachable": True,
    "authentication_valid": True,
    "project_exists": True,
    "version": "0.1.0",
    "configuration": {...}
}
```

## Performance Considerations

### Memory Usage

#### Message Queue

- **Default**: Unlimited queue size
- **With limit**: Oldest messages discarded when full
- **Trade-off**: Memory vs. data loss

```python
# Unlimited (default)
client = opik.Opik()  # No queue limit

# Limited (for memory-constrained environments)
# Set via config, not directly exposed
```

#### Batching

- **Memory limit**: 50MB per batch by default
- **Monitoring**: Estimated message sizes tracked
- **Auto-flush**: Triggers before memory limit

### CPU Usage

#### Background Threads

- **Queue consumers**: N threads (default: 1)
- **Batch manager**: 1 timer thread
- **File upload**: M threads (default: 5)

**Total**: N + M + 1 background threads

#### Processing Overhead

- **Message creation**: Minimal (dictionary construction)
- **Serialization**: Lazy (only when sending)
- **Batching**: Low overhead (list append)

### Network Usage

#### Without Batching

- **Requests**: 1 per trace/span
- **Typical**: 100-1000 requests/second for busy app

#### With Batching

- **Requests**: 1 per batch
- **Batch size**: 100 spans per request
- **Reduction**: 100x fewer requests

#### Connection Pooling

- **HTTP client**: Reuses connections
- **Max connections**: Configurable (default: 10)

### Latency Impact

#### User Code

```python
# Non-blocking call
trace = client.trace(...)  # Returns trace object immediately (~1μs)

# Decorator overhead
@opik.track
def my_function():
    pass  # Overhead: ~10-100μs per call
```

#### Backend Communication

```python
# Async processing
# User code continues immediately
# Backend calls happen in background

# Flush latency
client.flush(timeout=30)  # Waits for all messages
```

### Best Practices

1. **Use batching** for high-throughput applications
2. **Call flush()** before application exit
3. **Monitor queue size** in logs
4. **Configure timeout** for flush operations
5. **Use decorators** for automatic tracking (lower overhead)
6. **Avoid excessive metadata** (keep traces/spans lightweight)

### Troubleshooting Performance

#### High Memory Usage

```python
# Check message queue size
# If growing unbounded:
# 1. Check backend connectivity
# 2. Check rate limiting
# 3. Reduce tracing frequency
```

#### Slow Flush

```python
# If flush() takes too long:
# 1. Check queue size (too many pending messages)
# 2. Check network latency
# 3. Increase timeout
client.flush(timeout=60)  # Increase timeout
```

#### Message Loss

```python
# If messages not appearing:
# 1. Check backend connectivity
# 2. Verify authentication
# 3. Check for ERROR logs
# 4. Call flush() before exit
```

## Summary

The Opik Python SDK provides:

1. **Simple API**: High-level methods and decorators
2. **Non-blocking**: Asynchronous message processing
3. **Efficient**: Batching and connection pooling
4. **Reliable**: Retry logic and error handling
5. **Observable**: Comprehensive logging and monitoring

For more information, see:
- [Integrations](INTEGRATIONS.md) - LLM framework integrations
- [Evaluation](EVALUATION.md) - Evaluation framework
- [Testing](TESTING.md) - Testing guide
