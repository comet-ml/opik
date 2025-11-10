start_as_current_span
=====================

.. autofunction:: opik.start_as_current_span

Examples
--------

Basic usage
~~~~~~~~~~~

.. code-block:: python

   import opik

   with opik.start_as_current_span("my_operation") as span:
       # Your code here
       span.metadata["custom_key"] = "custom_value"
       print("Executing operation...")

With input and output data
~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   import opik

   with opik.start_as_current_span(
       name="llm_completion",
       type="llm",
       input={"prompt": "What is the capital of France?"},
       output={"response": "The capital of France is Paris."},
       tags=["llm", "completion"],
       metadata={"model": "gpt-3.5-turbo"}
   ) as span:
       # Your LLM code here
       pass

With distributed tracing
~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   import os
   import opik

   # extract headers from environment
   distributed_trace_headers = os.environ.get("opik_distributed_trace_headers")

   with opik.start_as_current_span(
       "process_request",
       opik_distributed_trace_headers=distributed_trace_headers
   ) as span:
       # Your code here
       pass

Error handling
~~~~~~~~~~~~~~

.. code-block:: python

   import opik

   try:
       with opik.start_as_current_span("risky_operation") as span:
           # Code that might fail
           raise ValueError("Something went wrong")
   except ValueError as e:
       # The span will automatically capture error information
       print(f"Operation failed: {e}")
       # Error details are stored in span.error_info