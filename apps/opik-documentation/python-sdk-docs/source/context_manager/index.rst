Context Managers
================

Opik provides context managers for creating and managing traces and spans in your application. These context managers allow you to easily instrument your code with tracing capabilities while ensuring proper cleanup and error handling.

Context managers are particularly useful when you need fine-grained control over trace and span creation, or when working with code that doesn't fit well with the `@track` decorator pattern.

Available Context Managers
---------------------------

.. toctree::
   :maxdepth: 1
   :titlesonly:

   start_as_current_span
   start_as_current_trace

Key Features
------------

- **Automatic Error Handling**: Context managers automatically capture and log errors that occur within their scope
- **Distributed Tracing**: Support for distributed tracing headers to maintain trace context across service boundaries
- **Flexible Configuration**: Rich set of parameters for customizing trace and span behavior
- **Resource Management**: Automatic cleanup and flushing of trace data
- **Nested Support**: Context managers can be nested to create hierarchical trace structures

Basic Usage Pattern
-------------------

.. code-block:: python

   import opik

   with opik.start_as_current_trace("my-trace", project_name="my-project") as trace:
        # Your application logic here
        trace.input = {"user_query": "Explain quantum computing"}
        trace.output = {"response": "Quantum computing is..."}
        trace.tags = ["chat"]
        trace.metadata = {"model": "gpt-4", "temperature": 0.7}

        # Basic span creation
        with opik.start_as_current_span("llm-call", type="llm", project_name="my-project") as span:
            # Your LLM call here
            span.input = {"prompt": "Explain quantum computing"}
            span.output = {"response": "Quantum computing is..."}
            span.model = "gpt-4"
            span.provider = "openai"
            span.usage = {
                "prompt_tokens": 10,
                "completion_tokens": 50,
                "total_tokens": 60
            }


When to Use Context Managers
----------------------------

Use context managers when:

- You need explicit control over trace/span lifecycle
- Working with code that can't be easily decorated
- Implementing custom error handling patterns
- Building distributed tracing across service boundaries
- Creating complex nested trace hierarchies

For simpler use cases, consider using the `@track` decorator instead.