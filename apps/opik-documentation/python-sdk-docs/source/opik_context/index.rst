opik_context
============

The opik context module provides a way to access the current span and trace from within a tracked function::

   from opik import opik_context, track

   @track
    def my_function():
       span = opik_context.get_current_span()
       trace = opik_context.get_current_trace()

You can learn more about each function in the following sections:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   get_current_span
   get_current_trace
