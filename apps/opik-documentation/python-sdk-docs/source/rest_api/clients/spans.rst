Spans Client
============

The Spans client provides methods for managing spans in the Opik platform.

.. autoclass:: opik.rest_api.spans.client.SpansClient
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
   
   # Get a span by ID
   span = client.rest_client.spans.get_span_by_id("span-id")
   
   # Search for spans
   spans = client.rest_client.spans.search_spans(
       project_name="my-project",
       trace_id="trace-id",
       max_results=100
   )
   
   # Add feedback score to a span
   client.rest_client.spans.add_span_feedback_score(
       id="span-id",
       name="relevance",
       value=0.85
   )