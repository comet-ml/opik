opik_context
============

The opik context module provides a way to access the current span and trace data from within a tracked function::

   from opik import opik_context, track

   @track
   def my_function():

      # Get the current span data
      span_data = opik_context.get_current_span_data()
      print(span_data)

      # Get the current trace data
      trace_data = opik_context.get_current_trace_data()
      print(trace_data)

      # Update the current span metadata
      opik_context.update_current_span(metadata={"my_key": "my_value"})

      # Update the current trace tags
      opik_context.update_current_trace(tags=["my_tag"])


You can also use the `get_distributed_trace_headers` function to get the distributed trace headers from the current trace::

   from opik import opik_context, track

   @track
   def my_function():
      distributed_trace_headers = opik_context.get_distributed_trace_headers()
      print(distributed_trace_headers)

You can learn more about each function in the following sections:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   get_current_span_data
   get_current_trace_data

   update_current_span
   update_current_trace

   get_distributed_trace_headers

Related Documentation
---------------------

For creating new traces and spans, see the :doc:`context managers <../context_manager/index>` documentation.
   