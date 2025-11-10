# Opik Python SDK: Evaluation Architecture

## Table of Contents

- [Overview](#overview)
- [Evaluation Engine Architecture](#evaluation-engine-architecture)
- [Evaluation Methods](#evaluation-methods)
- [Metrics Architecture](#metrics-architecture)
- [Parallel Execution Model](#parallel-execution-model)
- [Data Flow](#data-flow)

## Overview

The evaluation framework is designed to assess LLM applications through systematic testing. Unlike the tracing components which are optimized for non-blocking operation, the evaluation framework is **synchronous and blocking** - it waits for all tasks and metrics to complete before returning results.

### Design Philosophy

- **Synchronous by design**: Evaluation waits for completion (unlike tracing)
- **Parallel execution**: Uses thread pools for performance
- **Experiment tracking**: Automatic linkage to backend experiments
- **Metric composability**: Mix heuristic and LLM-based metrics
- **Error resilience**: Individual failures don't stop evaluation

### Location

```
opik/evaluation/
├── evaluator.py              # Main evaluate() functions
├── engine/
│   ├── engine.py             # EvaluationEngine core
│   ├── evaluation_tasks_executor.py  # Thread pool execution
│   ├── helpers.py            # Context management
│   └── types.py              # Type definitions
├── threads/
│   ├── evaluator.py          # evaluate_threads()
│   └── evaluation_engine.py  # ThreadsEvaluationEngine
├── metrics/
│   ├── base_metric.py        # BaseMetric interface
│   ├── heuristics/           # Fast, local metrics
│   ├── llm_judges/           # LLM-based metrics
│   └── conversation/         # Multi-turn metrics
└── models/                   # LLM model wrappers
```

## Evaluation Engine Architecture

### Core Components

The evaluation engine is the orchestrator that runs tasks, applies metrics, and logs results.

```
EvaluationEngine
├── Configuration
│   ├── Opik client
│   ├── Experiment reference
│   ├── Scoring metrics
│   ├── Worker count
│   └── Verbosity
│
├── Execution
│   ├── evaluate_llm_tasks()  # Main entry point
│   ├── _evaluate_llm_task()  # Per-item execution
│   └── evaluate_test_cases() # Direct test case eval
│
└── Output
    └── List[TestResult]
```

### EvaluationEngine Class

**Location**: `opik/evaluation/engine/engine.py`

```python
class EvaluationEngine:
    def __init__(
        self,
        client: opik_client.Opik,
        project_name: Optional[str],
        experiment_: experiment.Experiment,
        scoring_metrics: List[base_metric.BaseMetric],
        workers: int,
        verbose: int,
        scoring_key_mapping: Optional[ScoringKeyMappingType],
    ):
        self._client = client
        self._project_name = project_name
        self._experiment = experiment_
        self._workers = workers
        self._verbose = verbose
        self._scoring_metrics = scoring_metrics
        self._scoring_key_mapping = scoring_key_mapping
```

**Key Responsibilities**:
1. **Task execution**: Run user's task function for each dataset item
2. **Trace management**: Create trace context for each task execution
3. **Metric application**: Apply all metrics to task outputs
4. **Result aggregation**: Collect and structure results
5. **Experiment logging**: Log items to experiment

### Evaluation Tasks Executor

**Location**: `opik/evaluation/engine/evaluation_tasks_executor.py`

Thread pool executor that runs evaluation tasks in parallel.

```python
def execute(
    evaluation_tasks: List[EvaluationTask[T]],
    workers: int,
    verbose: int,
    desc: str = "Evaluation",
) -> List[T]:
    """
    Execute evaluation tasks with optional parallelism.

    Args:
        evaluation_tasks: List of callable tasks
        workers: Number of parallel workers
        verbose: Show progress bar
        desc: Progress bar description
    """

    if workers == 1:
        # Sequential execution (no thread pool overhead)
        return [task() for task in tqdm(evaluation_tasks)]

    # Parallel execution
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(task) for task in evaluation_tasks]

        # Collect as they complete (with progress bar)
        return [
            future.result()
            for future in tqdm(as_completed(futures))
        ]
```

**Design Decision**: Thread pool (not process pool or asyncio) because:
- **Network I/O bound**: Tasks spend most time waiting for LLM API responses
- **Thread-safe client**: Sharing Opik client across threads is safe (contextvars provide isolation)
- **Lower overhead**: No serialization costs like multiprocessing
- **Sequential execution per item**: Each thread runs a dataset item's task and all its metrics sequentially, producing clean trace hierarchies without interleaved execution (unlike asyncio where operations can interleave)
- **Async task support**: Works with async task functions via `asyncio_support`

## Evaluation Methods

The SDK provides 4 evaluation methods, each designed for different use cases:

| Method | Dataset | Task Function | Data Source | Logs To |
|--------|---------|---------------|-------------|---------|
| `evaluate()` | ✅ Required | ✅ Required | Executes task on dataset | Experiment items |
| `evaluate_prompt()` | ✅ Required | ❌ Auto-generated | Executes prompt on dataset | Experiment items |
| `evaluate_experiment()` | ❌ From experiment | ❌ Not needed | Existing experiment data | Experiment items (update) |
| `evaluate_threads()` | ❌ Not needed | ❌ Not needed | Existing traces from backend | Traces (feedback scores) |

### 1. evaluate() - Core Evaluation

**Location**: `opik/evaluation/evaluator.py`

```python
def evaluate(
    dataset: Dataset,
    task: LLMTask,
    scoring_metrics: List[BaseMetric],
    experiment_name: Optional[str] = None,
    task_threads: int = 16,
    # ... more parameters
) -> EvaluationResult:
```

**Implementation Flow**:

```
1. Create or get experiment
   ├─► experiment = client.create_experiment(name, dataset_name)
   └─► Links dataset to experiment

2. Create EvaluationEngine
   ├─► Pass client, experiment, metrics
   └─► Configure workers, verbosity

3. Run evaluation
   ├─► engine.evaluate_llm_tasks(dataset, task, ...)
   └─► Returns List[TestResult]

4. Build result
   ├─► Create EvaluationResult
   ├─► Aggregate scores
   └─► Include experiment info

5. Display report (if verbose)
   └─► Print summary statistics
```

**Key Implementation Details**:
- Creates `functools.partial` for each dataset item
- Each partial is an `EvaluationTask` that captures item and task
- Tasks are submitted to thread pool
- Results collected and logged to experiment

### 2. evaluate_prompt() - Prompt Evaluation

**Location**: `opik/evaluation/evaluator.py`

```python
def evaluate_prompt(
    dataset: Dataset,
    messages: List[Dict[str, Any]],  # Prompt template
    model: Union[str, OpikBaseModel],
    scoring_metrics: List[BaseMetric],
    # ... more parameters
) -> EvaluationResult:
```

**Implementation Strategy**:

```
1. Build prompt template
   ├─► Parse messages for {{variables}}
   └─► Create prompt_template.PromptTemplate

2. Create model wrapper
   ├─► If string: models_factory.create_model(model_name)
   └─► If OpikBaseModel: use directly

3. Build task function
   ├─► _build_prompt_evaluation_task(model, messages)
   └─► Returns function that:
       ├─► Formats prompt with dataset item values
       ├─► Calls model.generate()
       └─► Returns formatted output

4. Delegate to evaluate()
   └─► evaluate(dataset, task=generated_task, ...)
```

**Internally created task**:
```python
def _prompt_evaluation_task(dataset_item):
    # Format prompt with item values
    formatted_messages = prompt_template.format(
        messages,
        **dataset_item
    )

    # Call model
    response = model.generate(input=formatted_messages)

    # Return for scoring
    return {
        "input": formatted_messages,
        "output": response,
        **dataset_item  # Include other fields
    }
```

### 3. evaluate_experiment() - Re-evaluation

**Location**: `opik/evaluation/evaluator.py`

```python
def evaluate_experiment(
    experiment_name: str,
    scoring_metrics: List[BaseMetric],
    # ... more parameters
) -> EvaluationResult:
```

**Implementation Strategy**:

```
1. Fetch experiment items
   ├─► client.get_experiment_by_name(experiment_name)
   └─► experiment.get_experiment_items()

2. Convert to TestCase objects
   ├─► Each item becomes TestCase
   ├─► Contains: id, input, output, reference, trace_id
   └─► No task execution needed (data already exists)

3. Apply metrics to existing data
   ├─► engine.evaluate_test_cases(test_cases)
   └─► Metrics score existing outputs

4. Log new scores
   └─► Update experiment items with new feedback scores
```

**Key Difference**: No task execution, only metric application on existing data.

### 4. evaluate_threads() - Conversation Evaluation

**Location**: `opik/evaluation/threads/evaluator.py`

```python
def evaluate_threads(
    project_name: str,
    filter_string: Optional[str],
    metrics: List[ConversationThreadMetric],
    trace_input_transform: Callable,
    trace_output_transform: Callable,
    # ... more parameters
) -> ThreadsEvaluationResult:
```

**Key Difference from Other Evaluation Methods**:

Unlike `evaluate()`, `evaluate_prompt()`, and `evaluate_experiment()`, this method:
- ❌ **No dataset required**: Works on existing production traces
- ❌ **No task function required**: No new execution, evaluates historical data
- ✅ **Fetches existing traces**: Pulls traces from backend based on filter
- ✅ **Logs to traces directly**: Feedback scores attached to original traces (not experiment items)

**Use Case**: Evaluate multi-turn conversations that already happened in production.

**Implementation Strategy**:

```
1. Fetch threads from backend
   ├─► threads_client.search_threads(project_name, filter_string)
   ├─► Uses OQL filter to select specific threads
   └─► Returns List[TraceThread] (existing production data)

2. Fetch traces for each thread from backend
   ├─► For each thread:
   │   └─► client.search_traces(thread_id=thread.id)
   ├─► Pulls actual conversation traces that already exist
   └─► Limit: max_traces_per_thread (default: 1000)

3. Convert traces to conversation format
   ├─► For each trace (represents one conversation turn):
   │   ├─► Apply trace_input_transform → extract user message
   │   ├─► Apply trace_output_transform → extract assistant response
   │   └─► Build {"role": "user/assistant", "content": "..."}
   └─► conversation: List[Turn] (full dialogue history)

4. Apply conversation metrics
   ├─► ThreadsEvaluationEngine executes in parallel
   ├─► Each metric receives full conversation
   └─► Returns List[ScoreResult] per thread

5. Log feedback scores back to backend
   ├─► threads_client.log_feedback_scores_to_thread(thread_id, scores)
   └─► Scores attached to original thread (visible in UI)
```

**Architecture**:

```
ThreadsEvaluationEngine
    │
    ├─► Fetch threads from project
    │
    ├─► For each thread:
    │   │
    │   ├─► Fetch all traces
    │   │
    │   ├─► Transform to conversation format:
    │   │   ├─► Extract user messages (input_transform)
    │   │   └─► Extract assistant messages (output_transform)
    │   │
    │   ├─► Apply conversation metrics
    │   │
    │   └─► Log scores to thread
    │
    └─► Return ThreadsEvaluationResult
```

**Why Transform Functions are Needed**:

Different frameworks structure trace data differently:

```python
# LangChain might store:
trace.input = {"messages": [{"role": "user", "content": "Hi"}]}

# Custom app might store:
trace.input = {"user_query": "Hi", "session_id": "123"}

# Transform extracts the actual user message:
trace_input_transform = lambda x: x["user_query"]
trace_output_transform = lambda x: x["response"]
```

## Metrics Architecture

### BaseMetric Interface

All metrics extend `BaseMetric`:

**Location**: `opik/evaluation/metrics/base_metric.py`

```python
class BaseMetric(abc.ABC):
    name: str

    @abc.abstractmethod
    def score(self, **kwargs) -> ScoreResult:
        """
        Compute metric score.

        Must raise MetricComputationError on failure.
        Must not hide or mask missing data.
        """
        pass
```

**Metric Contract**:
- Must implement `score()` method
- Must return `ScoreResult` (value, name, reason)
- Must raise `MetricComputationError` on failure (not hide errors)
- Can accept any kwargs (flexible interface)

### Metric Type Implementation

#### Heuristic Metrics

**Pattern**: Pure Python computation, no external calls.

```python
class LevenshteinRatio(BaseMetric):
    def score(self, output: str, reference: str, **kwargs) -> ScoreResult:
        # Compute edit distance
        distance = compute_levenshtein(output, reference)
        ratio = 1 - (distance / max(len(output), len(reference)))

        return ScoreResult(
            value=ratio,
            name="levenshtien_ratio_metric"
        )
```

**Characteristics**:
- Fast (< 1ms)
- Deterministic
- No network calls
- Good for iteration

#### LLM Judge Metrics

**Pattern**: Call LLM to assess quality.

**Location**: `opik/evaluation/metrics/llm_judges/`

```python
class Hallucination(BaseMetric):
    def __init__(self, model: Optional[OpikBaseModel] = None):
        self.name = "hallucination_metric"
        self.model = model or OpikOpenAIModel()

    def score(
        self,
        input: str,
        output: str,
        context: List[str],
        **kwargs
    ) -> ScoreResult:
        # 1. Format prompt template
        prompt = format_template(
            HALLUCINATION_TEMPLATE,
            input=input,
            output=output,
            context=context
        )

        # 2. Call LLM judge
        response = self.model.generate(
            input=[{"role": "user", "content": prompt}]
        )

        # 3. Parse response
        parsed = parse_llm_response(response)

        return ScoreResult(
            value=parsed["score"],
            name=self.name,
            reason=parsed["reason"]
        )
```

**Characteristics**:
- Slow (LLM call: ~1-5 seconds)
- Non-deterministic (LLM variance)
- Network dependency
- More nuanced evaluation

**Common Components**:
- `template.py`: Prompt templates for judge
- `parser.py`: Parse LLM responses
- `metric.py`: Metric implementation

#### Conversation Metrics

**Pattern**: Evaluate multi-turn conversations.

**Location**:
- Base class: `opik/evaluation/metrics/conversation_metric_base.py`
- Heuristic implementations: `opik/evaluation/metrics/heuristics/conversation/`
- LLM-based implementations: `opik/evaluation/metrics/llm_judges/conversation/`

```python
class ConversationThreadMetric(BaseMetric):
    """Base class for conversation metrics"""

    @abc.abstractmethod
    def score_conversation(
        self,
        conversation: List[Dict[str, str]]
    ) -> ScoreResult:
        """Score entire conversation"""

class ConversationalCoherence(ConversationThreadMetric):
    def score_conversation(
        self,
        conversation: List[Dict[str, str]]
    ) -> ScoreResult:
        # Conversation format:
        # [
        #   {"role": "user", "content": "Hi"},
        #   {"role": "assistant", "content": "Hello"},
        #   ...
        # ]

        # Call LLM judge with full conversation
        score = self._evaluate_coherence(conversation)

        return ScoreResult(
            value=score,
            name="conversational_coherence_metric"
        )
```

**Characteristics**:
- Input: Full conversation history (list of turns)
- Output: Single score for entire thread
- Used by `evaluate_threads()` only

### Metric Arguments Validation

**Location**: `opik/evaluation/metrics/arguments_validator.py`

Metrics declare required arguments:

```python
# In metric implementation
class AnswerRelevance(BaseMetric):
    def score(self, input: str, output: str, **kwargs) -> ScoreResult:
        # Validation happens automatically
        pass

# Validator checks:
# 1. Extract function signature
# 2. Check required args are present in task output
# 3. Raise clear error if missing
```

**Error Example**:
```
MetricComputationError:
Metric 'answer_relevance_metric' requires argument 'input'
but it was not found in task output: {'output': '...', 'context': [...]}
```

## Parallel Execution Model

### Thread Pool Architecture

```
Main Thread
    │
    ├─► Create ThreadPoolExecutor(max_workers=N)
    │
    ├─► Submit N evaluation tasks
    │   │
    │   ├─► Worker 1: Process items[0], items[N], items[2N], ...
    │   ├─► Worker 2: Process items[1], items[N+1], ...
    │   └─► Worker N: Process items[N-1], items[2N-1], ...
    │
    ├─► Wait for completion (as_completed)
    │   └─► Show progress bar with tqdm
    │
    └─► Return aggregated results
```

### Task Distribution

```python
# Create partial functions (capture dataset_item and task)
evaluation_tasks = [
    functools.partial(
        self._evaluate_llm_task,
        item=dataset_item,
        task=user_task,
        trial_id=0
    )
    for dataset_item in dataset_items
]

# Execute in parallel
test_results = evaluation_tasks_executor.execute(
    evaluation_tasks,
    workers=self._workers,
    verbose=self._verbose
)
```

**Why functools.partial?**
- Creates zero-argument callable for thread pool
- Captures context (item, task, trial_id)
- Type-safe with `EvaluationTask` protocol

### Context Isolation

Each evaluation task runs in isolated context:

**Location**: `opik/evaluation/engine/helpers.py`

```python
@contextlib.contextmanager
def evaluate_llm_task_context(
    experiment: Experiment,              # From api_objects/experiment/
    dataset_item_id: str,
    trace_data: TraceData,               # From api_objects/trace/
    client: Opik                         # From api_objects/opik_client.py
) -> Iterator[None]:
    """
    Creates trace context for task execution.
    Ensures traces are properly linked to experiment.

    This context manager guarantees:
    1. Trace context is set before task runs
    2. Trace is sent to backend after task completes
    3. Experiment item is created linking trace to dataset item
    4. Context is cleaned up even on exceptions
    """
    try:
        # Set trace context
        # [opik/context_storage.py]
        context_storage.set_trace_data(trace_data)

        # Yield to task execution
        # User's task function runs here with trace context active
        yield

    except Exception as e:
        # Capture error in trace
        # [decorator/error_info_collector.py]
        error_info = error_info_collector.collect(e)
        trace_data.error_info = error_info
        raise  # Re-raise to caller

    finally:
        # Cleanup context (always runs)
        # [opik/context_storage.py]
        trace_data = context_storage.pop_trace_data()
        trace_data.init_end_time()

        # Send trace to backend
        # [api_objects/opik_client.py]
        client.trace(**trace_data.as_parameters)

        # Link trace to experiment
        # [api_objects/experiment/experiment_item.py]
        experiment_item = ExperimentItemReferences(
            dataset_item_id=dataset_item_id,
            trace_id=trace_data.id
        )

        # [api_objects/experiment/experiment.py]
        experiment.insert([experiment_item])
```

**Why context manager?**
- Guarantees cleanup even on exception
- Automatically links trace to experiment
- Error handling built-in

## Data Flow

### evaluate() Complete Flow

```
User calls evaluate(dataset, task, metrics)
    │ [evaluation/evaluator.py]
    ▼
┌──────────────────────────────────────────────┐
│ 1. Prepare Experiment                        │
│    [evaluation/evaluator.py]                 │
│                                              │
│    ├─► client.create_experiment()            │
│    │   [api_objects/experiment/experiment.py]│
│    ├─► Link to dataset                       │
│    └─► Store experiment_config               │
└────────┬─────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│ 2. Create EvaluationEngine                   │
│    [evaluation/engine/engine.py]             │
│                                              │
│    ├─► EvaluationEngine.__init__()           │
│    ├─► Store client, experiment              │
│    ├─► Store metrics, workers                │
│    └─► Setup verbosity                       │
└────────┬─────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│ 3. Fetch and Sample Dataset                  │
│    [api_objects/dataset/dataset.py]          │
│                                              │
│    ├─► dataset.__internal_api__get_items__() │
│    ├─► Apply nb_samples filter               │
│    ├─► Apply dataset_sampler                 │
│    │   [evaluation/samplers/]                │
│    └─► dataset_items: List[DatasetItem]      │
└────────┬─────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│ 4. Create Evaluation Tasks                   │
│    [evaluation/engine/engine.py]             │
│                                              │
│    ├─► For each dataset_item:                │
│    │   └─► functools.partial(               │
│    │         _evaluate_llm_task,             │
│    │         item=item, task=task            │
│    │       )                                 │
│    └─► evaluation_tasks: List[Callable]      │
└────────┬─────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│ 5. Execute Tasks (Thread Pool)               │
│    [evaluation/engine/evaluation_tasks_executor.py]│
│                                              │
│    ├─► ThreadPoolExecutor(workers=N)         │
│    ├─► pool.submit() for each task           │
│    ├─► futures.as_completed()                │
│    └─► Collect with tqdm progress bar        │
└────────┬─────────────────────────────────────┘
         │
         │ For each task (in parallel):
         │
         ▼
┌──────────────────────────────────────────────┐
│ 6. _evaluate_llm_task()                      │
│    [evaluation/engine/engine.py]             │
│                                              │
│ A. Setup Trace Context                       │
│    [evaluation/engine/helpers.py]            │
│    ├─► Create TraceData                      │
│    ├─► Set experiment metadata               │
│    └─► context_storage.set_trace_data()      │
│        [opik/context_storage.py]             │
│                                              │
│ B. Execute User Task                         │
│    ├─► task_output = task(item)              │
│    ├─► Capture @track calls (if any)         │
│    └─► Handle exceptions                     │
│        [decorator/error_info_collector.py]   │
│                                              │
│ C. Apply Metrics                             │
│    [evaluation/metrics/]                     │
│    ├─► For each metric:                      │
│    │   ├─► arguments_validator.validate()    │
│    │   ├─► arguments_helpers.map_keys()      │
│    │   ├─► metric.score(**fields)            │
│    │   │   [metrics/base_metric.py]          │
│    │   └─► Collect ScoreResult               │
│    └─► scores: List[ScoreResult]             │
│                                              │
│ D. Create Experiment Item                    │
│    [api_objects/experiment/experiment.py]    │
│    ├─► Build ExperimentItem                  │
│    ├─► Include scores as feedback            │
│    └─► experiment.insert([item])             │
│        [sends to backend via message queue]  │
│                                              │
│ E. Cleanup Context                           │
│    [evaluation/engine/helpers.py]            │
│    ├─► Finalize trace (end_time)             │
│    ├─► context_storage.pop_trace_data()      │
│    └─► client.trace(**trace_params)          │
│        [sends CreateTraceMessage]            │
│                                              │
│ F. Return TestResult                         │
│    [evaluation/test_result.py]               │
│    └─► input, output, scores, trace_id       │
└─────────┬────────────────────────────────────┘
          │
          ▼
┌──────────────────────────────────────────────┐
│ 7. Aggregate Results                         │
│    [evaluation/evaluator.py]                 │
│                                              │
│    ├─► Collect all TestResults               │
│    ├─► Calculate aggregate scores            │
│    │   [evaluation/score_statistics.py]      │
│    └─► Build EvaluationResult                │
│        [evaluation/evaluation_result.py]     │
└────────┬─────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│ 8. Display Report (if verbose)               │
│    [evaluation/report.py]                    │
│                                              │
│    ├─► Summary statistics                    │
│    ├─► Scores by metric                      │
│    └─► Experiment link                       │
└──────────────────────────────────────────────┘
         │
         ▼
    Return EvaluationResult
```

### evaluate_threads() Flow

```
User calls evaluate_threads(project_name, filter, metrics)
    │ [evaluation/threads/evaluator.py]
    ▼
┌──────────────────────────────────────────────┐
│ 1. Create ThreadsEvaluationEngine            │
│    [evaluation/threads/evaluation_engine.py] │
│                                              │
│    ├─► Store client, metrics                 │
│    └─► Configure num_workers                 │
└────────┬─────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│ 2. Fetch Threads from Backend                │
│    [api_objects/threads/threads_client.py]   │
│                                              │
│    ├─► threads_client.search_threads(        │
│    │       project_name, filter_string       │
│    │   )                                      │
│    │   [uses REST API to backend]            │
│    └─► Returns List[TraceThread]             │
│        (existing production data)            │
└────────┬─────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│ 3. For Each Thread (in parallel):            │
│    [evaluation/threads/evaluation_engine.py] │
│    [using ThreadPoolExecutor]                │
│                                              │
│    A. Fetch Traces from Backend              │
│       [api_objects/opik_client.py]           │
│       ├─► client.search_traces(              │
│       │       thread_id=thread.id            │
│       │   )                                   │
│       │   [REST API call to backend]         │
│       └─► Max: max_traces_per_thread         │
│           Returns List[Trace]                │
│                                              │
│    B. Build Conversation                     │
│       [evaluation/threads/helpers.py]        │
│       ├─► For each trace:                    │
│       │   ├─► trace_input_transform(input)   │
│       │   │   [user-provided function]       │
│       │   ├─► trace_output_transform(output) │
│       │   │   [user-provided function]       │
│       │   └─► Build conversation turn         │
│       └─► conversation: List[Turn]           │
│                                              │
│    C. Apply Metrics                          │
│       [evaluation/metrics/heuristics/conversation/]│
│       [evaluation/metrics/llm_judges/conversation/]│
│       ├─► For each metric:                   │
│       │   └─► metric.score_conversation(     │
│       │          conversation                │
│       │       )                               │
│       │       [may call LLM judge]           │
│       └─► scores: List[ScoreResult]          │
│                                              │
│    D. Log Scores to Backend                  │
│       [api_objects/threads/threads_client.py]│
│       └─► log_feedback_scores_to_thread(     │
│              thread_id, scores               │
│          )                                    │
│          [REST API call - attaches scores    │
│           to original thread]                │
│                                              │
└────────┬─────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│ 4. Aggregate Results                         │
│    [evaluation/threads/evaluator.py]         │
│                                              │
│    ├─► Collect all thread scores             │
│    └─► Build ThreadsEvaluationResult         │
│        [evaluation/threads/evaluation_result.py]│
└──────────────────────────────────────────────┘
         │
         ▼
    Return ThreadsEvaluationResult
```

## Evaluation Engine Internals

### _evaluate_llm_task() Detailed

This is the core function executed for each dataset item.

**Location**: `opik/evaluation/engine/engine.py`

```python
@opik.track(name="metrics_calculation")  # Creates span for observability
def _evaluate_llm_task(
    self,
    item: DatasetItem,               # From api_objects/dataset/dataset_item.py
    task: LLMTask,                   # User-provided function
    trial_id: int,
) -> TestResult:                     # From evaluation/test_result.py
    """
    Evaluate a single dataset item.

    Wrapped with @opik.track so all metrics computation
    is captured in a span for observability.
    """

    # 1. Create trace for this evaluation
    # [api_objects/trace/trace_client.py]
    trace_data = TraceData(
        id=generate_id(),                        # [id_helpers.py]
        name=f"evaluation_{item.id}",
        metadata={
            "experiment_id": self._experiment.id,
            "dataset_item_id": item.id,
            "trial_id": trial_id
        },
        project_name=self._project_name
    )

    # 2. Execute task in trace context
    # [evaluation/engine/helpers.py: evaluate_llm_task_context]
    with evaluate_llm_task_context(
        experiment=self._experiment,
        dataset_item_id=item.id,
        trace_data=trace_data,
        client=self._client
    ):
        # User's task function runs here
        # Any @track decorated functions create nested spans
        task_output = task(item.content)

    # 3. Apply metrics
    # [evaluation/metrics/]
    scores = []
    for metric in self._scoring_metrics:
        # Map task output keys to metric expected keys
        # [evaluation/engine/helpers.py: prepare_scoring_input]
        scoring_input = prepare_scoring_input(
            task_output,
            item.content,
            self._scoring_key_mapping
        )

        # Validate metric has required arguments
        # [evaluation/metrics/arguments_validator.py]
        validate_arguments(metric, scoring_input)

        # Compute score
        # [evaluation/metrics/base_metric.py: BaseMetric.score()]
        try:
            score = metric.score(**scoring_input)
            scores.append(score)
        except MetricComputationError as e:
            LOGGER.error(f"Metric {metric.name} failed: {e}")
            # Continue with other metrics

    # 4. Create experiment item with scores
    # [api_objects/experiment/experiment_item.py]
    experiment_item = ExperimentItem(
        dataset_item_id=item.id,
        trace_id=trace_data.id,
        input=task_output.get("input"),
        output=task_output.get("output"),
        feedback_scores=[
            {"name": score.name, "value": score.value, "reason": score.reason}
            for score in scores
        ]
    )

    # Note: experiment.insert() called in context manager cleanup
    # [api_objects/experiment/experiment.py]

    # 5. Return test result
    # [evaluation/test_result.py]
    return TestResult(
        dataset_item_id=item.id,
        input=task_output.get("input"),
        output=task_output.get("output"),
        scores=scores,
        trace_id=trace_data.id,
        experiment_item_id=experiment_item.id
    )
```

### Scoring Input Preparation

**Location**: `opik/evaluation/engine/helpers.py`

Maps task output and dataset item to metric-expected format.

**Purpose**: Bridge between user's task output keys and metric's expected argument names.

```python
def prepare_scoring_input(
    task_output: Dict[str, Any],                    # User task return value
    dataset_item: Dict[str, Any],                   # Original dataset item
    scoring_key_mapping: Optional[ScoringKeyMappingType]  # Optional key remapping
) -> Dict[str, Any]:
    """
    Combine task output and dataset item, apply key mapping.

    Priority (later overwrites earlier):
    1. Dataset item fields
    2. Task output fields (override)
    3. Scoring key mapping (remap)

    Used by: evaluation/engine/engine.py: _evaluate_llm_task()
    """

    # Start with dataset item
    scoring_input = dataset_item.copy()

    # Override with task output (task output takes precedence)
    scoring_input.update(task_output)

    # Apply key mapping (rename keys for metric compatibility)
    # [evaluation/metrics/arguments_helpers.py]
    if scoring_key_mapping:
        for target_key, source_key in scoring_key_mapping.items():
            if source_key in scoring_input:
                scoring_input[target_key] = scoring_input[source_key]

    return scoring_input
```

**Example**:
```python
# Dataset item
{"user_question": "What is AI?", "expected_answer": "..."}

# Task output
{"output": "AI is...", "context": [...]}

# Scoring key mapping
{"input": "user_question", "reference": "expected_answer"}

# Result for metrics
{
    "input": "What is AI?",      # From user_question
    "output": "AI is...",         # From task output
    "reference": "...",           # From expected_answer
    "context": [...]              # From task output
}
```

### Trial Execution

When `trial_count > 1`:

```python
# Outer loop over trials
for trial_id in range(trial_count):
    # Inner loop over dataset items
    for item in dataset_items:
        test_result = _evaluate_llm_task(item, task, trial_id)
        test_results.append(test_result)
```

**Purpose**: Measure variance in non-deterministic outputs.

**Result**: Multiple TestResults per dataset item (one per trial).

## Error Handling

### Task Execution Errors

**Location**: `opik/evaluation/engine/helpers.py` (in `evaluate_llm_task_context`)

```python
# In evaluate_llm_task_context context manager
try:
    # User's task function executes here
    task_output = task(dataset_item)
except Exception as e:
    # Capture error details
    # [decorator/error_info_collector.py]
    error_info = error_info_collector.collect(e)

    # Store in trace (visible in UI)
    trace_data.error_info = error_info

    # Re-raise exception (task fails, but other items continue)
    raise
```

**Design Decision**: Individual task failures don't stop evaluation. Each dataset item is independent.

**Implementation**:
- Error captured by context manager
- Logged to trace for debugging
- Exception propagated to thread pool
- Thread pool catches and logs but continues with other items

### Metric Computation Errors

**Location**: `opik/evaluation/metrics/base_metric.py`, `opik/exceptions.py`

```python
# From opik/exceptions.py
class MetricComputationError(OpikException):
    """Raised when metric computation fails"""

# Metric implementation
# [evaluation/metrics/heuristics/ or llm_judges/]
class CustomMetric(BaseMetric):
    def score(self, **kwargs) -> ScoreResult:
        try:
            result = compute_score(**kwargs)
        except Exception as e:
            # DON'T hide errors - raise explicit exception
            raise MetricComputationError(
                f"Failed to compute {self.name}: {e}"
            ) from e
```

**Design Principle**: Metrics must raise `MetricComputationError` on failure.

**Why explicit errors?**
- Missing data should be visible (not silently return 0)
- Misconfiguration should fail fast (not produce wrong results)
- Silent failures hide problems (hard to debug)

**Handling in engine** (`evaluation/engine/engine.py`):
```python
try:
    score = metric.score(**scoring_input)
    scores.append(score)
except MetricComputationError as e:
    LOGGER.error(f"Metric {metric.name} failed: {e}")
    # Continue with other metrics (partial results better than no results)
```

### Rate Limiting

**Location**: `opik/evaluation/engine/exception_analyzer.py`

```python
def is_llm_provider_rate_limit_error(exception: Exception) -> bool:
    """
    Detect rate limit errors from LLM providers.

    Checks for:
    - OpenAI RateLimitError
    - Anthropic RateLimitError
    - Other provider-specific exceptions
    """
    # Check exception type and attributes
    return isinstance(exception, (...))

# Usage in engine (could be implemented)
# [evaluation/engine/engine.py]
try:
    score = metric.score(**fields)
except Exception as e:
    if is_llm_provider_rate_limit_error(e):
        # Could implement backoff/retry strategy
        # Currently: log and continue
        LOGGER.warning(f"Rate limited: {e}")
        # Skip this metric
```

**Current behavior**: Rate limits are logged but not automatically retried in evaluation context (unlike tracing which has retry logic).

## Key Implementation Details

### 1. Experiment-Dataset Linkage

```python
# Experiment always linked to dataset
experiment = client.create_experiment(
    name="exp_1",
    dataset_name="dataset_1"  # Required
)

# Experiment items reference dataset items
experiment_item = ExperimentItem(
    dataset_item_id=dataset_item.id,  # Links to dataset item
    trace_id=trace_data.id,            # Links to trace
    # ... data
)
```

### 2. Trace-Experiment Linkage

```python
# Trace metadata includes experiment info
trace_data = TraceData(
    name="evaluation_task",
    metadata={
        "experiment_id": experiment.id,
        "dataset_item_id": dataset_item.id,
        "trial_id": trial_id
    }
)

# Experiment item references trace
experiment_item = ExperimentItemReferences(
    dataset_item_id=item.id,
    trace_id=trace_data.id
)
```

### 3. Metrics Spans

```python
# _evaluate_llm_task is decorated with @opik.track
@opik.track(name="metrics_calculation")
def _evaluate_llm_task(...):
    # Creates span for all metric computations
    # Nested under evaluation trace
```

**Span hierarchy**:
```
Trace: evaluation_task
  ├─ Span: llm_task
  └─ Span: metrics_calculation
      ├─ Span: hallucination_metric
      └─ Span: answer_relevance_metric
```


## Summary

The evaluation framework is designed for **systematic quality assessment**:

1. **4 evaluation methods** for different use cases
2. **Parallel execution engine** with ThreadPoolExecutor
3. **3 metric types**: Heuristic, LLM judges, conversation
4. **Automatic experiment tracking** and linkage
5. **Error resilience**: Individual failures don't stop evaluation
6. **Context isolation**: Each task runs in separate trace context

**Key Architectural Decisions**:
- **Synchronous design**: Evaluation waits for results (unlike tracing)
- **Thread pool for parallelism**: Not process pool or asyncio
- **Context managers**: Guarantee cleanup even on exceptions
- **Explicit error handling**: Metrics raise `MetricComputationError`, never hide failures
- **Progress reporting**: tqdm integration for different environments

For more information, see:
- [API and Data Flow](API_AND_DATA_FLOW.md) - Core architecture
- [Integrations](INTEGRATIONS.md) - LLM framework integrations
- [Testing](TESTING.md) - Testing evaluation features
