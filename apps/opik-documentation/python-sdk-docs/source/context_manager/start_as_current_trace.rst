start_as_current_trace
======================

.. autofunction:: opik.start_as_current_trace

Examples
--------

Basic usage
~~~~~~~~~~~

.. code-block:: python

   import opik

   with opik.start_as_current_trace("my_trace") as trace:
       # Your code here
       trace.metadata["custom_key"] = "custom_value"
       print("Executing trace...")

With input and output data
~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   import opik

   with opik.start_as_current_trace(
       name="user_query_processing",
       input={"user_question": "What is machine learning?"},
       output={"answer": "Machine learning is a subset of AI..."},
       tags=["user_query", "ai"],
       metadata={"session_id": "abc123"}
   ) as trace:
       # Your processing code here
       pass

With conversational threads support using `thread_id` identifier
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   import opik
   import threading

   with opik.start_as_current_trace(
       "chatbot_conversation",
       project_name="my_project",
       thread_id="00f067aa0ba902b7",
   ) as trace:
       # Your processing code here
       pass


Error handling
~~~~~~~~~~~~~~

.. code-block:: python

   import opik

   try:
       with opik.start_as_current_trace("risky_trace") as trace:
           # Code that might fail
           raise RuntimeError("Something went wrong")
   except RuntimeError as e:
       # The trace will automatically capture error information
       print(f"Trace failed: {e}")
       # Error details are stored in trace.error_info

Nested spans within a trace
~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   import opik

   with opik.start_as_current_trace("main_workflow") as trace:
       # Main workflow code
       
       with opik.start_as_current_span("sub_operation_1") as span1:
           # First sub-operation
           pass
       
       with opik.start_as_current_span("sub_operation_2") as span2:
           # Second sub-operation
           pass
