distributed_headers
===================

.. autofunction:: opik.decorator.context_manager.distributed_headers

Examples
--------

Basic usage in a server endpoint
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   from opik.decorator.context_manager import distributed_headers
   from fastapi import FastAPI, Request

   app = FastAPI()

   @app.post("/generate_response")
   def generate_llm_response(request: Request) -> str:
       # Extract distributed headers from the incoming request
       headers = {
           "opik_trace_id": request.headers.get("opik_trace_id"),
           "opik_parent_span_id": request.headers.get("opik_parent_span_id"),
       }

       # Use the context manager to handle distributed headers
       with distributed_headers(headers):
           result = my_llm_application()

       return result

With flush enabled
~~~~~~~~~~~~~~~~~~

.. code-block:: python

   from opik.decorator.context_manager import distributed_headers

   def process_request(headers_dict):
       # Flush data immediately after the root span is processed
       with distributed_headers(headers_dict, flush=True):
           # Your processing logic here
           pass

Using with the track decorator
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   from opik import track
   from opik.decorator.context_manager import distributed_headers
   from flask import Flask, request

   app = Flask(__name__)

   @track()
   def my_llm_function(prompt: str) -> str:
       # Your LLM logic here
       return "response"

   @app.route("/api/generate", methods=["POST"])
   def api_endpoint():
       # Extract headers from the request
       headers = {
           "opik_trace_id": request.headers.get("opik_trace_id"),
           "opik_parent_span_id": request.headers.get("opik_parent_span_id"),
       }

       # Create distributed trace context
       with distributed_headers(headers):
           result = my_llm_function(prompt=request.json.get("prompt"))

       return {"result": result}

Error handling
~~~~~~~~~~~~~~

.. code-block:: python

   from opik.decorator.context_manager import distributed_headers

   try:
       with distributed_headers(incoming_headers):
           # Code that might fail
           result = risky_operation()
   except Exception as e:
       # The context manager automatically logs the error
       # and attaches error information to the root span
       print(f"Operation failed: {e}")

Creating nested spans within distributed context
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: python

   from opik import track
   from opik.decorator.context_manager import distributed_headers

   @track()
   def sub_operation():
       # This will be a nested span
       pass

   def handle_distributed_request(headers_dict):
       # Create the root span with distributed headers
       with distributed_headers(headers_dict):
           # These tracked functions will be nested under the root span
           sub_operation()