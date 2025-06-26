Traces Client
=============

The Traces client provides methods for managing traces in the Opik platform.

.. autoclass:: opik.rest_api.traces.client.TracesClient
   :members:
   :undoc-members:
   :show-inheritance:
   :inherited-members:
   :exclude-members: with_raw_response

Usage Example
-------------

.. code-block:: python

   import opik
   
   client = opik.Opik()
   
   # Get a trace by ID
   trace = client.rest_client.traces.get_trace_by_id("trace-id")
   
   # Search for traces
   traces = client.rest_client.traces.search_traces(
       project_name="my-project",
       max_results=100
   )
   
   # Add feedback score to a trace
   client.rest_client.traces.add_trace_feedback_score(
       id="trace-id",
       name="accuracy",
       value=0.95
   )