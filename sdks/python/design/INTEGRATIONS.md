# Opik Python SDK: Integrations

## Table of Contents

- [Overview](#overview)
- [Integration Patterns](#integration-patterns)
- [Supported Integrations](#supported-integrations)
- [Building New Integrations](#building-new-integrations)
- [Advanced Topics](#advanced-topics)

## Overview

The Opik Python SDK provides seamless integration with popular LLM frameworks and providers. Integrations automatically capture traces, spans, usage data, and costs without requiring manual instrumentation.

### Integration Goals

1. **Zero-overhead**: Minimal performance impact
2. **Automatic tracking**: Capture calls without manual instrumentation
3. **Provider-specific**: Support unique features of each library
4. **Consistent API**: Similar usage patterns across integrations
5. **Optional**: Can be disabled or configured

## Integration Patterns

The SDK supports two main integration patterns, chosen based on the target library's architecture.

### Pattern 1: Decorator-Based Integration

**Use when**: The library exposes client objects with methods to wrap.

**How it works**: Wrap client methods to intercept calls and add tracking.

**Examples**: OpenAI, Anthropic, Bedrock

```python
from opik.integrations.openai import track_openai
import openai

# Create client
client = openai.OpenAI()

# Wrap with tracking
tracked_client = track_openai(
    openai_client=client,
    project_name="my_project"
)

# Use normally - automatically tracked
response = tracked_client.chat.completions.create(
    model="gpt-4",
    messages=[{"role": "user", "content": "Hello"}]
)
```

**Architecture**:

```
User calls tracked_client.method()
        │
        ▼
OpikDecorator intercepts
        │
        ├─► Start span
        │   - Capture input
        │   - Set span type="llm"
        │   - Record start_time
        │
        ▼
Call original method
        │
        ▼
Get response
        │
        ├─► End span
        │   - Capture output
        │   - Extract usage
        │   - Calculate cost
        │   - Set metadata
        │
        ▼
Return response to user
```

### Pattern 2: Callback-Based Integration

**Use when**: The library supports callback/hook mechanisms.

**How it works**: Implement library-specific callback interface that receives execution events.

**Examples**: LangChain, LlamaIndex, Haystack

```python
from opik.integrations.langchain import OpikTracer
from langchain.chains import LLMChain

# Create tracer
tracer = OpikTracer(project_name="my_project")

# Use with LangChain operations
chain = LLMChain(llm=..., prompt=...)
result = chain.invoke(
    input={"query": "test"},
    config={"callbacks": [tracer]}
)
```

**Architecture**:

```
User invokes chain
        │
        ▼
LangChain execution starts
        │
        ├─► on_chain_start(chain_info)
        │   │
        │   └─► OpikTracer: Create trace/span
        │
        ├─► on_llm_start(llm_info)
        │   │
        │   └─► OpikTracer: Create nested span
        │
        ├─► on_llm_end(llm_result)
        │   │
        │   └─► OpikTracer: Update span, add usage
        │
        └─► on_chain_end(chain_result)
            │
            └─► OpikTracer: Finalize trace/span
```

## Supported Integrations

### OpenAI

**Integration type**: Decorator-based

**Location**: `opik/integrations/openai/`

**Features**:
- Chat completions
- Streaming responses
- Function calling
- Responses API (Structured Outputs)
- Usage tracking (tokens, costs)
- Automatic model detection

**Basic Usage**:

```python
from opik.integrations.openai import track_openai
import openai

client = openai.OpenAI()
tracked_client = track_openai(client, project_name="openai-app")

# Regular completion
response = tracked_client.chat.completions.create(
    model="gpt-4",
    messages=[{"role": "user", "content": "Hello"}]
)

# Streaming
stream = tracked_client.chat.completions.create(
    model="gpt-4",
    messages=[{"role": "user", "content": "Hello"}],
    stream=True
)

for chunk in stream:
    print(chunk.choices[0].delta.content, end="")

# Function calling
response = tracked_client.chat.completions.create(
    model="gpt-4",
    messages=[{"role": "user", "content": "What's the weather?"}],
    tools=[{
        "type": "function",
        "function": {
            "name": "get_weather",
            "parameters": {...}
        }
    }]
)

# Structured outputs (Responses API)
response = tracked_client.responses.create(
    model="gpt-4",
    input=[{"role": "user", "content": "Analyze this"}],
    response_format={"type": "json_schema", "json_schema": {...}}
)
```

**Captured Data**:
- Input messages
- Output/reasoning
- Model name
- Usage (prompt_tokens, completion_tokens, total_tokens)
- Cost (calculated from usage and model pricing)
- Function calls and tool use
- Metadata (temperature, max_tokens, etc.)

**Async Support**:

```python
async_client = openai.AsyncOpenAI()
tracked_async = track_openai(async_client)

response = await tracked_async.chat.completions.create(
    model="gpt-4",
    messages=[{"role": "user", "content": "Hello"}]
)
```

### Anthropic

**Integration type**: Decorator-based

**Location**: `opik/integrations/anthropic/`

**Features**:
- Messages API
- Streaming responses
- Tool use
- Usage tracking
- Cost calculation

**Basic Usage**:

```python
from opik.integrations.anthropic import track_anthropic
import anthropic

client = anthropic.Anthropic()
tracked_client = track_anthropic(client, project_name="anthropic-app")

# Regular completion
response = tracked_client.messages.create(
    model="claude-3-sonnet-20240229",
    max_tokens=1024,
    messages=[{"role": "user", "content": "Hello"}]
)

# Streaming
stream = tracked_client.messages.create(
    model="claude-3-sonnet-20240229",
    max_tokens=1024,
    messages=[{"role": "user", "content": "Hello"}],
    stream=True
)

for event in stream:
    print(event.delta.text, end="")

# Tool use
response = tracked_client.messages.create(
    model="claude-3-sonnet-20240229",
    max_tokens=1024,
    messages=[{"role": "user", "content": "What's the weather?"}],
    tools=[{
        "name": "get_weather",
        "description": "Get weather for location",
        "input_schema": {...}
    }]
)
```

**Captured Data**:
- Input messages (with system prompts)
- Output text and stop reason
- Model name
- Usage (input_tokens, output_tokens)
- Cost
- Tool use information

### AWS Bedrock

**Integration type**: Decorator-based

**Location**: `opik/integrations/bedrock/`

**Features**:
- Converse API support
- InvokeModel support
- Multiple model formats (Claude, Llama, Titan, etc.)
- Usage tracking
- Region-specific pricing

**Basic Usage**:

```python
from opik.integrations.bedrock import track_bedrock
import boto3

client = boto3.client('bedrock-runtime', region_name='us-east-1')
tracked_client = track_bedrock(client, project_name="bedrock-app")

# Converse API (recommended)
response = tracked_client.converse(
    modelId="anthropic.claude-3-sonnet-20240229-v1:0",
    messages=[{"role": "user", "content": [{"text": "Hello"}]}]
)

# InvokeModel (legacy)
response = tracked_client.invoke_model(
    modelId="anthropic.claude-v2",
    body=json.dumps({
        "prompt": "\n\nHuman: Hello\n\nAssistant:",
        "max_tokens_to_sample": 1000
    })
)
```

**Captured Data**:
- Input messages/prompts
- Output text
- Model ID
- Usage (varies by model)
- Cost (region-specific)
- Request metadata

### LangChain

**Integration type**: Callback-based

**Location**: `opik/integrations/langchain/`

**Features**:
- Chains
- Agents
- Tools
- Nested operations
- LLM calls
- Retriever calls

**Basic Usage**:

```python
from opik.integrations.langchain import OpikTracer
from langchain.chains import LLMChain
from langchain_openai import ChatOpenAI

# Create tracer
tracer = OpikTracer(project_name="langchain-app")

# Use with chain
llm = ChatOpenAI(model="gpt-4")
chain = LLMChain(llm=llm, prompt=prompt_template)

result = chain.invoke(
    {"query": "What is AI?"},
    config={"callbacks": [tracer]}
)

# Use with agent
from langchain.agents import AgentExecutor, create_openai_functions_agent

agent = create_openai_functions_agent(llm=llm, tools=tools, prompt=prompt)
agent_executor = AgentExecutor(agent=agent, tools=tools)

result = agent_executor.invoke(
    {"input": "Search for information about AI"},
    config={"callbacks": [tracer]}
)
```

**Captured Data**:
- Chain inputs/outputs
- LLM calls (prompts, responses, usage)
- Tool calls
- Agent steps
- Retriever queries
- Execution hierarchy

### LangGraph

**Integration**: Works with LangChain tracer

**Basic Usage**:

```python
from opik.integrations.langchain import OpikTracer
from langgraph.graph import StateGraph

tracer = OpikTracer(project_name="langgraph-app")

# Define graph
workflow = StateGraph(state_schema)
workflow.add_node("step1", step1_function)
workflow.add_node("step2", step2_function)
workflow.add_edge("step1", "step2")

app = workflow.compile()

# Run with tracing
result = app.invoke(
    {"input": "test"},
    config={"callbacks": [tracer]}
)
```

### LlamaIndex

**Integration type**: Callback-based

**Location**: `opik/integrations/llama_index/`

**Features**:
- Query engines
- Chat engines
- Retrievers
- LLM calls
- Embeddings

**Basic Usage**:

```python
from opik.integrations.llama_index import OpikCallbackHandler
from llama_index.core import VectorStoreIndex, Settings

# Configure globally
opik_handler = OpikCallbackHandler(project_name="llamaindex-app")
Settings.callback_manager.add_handler(opik_handler)

# Use normally
index = VectorStoreIndex.from_documents(documents)
query_engine = index.as_query_engine()

response = query_engine.query("What is AI?")
```

**Captured Data**:
- Queries and responses
- Retrieved documents
- LLM calls
- Embeddings operations
- Reranking steps

### DSPy

**Integration type**: Decorator-based

**Location**: `opik/integrations/dspy/`

**Features**:
- Program execution
- Module calls
- LM interactions
- Optimizer steps

**Basic Usage**:

```python
from opik.integrations.dspy import track_dspy
import dspy

# Configure DSPy
lm = dspy.OpenAI(model="gpt-4")
dspy.configure(lm=lm)

# Track DSPy
track_dspy(project_name="dspy-app")

# Use DSPy programs normally
class CoT(dspy.Module):
    def __init__(self):
        super().__init__()
        self.generate = dspy.ChainOfThought("question -> answer")
    
    def forward(self, question):
        return self.generate(question=question)

cot = CoT()
response = cot(question="What is AI?")  # Automatically tracked
```

### CrewAI

**Integration type**: Callback-based

**Location**: `opik/integrations/crewai/`

**Features**:
- Crew execution
- Agent tasks
- Tool usage
- Multi-agent interactions

**Basic Usage**:

```python
from opik.integrations.crewai import OpikCrew
from crewai import Agent, Task, Crew

# Define agents and tasks
researcher = Agent(
    role="Researcher",
    goal="Research topics",
    tools=[search_tool]
)

task = Task(
    description="Research AI trends",
    agent=researcher
)

# Create crew with Opik tracking
crew = Crew(
    agents=[researcher],
    tasks=[task]
)

# Track execution
opik_crew = OpikCrew(crew, project_name="crewai-app")
result = opik_crew.kickoff()
```

### Haystack

**Integration type**: Callback-based

**Location**: `opik/integrations/haystack/`

**Features**:
- Pipeline execution
- Component tracking
- RAG workflows

**Basic Usage**:

```python
from opik.integrations.haystack import OpikConnector
from haystack import Pipeline
from haystack.components.generators import OpenAIGenerator

# Create pipeline
pipeline = Pipeline()
pipeline.add_component("generator", OpenAIGenerator(model="gpt-4"))

# Add Opik tracking
opik_connector = OpikConnector(project_name="haystack-app")
pipeline.add_component("tracer", opik_connector)

# Run pipeline
result = pipeline.run({"prompt": "What is AI?"})
```

### LiteLLM

**Integration type**: Decorator + Logger-based

**Location**: `opik/integrations/litellm/`

**Features**:
- Unified interface for 100+ LLM providers
- Automatic provider detection
- Usage tracking
- Cost calculation

**Basic Usage**:

```python
from opik.integrations.litellm import track_litellm
import litellm

# Enable tracking
track_litellm(project_name="litellm-app")

# Use any supported model
response = litellm.completion(
    model="gpt-4",
    messages=[{"role": "user", "content": "Hello"}]
)

# Works with any provider
response = litellm.completion(
    model="claude-3-sonnet-20240229",
    messages=[{"role": "user", "content": "Hello"}]
)

response = litellm.completion(
    model="command-r-plus",  # Cohere
    messages=[{"role": "user", "content": "Hello"}]
)
```

### Google GenAI

**Integration type**: Decorator-based

**Location**: `opik/integrations/genai/`

**Features**:
- Gemini models
- Multi-modal inputs
- Streaming
- Function calling

**Basic Usage**:

```python
from opik.integrations.genai import track_genai
import google.generativeai as genai

# Configure
genai.configure(api_key="your_key")

# Track
track_genai(project_name="genai-app")

# Use normally
model = genai.GenerativeModel("gemini-pro")
response = model.generate_content("What is AI?")

# Multi-modal
image = PIL.Image.open("image.jpg")
response = model.generate_content(["Describe this image", image])
```

### AISuite

**Integration type**: Decorator-based

**Location**: `opik/integrations/aisuite/`

**Features**:
- Unified interface
- Multiple providers
- Automatic tracking

**Basic Usage**:

```python
from opik.integrations.aisuite import track_aisuite
import aisuite

client = aisuite.Client()
tracked_client = track_aisuite(client, project_name="aisuite-app")

# Use with any provider
response = tracked_client.chat.completions.create(
    model="openai:gpt-4",
    messages=[{"role": "user", "content": "Hello"}]
)
```

### ADK (Agent Development Kit)

**Integration type**: Decorator-based

**Location**: `opik/integrations/adk/`

**Features**:
- Agent execution
- Graph-based workflows
- Tool usage
- State management

**Basic Usage**:

```python
from opik.integrations.adk import track_adk
from adk import Agent, Tool

track_adk(project_name="adk-app")

# Define agent
agent = Agent(
    name="assistant",
    tools=[search_tool, calculator_tool]
)

# Run agent
result = agent.run("Search for AI trends and calculate growth")
```

## Building New Integrations

### Step 1: Choose Integration Pattern

**Decorator-Based** if:
- Library exposes client objects
- Methods return responses
- Can wrap method calls

**Callback-Based** if:
- Library has callback/hook system
- Events fire during execution
- Need to track execution lifecycle

### Step 2: Implement Integration

#### Decorator-Based Template

```python
# opik/integrations/mylib/decorator.py

from opik.decorator import base_track_decorator
from opik.decorator import arguments_helpers
import mylib

class MyLibDecorator(base_track_decorator.BaseTrackDecorator):
    """Decorator for MyLib integration"""
    
    def __init__(self, project_name: Optional[str] = None):
        super().__init__()
        self._project_name = project_name
    
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        """Extract input data before function execution"""
        
        # Extract relevant inputs from args/kwargs
        # This is library-specific
        model = kwargs.get("model")
        messages = kwargs.get("messages", [])
        
        input_data = {
            "model": model,
            "messages": messages
        }
        
        return arguments_helpers.StartSpanParameters(
            name=f"{func.__name__}",
            input=input_data,
            type="llm",  # or "tool", "general"
            tags=["mylib"],
            project_name=self._project_name or track_options.project_name,
        )
    
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        """Extract output data after function execution"""
        
        # Extract relevant data from response
        # This is library-specific
        if isinstance(output, mylib.Response):
            output_text = output.content
            usage = {
                "completion_tokens": output.usage.completion_tokens,
                "prompt_tokens": output.usage.prompt_tokens,
                "total_tokens": output.usage.total_tokens,
            }
            
            # Update span with library-specific data
            current_span_data.model = output.model
            current_span_data.provider = "mylib"
            current_span_data.usage = usage
            
            # Calculate cost if possible
            if hasattr(output, 'cost'):
                current_span_data.total_cost = output.cost
            
            output_data = {"output": output_text}
        else:
            output_data = {"output": str(output)}
        
        return arguments_helpers.EndSpanParameters(output=output_data)


def track_mylib(
    client: mylib.Client,
    project_name: Optional[str] = None,
) -> mylib.Client:
    """
    Track MyLib calls with Opik.
    
    Args:
        client: MyLib client instance
        project_name: Optional project name
    
    Returns:
        Wrapped client with tracking
    """
    decorator = MyLibDecorator(project_name=project_name)
    
    # Wrap specific methods
    client.chat.completions.create = decorator.track(
        client.chat.completions.create,
        name="mylib_completion",
        type="llm"
    )
    
    return client
```

#### Callback-Based Template

```python
# opik/integrations/mylib/tracer.py

from opik.api_objects import opik_client, span, trace
from opik import context_storage
import mylib

class OpikMyLibTracer(mylib.BaseTracer):
    """Tracer for MyLib integration"""
    
    def __init__(self, project_name: Optional[str] = None):
        super().__init__()
        self._project_name = project_name
        self._client = opik_client.get_client_cached()
        self._run_to_span: Dict[str, str] = {}
    
    def on_chain_start(self, run_id: str, chain_info: dict) -> None:
        """Called when chain starts"""
        
        # Check for existing trace
        trace_data = context_storage.get_trace_data()
        if trace_data is None:
            # Create new trace
            trace_id = self._client.trace(
                name=chain_info["name"],
                input=chain_info["inputs"],
                project_name=self._project_name,
            )
            # Store for cleanup
            self._run_to_trace[run_id] = trace_id
        
        # Create span
        span_id = self._client.span(
            name=chain_info["name"],
            input=chain_info["inputs"],
            type="general",
        )
        
        self._run_to_span[run_id] = span_id
    
    def on_llm_start(self, run_id: str, llm_info: dict) -> None:
        """Called when LLM call starts"""
        
        span_id = self._client.span(
            name="llm_call",
            input={"messages": llm_info["messages"]},
            type="llm",
            model=llm_info.get("model"),
            provider=llm_info.get("provider"),
        )
        
        self._run_to_span[run_id] = span_id
    
    def on_llm_end(self, run_id: str, llm_result: dict) -> None:
        """Called when LLM call ends"""
        
        span_id = self._run_to_span.get(run_id)
        if span_id:
            self._client.span(
                id=span_id,
                output={"response": llm_result["output"]},
                usage=llm_result.get("usage"),
            )
    
    def on_chain_end(self, run_id: str, chain_result: dict) -> None:
        """Called when chain ends"""
        
        span_id = self._run_to_span.get(run_id)
        if span_id:
            self._client.span(
                id=span_id,
                output=chain_result["outputs"],
            )
            del self._run_to_span[run_id]
        
        # Clean up trace if we created it
        if run_id in self._run_to_trace:
            del self._run_to_trace[run_id]
```

### Step 3: Add Tests

```python
# tests/library_integration/mylib/test_mylib.py

def test_mylib_basic_completion(fake_backend):
    """Test basic MyLib completion tracking"""
    
    # Setup
    client = mylib.Client()
    tracked_client = track_mylib(client, project_name="test")
    
    # Execute
    response = tracked_client.chat.completions.create(
        model="test-model",
        messages=[{"role": "user", "content": "Hello"}]
    )
    
    opik.flush_tracker()
    
    # Verify
    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]
    
    assert trace.name == "mylib_completion"
    assert trace.spans[0].type == "llm"
    assert trace.spans[0].provider == "mylib"
    assert trace.spans[0].usage is not None
```

### Step 4: Add Documentation

```python
# opik/integrations/mylib/__init__.py

"""
MyLib Integration for Opik.

This integration provides automatic tracking for MyLib calls.

Basic Usage:
    from opik.integrations.mylib import track_mylib
    import mylib
    
    client = mylib.Client()
    tracked_client = track_mylib(client, project_name="my_project")
    
    response = tracked_client.chat.completions.create(
        model="test-model",
        messages=[{"role": "user", "content": "Hello"}]
    )

Features:
    - Automatic span creation for LLM calls
    - Usage tracking (tokens)
    - Cost calculation
    - Streaming support
"""

from .decorator import track_mylib

__all__ = ["track_mylib"]
```

## Advanced Topics

### Handling Streaming Responses

```python
def _streams_handler(
    self,
    output: Any,
    capture_output: bool,
    generations_aggregator: Optional[Callable],
) -> Optional[Any]:
    """Handle streaming responses"""
    
    if hasattr(output, '__iter__') and not isinstance(output, (str, bytes)):
        # Wrap generator to accumulate chunks
        return GeneratorProxy(
            generator=output,
            span_data=self._current_span_data,
            capture_output=capture_output,
        )
    
    return None

class GeneratorProxy:
    """Wraps generator to accumulate chunks"""
    
    def __init__(self, generator, span_data, capture_output):
        self._generator = generator
        self._span_data = span_data
        self._capture_output = capture_output
        self._accumulated_output = []
        self._accumulated_usage = {}
    
    def __iter__(self):
        return self
    
    def __next__(self):
        try:
            chunk = next(self._generator)
            
            # Accumulate data
            if self._capture_output:
                self._accumulated_output.append(extract_content(chunk))
            
            # Accumulate usage
            if hasattr(chunk, 'usage'):
                self._accumulate_usage(chunk.usage)
            
            return chunk
            
        except StopIteration:
            # Generator exhausted, update span
            self._finalize_span()
            raise
    
    def _finalize_span(self):
        """Update span with accumulated data"""
        if self._accumulated_output:
            self._span_data.output = {
                "output": "".join(self._accumulated_output)
            }
        
        if self._accumulated_usage:
            self._span_data.usage = self._accumulated_usage
```

### Provider-Specific Usage Tracking

```python
# opik/llm_usage/opik_usage_factory.py

def build_opik_usage(
    provider: Union[str, LLMProvider],
    usage: Dict[str, Any],
) -> OpikUsage:
    """Build OpikUsage from provider-specific format"""
    
    builders = {
        LLMProvider.OPENAI: OpikUsage.from_openai_dict,
        LLMProvider.ANTHROPIC: OpikUsage.from_anthropic_dict,
        LLMProvider.BEDROCK: OpikUsage.from_bedrock_dict,
        LLMProvider.GOOGLE: OpikUsage.from_google_dict,
    }
    
    builder = builders.get(provider)
    if builder:
        return builder(usage)
    
    # Fallback to generic
    return OpikUsage.from_dict(usage)
```

### Cost Calculation

```python
# Pricing tables by provider and model
OPENAI_PRICING = {
    "gpt-4": {
        "input": 0.03 / 1000,   # per token
        "output": 0.06 / 1000,
    },
    "gpt-3.5-turbo": {
        "input": 0.0005 / 1000,
        "output": 0.0015 / 1000,
    },
}

def calculate_cost(model: str, usage: OpikUsage, provider: str) -> float:
    """Calculate cost based on usage"""
    
    pricing = get_pricing(provider, model)
    if not pricing:
        return 0.0
    
    input_cost = usage.prompt_tokens * pricing["input"]
    output_cost = usage.completion_tokens * pricing["output"]
    
    return input_cost + output_cost
```

### Distributed Tracing

```python
# Service A
import opik

@opik.track
def service_a_function():
    # Get headers for remote call
    headers = opik.get_distributed_trace_headers()
    
    # Make remote call
    response = requests.post(
        "http://service-b/endpoint",
        headers=headers,  # Pass trace context
        json={"data": "test"}
    )

# Service B
@opik.track
def service_b_endpoint(request):
    # Receives headers automatically via framework integration
    # Continues trace from Service A
    result = process_request(request)
    return result
```

## Summary

The Opik Python SDK provides comprehensive integrations for popular LLM frameworks:

1. **Two integration patterns**: Decorator-based and callback-based
2. **12+ integrations**: OpenAI, Anthropic, LangChain, and more
3. **Automatic tracking**: No manual instrumentation needed
4. **Provider-specific features**: Usage, costs, streaming
5. **Extensible**: Easy to add new integrations

For more information, see:
- [API and Data Flow](API_AND_DATA_FLOW.md) - Core architecture
- [Evaluation](EVALUATION.md) - Evaluation framework
- [Testing](TESTING.md) - Testing integrations

